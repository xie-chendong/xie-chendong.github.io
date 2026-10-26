---
title: "使用 Spring AOP、Redis ZSET 与 Lua 实现接口滑动窗口限流"
description: "通过 Spring AOP 注解统一接入接口限流，使用 Redis ZSET 记录滑动窗口内的请求，并以 Lua 脚本保证清理、计数和写入的原子性，讲解实现思路、关键代码与注意事项。"
date: 2026-8-26
tags: [Spring, Redis, 限流]
categories: [JAVA后端]
---

# 使用 Spring AOP、Redis ZSET 与 Lua 实现接口滑动窗口限流

## 配套源码

- [下载配套源码包（ZIP）](/downloads/redis-rate-limit/redis-rate-limit.zip)
- [接入说明与依赖清单（README）](/downloads/redis-rate-limit/README.md)
- [Maven 配置（pom.xml）](/downloads/redis-rate-limit/pom.xml)

| 文件 | 作用 |
| --- | --- |
| [ApiLimit.java](/downloads/redis-rate-limit/src/main/java/com/chendong/zfilestudy/core/annotation/ApiLimit.java) | 定义限流注解 |
| [ApiLimitAspect.java](/downloads/redis-rate-limit/src/main/java/com/chendong/zfilestudy/core/aspect/ApiLimitAspect.java) | 生成限流键、准备参数并执行脚本 |
| [LuaScript.java](/downloads/redis-rate-limit/src/main/java/com/chendong/zfilestudy/core/common/LuaScript.java) | 注册原项目的 Lua 脚本 Bean |
| [rate-limit.lua](/downloads/redis-rate-limit/rate-limit.lua) | 从原 Java 文件导出的同一份 Lua 脚本，便于独立阅读 |
| [RedisTemplateConfiguration.java](/downloads/redis-rate-limit/src/main/java/com/chendong/zfilestudy/core/config/redis/RedisTemplateConfiguration.java) | 原项目的 RedisTemplate 配置 |

源码提取自 `zfile-study`，保留原包名和实现，包含切面所需的配套工具类、常量与业务异常。迁移到其他系统时，请先阅读接入说明，确认 Spring Bean、登录态、客户端 IP 和异常处理的适配方式。此附件是原实现的源码快照，仍保留正文中说明的参数校验、窗口精度等限制。

## 1. 前言

验证码、登录、发送通知等公开接口容易被短时间的重复请求消耗资源，甚至成为攻击入口。把限流判断写进每个控制器会带来重复代码，也容易遗漏某个接口。

`@ApiLimit` 将“在哪个接口限流、在多长时间内允许多少次”声明在方法上。Spring AOP 在方法执行前读取配置，Redis 有序集合保存请求时间点，Lua 脚本把清理、计数和写入合成一次原子操作。业务方法只有在请求被允许后才会执行。

## 2. 核心功能概述

这是一种按“调用者 + 请求 URI”分桶的 Redis 滑动窗口限流：

```text
HTTP 请求
  -> Spring AOP 的 @Before 通知
  -> 生成限流键：已登录用户 ID 或未登录客户端 IP + 请求 URI
  -> 执行 Lua：清理窗口外记录 -> 计数 -> 拒绝或写入本次请求
  -> 放行时执行控制器；超限时抛出业务异常并终止调用
```

注解定义如下：

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiLimit {
    int timeout();

    TimeUnit timeUnit() default TimeUnit.SECONDS;

    long maxCount();
}
```

- `timeout`：滑动窗口长度，必填。
- `timeUnit`：窗口单位，默认秒。
- `maxCount`：一个限流键在窗口内允许通过的最大次数，必填。

例如 `@ApiLimit(timeout = 10, maxCount = 3)` 表示：对同一调用者访问同一 URI，在任意连续 10 秒内最多允许 3 次。第 4 次请求会在进入控制器前被拒绝。

该实现控制的是单个应用共享 Redis 中的访问频率，适用于多个应用实例共同限流。它不等同于网关的全局防护，也不会限制未标注注解的方法。

## 3. 环境与前置依赖

实现使用以下组件：

- Java 17：运行时注解、`Instant`、`TimeUnit` 与 `UUID`；
- Spring AOP：匹配 `@ApiLimit` 方法并在执行前拦截；
- Spring Data Redis：通过 `RedisTemplate` 执行 Lua 脚本；
- Redis：提供 ZSET、键过期与单线程 Lua 执行；
- 登录态与 Servlet 请求上下文：用于取得用户标识、客户端 IP 与请求 URI。

应用需要启用代理式 AOP，切面和 Lua 脚本都应作为 Spring Bean 注册。RedisTemplate 的常规 value 序列化在此处不能直接用于 Lua 参数：脚本接收的是数值和文本，因此执行时显式指定字符串序列化器传递 key 与参数，并以 `Long` 反序列化脚本返回值。

## 4. 详细用法教程

### 4.1 在控制器方法上声明限流规则

在需要保护的方法上添加注解即可：

```java
@GetMapping("/login/captcha")
@ApiLimit(timeout = 10, maxCount = 3)
public AjaxJson<LoginVerifyImgResult> captcha() {
    LoginVerifyImgResult result = imgVerifyCodeService.generatorCaptcha();
    return AjaxJson.getSuccessData(result);
}
```

这是示例实现中的实际用法。未指定 `timeUnit` 时采用默认的 `SECONDS`，因此规则为 10 秒内最多通过 3 次。拦截发生在方法体之前，超限请求不会执行验证码生成逻辑。

可以显式改变单位和次数：

```java
@ApiLimit(timeout = 1, timeUnit = TimeUnit.MINUTES, maxCount = 20)
public ApiResponse<?> sendCode() {
    // 仅在限流通过后执行
}
```

注解本身不指定 key。调用者身份和 URI 由切面统一推导，以避免每个控制器重复拼接字符串。

### 4.2 理解限流键的隔离范围

切面按当前登录状态生成 Redis key：

```java
String url = RequestHolder.getRequest().getRequestURI();
String loginId = StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : null;

if (StringUtils.isEmpty(loginId)) {
    String ip = IpUtils.getClientIp();
    return String.format("api:limit:%s:%s", ip, url);
}
return String.format("api:limit:%s:%s", loginId, url);
```

已登录请求的计数彼此独立，同一用户访问不同 URI 也彼此独立；未登录请求按客户端 IP 隔离。这样可避免一个已登录用户耗尽其他用户的额度，同时限制同一 IP 对公开接口的高频访问。

### 4.3 理解请求在窗口内的表示

每次允许的请求会写入一个 ZSET：

- `score` 是当前 Unix 时间戳的毫秒值；
- `member` 是随机 UUID；
- ZSET key 是上一节生成的限流键。

UUID 不是身份标识，而是保证即使多次请求发生在同一毫秒，ZSET 成员仍然不同。请求时间保存在 score 中，便于按窗口左边界删除过期记录。

### 4.4 超限后的结果

Lua 返回 `0` 表示本次请求已记入窗口并允许执行；返回 `1` 表示当前窗口内已有 `maxCount` 条允许记录，切面会抛出“请求过于频繁”的业务异常。该异常的具体 HTTP 响应形式由应用的异常处理机制决定。

Redis 脚本返回非数值或空值时，切面会记录错误日志并抛出通用业务异常，而不是继续执行受保护接口。Redis 命令执行过程中直接抛出的连接或脚本异常没有在该切面中捕获，会继续向调用链传播。

## 5. 核心知识点提炼

### 滑动窗口与固定窗口的区别

固定窗口按自然时间段计数，例如 12:00:00 至 12:00:10 最多 3 次。它可能产生边界突刺：用户在前一个窗口末尾请求 3 次，又在下一个窗口开头请求 3 次，极短时间内就通过 6 次。

本实现每次以“当前时刻减去窗口长度”为左边界，统计区间 `(now - timeout, now]` 内的记录。窗口会随请求移动，因而避免了固定窗口的边界重置问题，但需要保存窗口内每一条允许请求。

### 为什么必须使用 Lua

一次判定至少包含四步：删除过期成员、读取当前数量、判断是否超限、写入允许请求并设置过期时间。若在 Java 中用多条 Redis 命令实现，并发请求可能都在计数阶段看到较小的值，进而同时放行，突破 `maxCount`。

Redis 会串行执行同一段 Lua 脚本，因此这组命令在 Redis 端对其他命令表现为原子操作。原子性只覆盖脚本内部的 Redis 读写，不覆盖随后执行的控制器业务。

### 允许请求才消耗额度

脚本在 `count >= maxCount` 时直接返回 `1`，不会把被拒绝请求写入 ZSET。窗口中只包含已放行的请求，因此反复重试不会延长窗口，也不会增加 ZSET 成员数量。

### 过期时间的作用

每次允许请求后，脚本调用 `EXPIRE key seconds`。活跃调用者的 key 会持续存在；不再请求后，键会自动删除，避免每个历史 IP、用户和 URI 长期占用 Redis 内存。

## 6. 代码重点与难点解析

切面先把注解配置转换为窗口边界和脚本参数：

```java
int timeout = apiLimit.timeout();
TimeUnit timeUnit = apiLimit.timeUnit();
long seconds = TimeoutUtils.toSeconds(timeout, timeUnit);
long maxCount = apiLimit.maxCount();

Instant now = Instant.now();
long startTs = now.minus(timeout, toTemporalUnit(timeUnit)).toEpochMilli();
long endTs = now.toEpochMilli();
String uuid = UUID.randomUUID().toString();

Object[] args = {
    String.valueOf(startTs),
    String.valueOf(endTs),
    String.valueOf(maxCount),
    String.valueOf(seconds),
    uuid
};
```

`startTs` 与 `endTs` 使用同一个 `Instant` 计算，保证脚本中的窗口边界一致。`TimeUnit` 被转换为 `ChronoUnit`，从而既能表达秒、分钟、小时和天，也能表达毫秒、微秒与纳秒。虽然时间边界最终以毫秒写入 Redis，低于毫秒的精度无法在 ZSET score 中保留。

Lua 的核心逻辑如下，代码节选对应真实实现：

```lua
redis.call('ZREMRANGEBYSCORE', key, 0, startTs)

local count = redis.call('ZCARD', key)
if count >= maxCount then
    return 1
end

redis.call('ZADD', key, endTs, uuid)
redis.call('EXPIRE', key, seconds)
return 0
```

`ZREMRANGEBYSCORE key 0 startTs` 删除 score 小于或等于窗口左边界的成员，因此记录恰好发生在 `now - timeout` 的请求不再计入当前窗口。随后 `ZCARD` 得到窗口内已放行请求数，只有未达到上限时才新增当前 UUID。

切面显式控制 Redis 序列化：

```java
Object raw = redisTemplate.execute(
        redisScript,
        RedisSerializer.string(),
        new GenericToStringSerializer<>(Long.class),
        Collections.singletonList(key),
        args
);

if (!(raw instanceof Number number)) {
    throw new BizException(ErrorCode.BIZ_ADMIN_ERROR);
}
if (number.longValue() == 1) {
    throw new BizException(ErrorCode.BIZ_ACCESS_TOO_FREQUENT);
}
```

应用的 RedisTemplate 默认 value 使用 JSON 序列化，而 Lua 参数必须是脚本可解析的普通字符串。这里为本次脚本调用显式传入字符串 serializer，避免 `tonumber(ARGV[n])` 因 JSON 编码而失败；返回值采用数值 serializer，再兼容性地按 `Number` 读取。

## 7. 踩坑总结与注意事项

1. **`timeout` 与 `maxCount` 没有参数校验。** 当前注解允许 0 或负数。`maxCount <= 0` 会导致所有请求直接被拒绝；不合法时间也可能让 `Instant.minus` 或 Redis 过期设置失败。建议在切面中校验两者均大于 0，并在启动期或首次调用时明确报错。
2. **小于一秒的窗口无法正确设置 TTL。** 脚本用 `EXPIRE`，其参数为整秒；当 `TimeoutUtils.toSeconds` 得到 0 时，键可能立刻过期。若要支持毫秒窗口，应传递毫秒并使用 `PEXPIRE`；否则限制 `timeUnit` 为秒及更大的单位。
3. **超长窗口会延长键的存活与状态保留。** `timeout` 为 `int`，配合 `DAYS` 可以表达很长的窗口；允许请求会持续刷新 key 的过期时间。应依据业务设置合理上限，避免不必要的历史记录保留和 Redis 内存占用。
4. **未登录用户按 IP 共用额度。** 位于同一 NAT、公司出口或代理后的多个正常用户可能共享一个额度；反向代理传递的客户端 IP 必须由受信任链路处理，不能无条件信任客户端可伪造的转发头。
5. **请求 URI 不含 HTTP 方法与查询参数。** 同一路径的 GET 与 POST 会共用额度，查询参数不同也不会分桶。这是当前 key 的既定语义；若需要区分，应把 HTTP method 或经过规范化、白名单化的业务维度加入 key。
6. **每个允许请求都会占一个 ZSET member。** 高并发、大窗口或较大的 `maxCount` 会增加内存与 `ZREMRANGEBYSCORE` 的处理成本。对只需要粗粒度限制的场景，固定窗口计数器或令牌桶通常更节省资源。
7. **Redis 执行异常会中断受保护接口。** 切面对“返回值非数值”转换为业务异常；Redis 连接、超时或 Lua 运行异常则继续向外传播。两种情况都不会自动放行，因此需要监控 Redis 可用性，并按接口风险明确是否需要在上层做降级。
8. **AOP 只拦截经过 Spring 代理的调用。** 直接对象自调用会绕过切面；控制器经 Spring MVC 调用会正常进入切面。若在服务内部使用该注解，应避免 `this.method()` 这种自调用方式。
9. **现有测试未覆盖限流逻辑。** 应补充 Redis 集成测试，验证第 `maxCount + 1` 次请求被拒绝、窗口到期后恢复、不同用户/IP/URI 相互隔离、并发下总放行数不超过上限，以及脚本异常的返回策略。

## 8. 关键代码解析

切面在带注解的方法执行前完成限流判断：

```java
@Aspect
@Component
public class ApiLimitAspect {

    @Before("@annotation(apiLimit)")
    public void before(JoinPoint joinPoint, ApiLimit apiLimit) {
        // 读取 timeout、timeUnit 与 maxCount
        // 生成 key、时间边界和 UUID
        // 原子执行 Lua 脚本
        // 返回 1 时抛出访问频繁异常
    }
}
```

使用 `@Before` 意味着切面只负责准入判断，放行后不再包裹业务方法，也不会依据业务方法的成功或异常回滚已经消耗的额度。这符合访问频率限制的常见语义：请求获得执行机会即计入额度，而不是只统计最终成功的业务结果。

脚本 Bean 由配置类创建，并以 `RedisScript<Long>` 注入切面：

```java
@Bean
public RedisScript<Long> apiLimitScript() {
    String lua = "..."; // ZSET 滑动窗口逻辑
    return RedisScript.of(lua, Long.class);
}
```

Spring Data Redis 会根据脚本文本管理执行，并把脚本返回值转换为 `Long`。将脚本单独定义为 Bean，能使切面专注于请求上下文和参数准备，算法细节则集中在 Redis 端。

需要注意，切面中声明的命名 `@Pointcut` 方法与 `@Before` 都使用相同的注解表达式，但通知目前直接写了表达式，未复用命名切点。两种写法行为一致；若后续增加多个相关通知，可改为 `@Before("apiLimit() && @annotation(apiLimit)")`，避免切点表达式分散。

## 9. 总结

`@ApiLimit` 通过声明式注解把接口规则与业务逻辑分离：AOP 在方法前进行准入，Redis ZSET 保存最近允许请求，Lua 保证并发下的清理、计数与写入原子执行。登录态按用户隔离，匿名请求按 IP 隔离，键通过过期时间自动回收。

该实现适合对少量敏感接口施加精确的滑动窗口限制。使用前应明确它的 key 维度、失败策略和窗口精度；演进时优先补充参数校验、毫秒 TTL 支持与 Redis 集成并发测试，再根据流量规模选择固定窗口、令牌桶或网关级限流方案。
