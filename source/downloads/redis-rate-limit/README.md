# Redis ZSET + Lua 滑动窗口限流源码

本文附件提取自 `zfile-study`，对应文章《使用 Spring AOP、Redis ZSET 与 Lua 实现接口滑动窗口限流》。

- 提取日期：2026-10-26。
- 来源提交：`e436162cfbb4d31879cb7399509313f61d244b38`，提取文件与该提交中的实现一致。
- `src/main/java/` 下的 14 个 Java 文件逐字节复制自原项目，保留包名、注释和实现。
- `rate-limit.lua` 从 `LuaScript.java` 中拼接的脚本文本导出。原实现仍由 `LuaScript` 注册脚本 Bean，不会自动加载这个独立文件。
- `pom.xml` 是为附件整理的最小编译依赖清单，不是原项目的完整 POM。

## 文件说明

Java 文件位于 `src/main/java/com/chendong/zfilestudy/core/`：

| 相对路径 | 用途 |
| --- | --- |
| `annotation/ApiLimit.java` | 方法上的限流规则 |
| `aspect/ApiLimitAspect.java` | AOP 准入判断、Redis key 和序列化处理 |
| `common/LuaScript.java` | Lua 脚本 Bean |
| `config/redis/RedisTemplateConfiguration.java` | 原项目 RedisTemplate 配置 |
| `constant/RedisKeyConstant.java` | 限流 key 前缀 |
| `constant/ZFileHttpHeaderConstant.java` | RequestHolder 使用的请求头常量 |
| `exception/ErrorCode.java` | 业务错误码 |
| `exception/core/BizException.java` | 业务异常 |
| `util/RequestHolder.java` | 获取 Servlet 请求上下文 |
| `util/IpUtils.java` | 获取客户端 IP |
| `util/stringUtil/StringUtils.java` | 原项目字符串工具 |
| `util/stringUtil/StrPool.java` | 字符串常量 |
| `util/charUtil/CharSequenceUtil.java` | 字符串判断及其原实现 |
| `util/charUtil/CharPool.java` | 字符常量 |

工具类包含其原有方法，以保留原始源码与编译依赖关系，不代表限流逻辑需要这些工具类的所有功能。

## 编译与使用

基线为 Java 17、Spring Boot 3.3.2、Sa-Token 1.38.0、Hutool 5.8.31；其余版本由 Spring Boot parent 管理。附件包含 Spring Web、AOP、Spring Data Redis、Commons Lang 和 Lombok 所需依赖。它是可编译的库源码，没有启动类，也不包含 Redis 服务、项目配置或真实账号信息。

解压 ZIP，进入包含 `pom.xml` 的目录，可执行：

```bash
mvn package
```

要在本机其他 Maven 项目中使用，可执行 `mvn install`，再添加依赖：

```xml
<dependency>
    <groupId>com.chendong</groupId>
    <artifactId>redis-rate-limit</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

`mvn install` 只安装到本机 Maven 仓库，不会发布到公共仓库。团队或 CI 环境需要自己构建、安装附件，或发布到团队构件仓库。

也可以复制需要的 Java 文件并合并依赖。两种接入方式选一种，避免同一个类同时来自源码和 JAR。复制到其他包时，需要同步调整 package、import 和 `ApiLimitAspect` 中的完整注解切点表达式。

## Spring 接入

1. 确认 AOP 自动配置生效。使用 `spring-boot-starter-aop` 的常规 Spring Boot 应用会自动配置代理。
2. 让 Spring 扫描 `com.chendong.zfilestudy.core`，或在自己的配置类上显式导入：

   ```java
   @Import({ApiLimitAspect.class, LuaScript.class})
   ```

   上面的类分别位于 `com.chendong.zfilestudy.core.aspect` 和 `com.chendong.zfilestudy.core.common`，`Import` 来自 `org.springframework.context.annotation.Import`。

3. 提供名称为 `redisTemplate` 的 `RedisTemplate<String, Object>` Bean。附件保留了原项目的 `RedisTemplateConfiguration`；如果你的项目已有 RedisTemplate，请优先复用并核对兼容性，避免重复导入配置。需要原配置时，可额外导入该类，并根据自己的环境配置 `spring.data.redis` 连接参数。
4. 保证 `apiLimitScript` 能被切面正确注入。原字段使用 `@Resource`，字段名是 `redisScript`；应用中存在多个 `RedisScript<Long>` Bean 时，应明确指定注入 `apiLimitScript`，避免类型歧义。
5. 在通过 Spring 代理调用的方法上添加注解。例如在现有 Spring MVC 控制器方法上添加：

   ```java
   @ApiLimit(timeout = 10, maxCount = 3)
   ```

6. 按下面的说明接入登录态和异常响应，再进行 Redis 集成验证。

## 迁移时必须核对的项目依赖

- **登录态**：切面通过 `StpUtil` 读取 Sa-Token 用户 ID。已有其他认证框架时，需要替换这部分身份获取逻辑；集群中还需要保证各实例的登录态配置一致。
- **请求上下文与 IP**：实现依赖 Spring MVC/Servlet，不适用于直接迁移到 WebFlux。原 `IpUtils` 使用 Hutool 读取客户端 IP；反向代理环境必须结合可信代理配置处理转发头，不能无条件相信客户端传入的 IP。
- **异常响应**：原切面抛出 `BizException`。附件没有复制原项目的全局异常处理器；请在现有异常处理中映射该异常，或替换为自己的异常类型。否则限流拒绝可能表现为普通服务器错误，而非期望的业务响应。
- **Redis 序列化**：原切面显式使用字符串参数序列化器调用 Lua，请保留这一行为。附件中的 RedisTemplate JSON 配置保留了原项目的类型信息策略，复用到其他系统前应检查其是否符合该系统的序列化要求。
- **原包名**：如果目标项目已有这些类，请复用或合并，避免把整个附件重复复制进去。

## 原实现保留的限制

提取没有重写限流算法，也没有补齐文章中提到的限制：

- `timeout` 和 `maxCount` 没有正数校验，使用前必须保证规则合法。
- TTL 使用 `EXPIRE` 的整秒精度，不应直接配置小于一秒的窗口。
- 请求时间来自应用实例，多个实例需要时钟同步。
- 已登录用户 ID 与匿名 IP 使用同一个 key 命名结构；URI 不区分 HTTP 方法和查询参数。
- 只拦截经过 Spring AOP 代理的调用，对象自调用会绕过切面。
- Redis 不可用时不会自动放行；已经放行的请求不会因业务执行失败而退还额度。

编译通过不等于已验证目标系统的运行行为。接入后应验证超限拒绝、窗口到期恢复、调用者隔离、并发额度和 Redis 故障策略。
