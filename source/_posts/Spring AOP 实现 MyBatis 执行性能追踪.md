---
title: "使用 Spring AOP 与 MyBatis 拦截器实现方法级性能追踪"
description: "通过 @PerfTrack 注解与 Spring AOP 建立方法调用的追踪上下文，结合 MyBatis 拦截器统计方法总耗时、SQL 累计耗时与执行次数，介绍实现原理、日志分析及嵌套和异步场景的边界。"
date: 2026-08-22
tags: [Spring, MyBatis]
categories: [JAVA后端]
---

# 使用 Spring AOP 与 MyBatis 拦截器实现方法级性能追踪

## 配套源码

- [下载配套源码包（ZIP）](/downloads/mybatis-perf-track/mybatis-perf-track.zip)
- [接入说明与依赖清单（README）](/downloads/mybatis-perf-track/README.md)
- [Maven 配置（pom.xml）](/downloads/mybatis-perf-track/pom.xml)

| 文件 | 作用 |
| --- | --- |
| [PerfTrack.java](/downloads/mybatis-perf-track/src/main/java/com/chendong/zfilestudy/core/annotation/PerfTrack.java) | 声明 SQL 明细开关与耗时阈值 |
| [PerfTrackAspect.java](/downloads/mybatis-perf-track/src/main/java/com/chendong/zfilestudy/core/aspect/PerfTrackAspect.java) | 管理一次方法追踪的开始与结束 |
| [PerfContext.java](/downloads/mybatis-perf-track/src/main/java/com/chendong/zfilestudy/core/entity/PerfContext.java) | 保存方法信息、SQL 次数和耗时记录 |
| [PerfTracker.java](/downloads/mybatis-perf-track/src/main/java/com/chendong/zfilestudy/core/util/PerfTracker.java) | 管理线程上下文并输出追踪日志 |
| [SqlPerfInterceptor.java](/downloads/mybatis-perf-track/src/main/java/com/chendong/zfilestudy/core/config/mybatis/SqlPerfInterceptor.java) | 拦截 MyBatis 查询与更新并累计耗时 |

源码提取自 `zfile-study`，保留原包名和实现。附件包含 5 个原始 Java 文件及最小 Maven 编译配置；迁移前请阅读接入说明，核对 Spring 扫描范围、MyBatis 插件注册方式，以及正文中说明的嵌套追踪和异步边界。

## 1. 前言

排查接口或服务变慢时，仅知道“这个方法慢”通常不够：还需要判断时间花在业务计算、数据库访问，还是调用链的其他部分。把计时与 SQL 统计散落在每个业务方法中，会污染业务代码，也容易漏掉异常分支的清理。

`@PerfTrack` 将性能追踪声明在方法上，由 Spring AOP 创建一次调用范围内的上下文，再由 MyBatis 拦截器累积 SQL 耗时。业务方法只需标记注解，即可在结束时得到方法总耗时、SQL 总耗时、SQL 次数和可选的 SQL 明细。

## 2. 核心功能概述

整个处理链如下：

```text
调用代理方法
  -> PerfTrackAspect 创建 ThreadLocal 上下文并开始方法计时
  -> 业务方法执行
       -> MyBatis Executor.query / update
       -> SqlPerfInterceptor 计时并写入当前上下文
  -> PerfTrackAspect 在 finally 中输出汇总、可选明细，并清理上下文
```

注解提供两个配置项：

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PerfTrack {
    boolean sqlDetail() default false;
    long slowSqlThreshold() default 500;
}
```

- `sqlDetail`：是否在结束时逐条打印已记录的 SQL 文本和耗时，默认关闭。
- `slowSqlThreshold`：单位为毫秒，默认 `500`。在这套实现中，它判断的是**本次方法调用内 SQL 总耗时**是否达到阈值，而不是单条 SQL 是否慢。

因此，它适合关注单个服务方法及其数据库访问总量的开发期诊断和运行日志观测。它不是全链路追踪系统：不会自动统计 HTTP、远程调用、缓存、文件 I/O 或其他线程中的工作。

## 3. 环境与前置依赖

实现涉及以下基础能力：

- Java 17 的运行时注解与 `ThreadLocal`；
- Spring AOP，且应用需启用 `@EnableAspectJAutoProxy`；
- `spring-boot-starter-aop`，用于提供 AOP 运行环境；
- MyBatis 的插件 SPI：`Interceptor`、`@Intercepts` 和 `@Signature`；
- SLF4J，用于输出汇总日志与 SQL 明细。

切面类和 MyBatis 插件都必须被 Spring 管理。示例中分别以 `@Component` 注册，应用启动类开启 `@EnableAspectJAutoProxy(exposeProxy = true)`，使带注解的外部代理调用能够进入 `@Around` 通知。

## 4. 详细用法教程

### 4.1 在需要观测的方法上标记注解

默认只输出汇总信息：

```java
@PerfTrack
public void updateSettings(SettingsCommand command) {
    // 查询、更新等业务逻辑
}
```

需要排查多条 SQL 的耗时时，临时开启明细：

```java
@PerfTrack(sqlDetail = true, slowSqlThreshold = 800)
public SettingsView getSettings() {
    return settingsMapper.selectSettings();
}
```

实际示例中，一个读取配置的方法使用 `@PerfTrack(sqlDetail = true)`，一个更新配置的方法使用默认配置。前者便于查看查询 SQL，后者仅输出方法与 SQL 的汇总数据。

### 4.2 切面负责建立和结束一次追踪

切点 `@annotation(perfTrack)` 匹配所有带该注解的方法，并把实际注解实例绑定为参数：

```java
@Aspect
@Component
public class PerfTrackAspect {

    @Around("@annotation(perfTrack)")
    public Object around(ProceedingJoinPoint joinPoint, PerfTrack perfTrack)
            throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        PerfTracker.start(methodName, perfTrack);
        long start = System.currentTimeMillis();
        try {
            return joinPoint.proceed();
        } finally {
            PerfTracker.end(System.currentTimeMillis() - start);
        }
    }
}
```

`joinPoint.proceed()` 代表原方法调用。使用 `finally` 至关重要：无论原方法正常返回还是抛出异常，都会输出已采集的耗时并移除线程上下文，避免线程池复用线程时遗留旧数据。

### 4.3 MyBatis 拦截器记录 SQL

插件只在追踪上下文存在时付出计时和记录成本：

```java
@Intercepts({
    @Signature(type = Executor.class, method = "query",
               args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(type = Executor.class, method = "update",
               args = {MappedStatement.class, Object.class})
})
@Component
public class SqlPerfInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        PerfContext context = PerfTracker.current();
        if (context == null) {
            return invocation.proceed();
        }

        long start = System.currentTimeMillis();
        try {
            return invocation.proceed();
        } finally {
            long cost = System.currentTimeMillis() - start;
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            Object parameter = invocation.getArgs().length > 1
                    ? invocation.getArgs()[1] : null;
            String sql = statement.getBoundSql(parameter).getSql();
            PerfTracker.recordSql(sql, cost);
        }
    }
}
```

`finally` 同样使失败的 SQL 也能被计时和记录。`BoundSql#getSql()` 得到的是 MyBatis 生成的 SQL 模板，通常仍保留 `?` 占位符，并非包含参数值的最终拼接 SQL。

### 4.4 理解日志结果

结束时会输出以下聚合值：

```text
method=updateSettings
methodCost=42ms
sqlTotalCost=31ms
sqlCount=2
```

`methodCost` 覆盖切面从 `proceed()` 开始到结束的整个同步方法执行时间；`sqlTotalCost` 是被 MyBatis 拦截器记录到的 SQL 耗时之和；二者差值可以粗略反映未被该插件覆盖的同步工作耗时，但不应直接等同于“纯业务耗时”。例如事务提交、缓存、日志、代理开销或 JDBC 驱动之外的等待都会影响解释。

## 5. 核心知识点提炼

### 运行时保留与方法目标

`@Retention(RetentionPolicy.RUNTIME)` 让 Spring AOP 能在运行期发现注解；`@Target(ElementType.METHOD)` 限定它只能标记方法。这两个元注解共同定义了切点表达式可以匹配的范围。

### `ThreadLocal` 是上下文桥梁

切面与 MyBatis 插件不直接依赖彼此，而是通过当前线程的 `PerfContext` 交换数据。每个线程有独立副本，因此并发请求之间不会混写计数；`end` 中的 `remove()` 则防止复用线程携带上一次请求的状态。

### AOP 代理决定注解是否生效

Spring 的代理式 AOP 只会拦截经过 Spring 代理的调用。一个对象在自身方法中直接调用另一个带 `@PerfTrack` 的方法，会绕过代理，切面不会执行。示例中的 `exposeProxy = true` 允许需要时经 `AopContext.currentProxy()` 回到代理对象，但这应谨慎使用，优先将被追踪的独立职责拆到另一个 Bean。

### MyBatis 拦截点决定统计范围

示例拦截 `Executor.query` 和 `Executor.update`，所以只会统计落到这两个 MyBatis 执行入口的数据库操作。非 MyBatis 数据访问、数据库连接等待、结果在业务层的二次处理，以及异步线程中的 SQL 不会自动进入同一份统计。

## 6. 代码重点与难点解析

性能上下文保存了配置快照与累计指标：

```java
public class PerfContext {
    private String methodName;
    private final boolean isSqlDetail;
    private final long slowSqlThreshold;
    private long sqlTotalCost;
    private int sqlCount;
    private List<SqlRecord> sqlRecords = new ArrayList<>();

    public record SqlRecord(String sql, long cost) {}

    public PerfContext(String methodName, PerfTrack perfTrack) {
        this.methodName = methodName;
        this.isSqlDetail = perfTrack.sqlDetail();
        this.slowSqlThreshold = perfTrack.slowSqlThreshold();
    }

    public void addSqlRecord(String sql, long cost) {
        sqlCount++;
        sqlTotalCost += cost;
        sqlRecords.add(new SqlRecord(sql, cost));
    }
}
```

配置在进入方法时被复制到上下文，后续 SQL 记录只更新该次调用的计数和累计值。`SqlRecord` 使用 Java `record` 表示不可变的单条记录，避免记录建立后被意外改写。

结束逻辑是汇总语义的关键：

```java
public static void end(long methodCost) {
    PerfContext context = CONTEXT.get();
    if (context == null) {
        CONTEXT.remove();
        return;
    }

    boolean isSlow = context.getSlowSqlThreshold() <= context.getSqlTotalCost();
    if (isSlow) {
        log.warn("method={} methodCost={}ms sqlTotalCost={}ms sqlCount={} threshold={}",
                context.getMethodName(), methodCost, context.getSqlTotalCost(),
                context.getSqlCount(), context.getSlowSqlThreshold());
    } else {
        log.info("method={} methodCost={}ms sqlTotalCost={}ms sqlCount={}",
                context.getMethodName(), methodCost, context.getSqlTotalCost(),
                context.getSqlCount());
    }
    // sqlDetail 为 true 时逐条输出 sqlRecords
    CONTEXT.remove();
}
```

这里的慢判断使用 `sqlTotalCost`。因此，多条各自低于阈值的 SQL 也可能因累计值达到阈值而触发告警，日志不能指出究竟是哪一条 SQL 慢。若目标是识别“单条慢 SQL”，应在 `recordSql` 中按 `cost >= threshold` 单独标记或输出，而不是只在 `end` 中比较累计值。

## 7. 踩坑总结与注意事项

1. **注解放在自调用方法上不生效。** 代理不会拦截 `this.someMethod()`；改为经另一个 Bean 调用，或显式通过代理调用。
2. **嵌套追踪会覆盖上下文。** 当前 `start` 直接 `set`，`end` 直接 `remove`。若外层追踪方法通过代理调用内层追踪方法，内层会替换外层上下文，结束后清空它，外层将丢失统计与日志。需要嵌套支持时，应使用 `ThreadLocal<Deque<PerfContext>>` 或保存并恢复父上下文。
3. **异步任务不会继承上下文。** 普通 `ThreadLocal` 只在当前线程有效；`@Async`、线程池和响应式链路需要显式上下文传递或采用适配的追踪方案。
4. **阈值名称与当前判断粒度不同。** `slowSqlThreshold` 的注释容易让人理解为单条 SQL 阈值，但源码比较的是 SQL 总耗时。应改名为 `slowSqlTotalThreshold`，或调整判断位置。
5. **关闭明细不等于不保存明细。** 当前 `addSqlRecord` 无条件把每条 SQL 放入列表，`sqlDetail` 只控制最终是否打印。高 SQL 数量的方法仍会分配记录对象；可在 `sqlDetail` 为 `false` 时仅累加次数和耗时。
6. **SQL 文本与敏感信息。** 现有 `BoundSql#getSql()` 通常不含 `?` 对应的参数值，但动态文本替换仍可能把数据带进 SQL。生产环境应限制明细日志的开启范围、脱敏并设置日志访问权限。
7. **计时 API 的选择。** `currentTimeMillis()` 适合日志中的毫秒显示，但会受系统时钟调整影响。只做耗时测量时，`System.nanoTime()` 更适合作为起止计时来源。
8. **方法名可能不够唯一。** 当前日志只保存简单方法名，重载方法或不同类中的同名方法难以区分。可改为 `joinPoint.getSignature().toShortString()` 或加入目标类名。

现有测试只验证应用上下文可启动，没有覆盖该追踪器。至少应补充：正常查询、异常 SQL、无 SQL 方法、`sqlDetail` 开关、阈值边界、嵌套注解和线程池场景。

## 8. 实际实现解析

以下是一次带明细的读取场景，注解贴在服务方法上，方法内部通过 Mapper 发起查询。为聚焦追踪链路，字段映射循环已省略：

```java
@Cacheable(key = "'dto'")
@PerfTrack(sqlDetail = true)
public SystemConfigDTO getSystemConfig() {
    SystemConfigDTO systemConfigDTO = new SystemConfigDTO();
    LambdaQueryWrapper<SystemConfig> wrapper = new LambdaQueryWrapper<>();
    wrapper.eq(SystemConfig::getDelFlag, "0");
    List<SystemConfig> systemConfigList = systemConfigMapper.selectList(wrapper);
    // 遍历 systemConfigList 并填充 systemConfigDTO
    return systemConfigDTO;
}
```

调用经 Spring 代理进入切面时会创建上下文。若实际执行到了 Mapper 查询，MyBatis 插件将记录一次 `query`；如果数据来自缓存，是否仍会进入性能切面取决于缓存与切面的顾问排序，不能仅凭注解顺序推断。即便进入切面，缓存命中也可能表现为 `sqlCount=0`。

更新场景使用默认配置。为聚焦注解与 SQL 执行入口，构建更新列表和结果校验已省略：

```java
@PerfTrack
@CacheEvict(allEntries = true)
@Transactional(rollbackFor = Exception.class)
public synchronized void updateSystemConfig(SystemConfigDTO systemConfigDTO) {
    List<SystemConfig> updateSystemConfigList = new ArrayList<>();
    // 根据 systemConfigDTO 生成 updateSystemConfigList
    MybatisBatch<SystemConfig> batch = new MybatisBatch<>(
            sqlSessionFactory, updateSystemConfigList);
    batch.execute(new MybatisBatch.Method<>(SystemConfigMapper.class).updateById());
}
```

`synchronized`、事务和缓存等其他拦截器也可能围绕该方法执行。该追踪器只承诺记录切面内部可见的同步方法耗时，以及当前线程中被 MyBatis 插件拦截到的 SQL；不应把日志中的时间视为所有基础设施成本的精确拆分。

## 9. 总结

`@PerfTrack` 将“方法耗时 + SQL 聚合”收敛为一个声明式能力：注解承载策略，AOP 管理生命周期，`ThreadLocal` 传递上下文，MyBatis 插件记录 SQL，最终统一输出日志。其关键正确性依赖于两个 `finally` 块和每次调用后的 `ThreadLocal.remove()`。

这套实现适用于快速定位同步服务方法中的数据库访问成本。投入生产性观测前，应优先明确阈值的统计单位，处理嵌套上下文与异步边界，并降低关闭 SQL 明细时不必要的记录分配；需要跨服务、跨线程关联分析时，则应接入专门的可观测性体系。
