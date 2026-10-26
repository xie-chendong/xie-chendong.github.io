# Spring AOP + MyBatis 方法级性能追踪源码

对应文章《使用 Spring AOP 与 MyBatis 拦截器实现方法级性能追踪》。

- 来源项目：`zfile-study`。
- 提取日期：2026-10-26；文章日期按作者要求设为 2026-08-22。
- 来源提交：`e436162cfbb4d31879cb7399509313f61d244b38`，提取的文件与该提交中的实现一致。
- `src/main/java/` 下 5 个 Java 文件均逐字节复制自原项目，保留包名、注释与逻辑。
- `pom.xml` 为附件整理的最小依赖清单，并非原项目完整 POM。

## 文件说明

所有 Java 文件位于 `src/main/java/com/chendong/zfilestudy/core/`：

| 相对路径 | 用途 |
| --- | --- |
| `annotation/PerfTrack.java` | 方法上的性能追踪注解 |
| `aspect/PerfTrackAspect.java` | 创建上下文、执行原方法并在 finally 中结束追踪 |
| `entity/PerfContext.java` | 方法名、配置、SQL 耗时与次数 |
| `util/PerfTracker.java` | ThreadLocal 上下文及汇总、明细日志 |
| `config/mybatis/SqlPerfInterceptor.java` | 拦截 Executor.query/update 并记录 SQL 模板与耗时 |

## 编译与复用

版本基线来自原项目：Java 17、Spring Boot 3.3.2、MyBatis Spring Boot Starter 3.0.5；Spring AOP、日志及 Lombok 版本由 Spring Boot parent 管理。附件是库源码，没有启动类、数据库驱动、连接配置或业务 Mapper。

解压 ZIP，进入包含 `pom.xml` 的目录：

```bash
mvn package
```

需要在本机其他 Maven 项目中引用时，可执行 `mvn install`，再添加：

```xml
<dependency>
    <groupId>com.chendong</groupId>
    <artifactId>mybatis-perf-track</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

这只安装到本机 Maven 仓库，不会发布到公共仓库；团队或 CI 环境需要构建附件或使用自己的构件仓库。也可以复制这 5 个 Java 文件并合并依赖。源码和 JAR 两种方式选一种，避免重复类。

## 接入现有 Spring Boot 项目

1. 确认项目使用 Spring AOP 和 MyBatis，已配置可用的数据源及业务 Mapper。已有 MyBatis-Plus 项目应沿用自己的 starter，不要同时引入两个 MyBatis starter。
2. 将 `PerfTrackAspect` 和 `SqlPerfInterceptor` 纳入 Spring 扫描范围，或在自己的配置类上使用 `@Import({PerfTrackAspect.class, SqlPerfInterceptor.class})`。两个类分别位于 `com.chendong.zfilestudy.core.aspect` 和 `com.chendong.zfilestudy.core.config.mybatis`，`Import` 来自 `org.springframework.context.annotation.Import`。
3. 常规 Spring Boot 应用在引入 `spring-boot-starter-aop` 后会自动配置 AOP；原项目额外使用 `@EnableAspectJAutoProxy(exposeProxy = true)`。普通跨 Bean 调用无需依赖 `exposeProxy`，但对象内部直接自调用不会经过代理。
4. 确认 `SqlPerfInterceptor` 注册到了实际使用的 `SqlSessionFactory`。标准 MyBatis Spring Boot 自动配置会收集 `Interceptor` Bean；手动创建工厂或使用多个数据源时，需要把它合并进相应工厂的插件列表。
5. 在现有服务方法上添加注解，通过 Spring 代理调用该方法：

   ```java
   @PerfTrack(sqlDetail = true, slowSqlThreshold = 800)
   ```

   `PerfTrack` 位于 `com.chendong.zfilestudy.core.annotation`。默认 `sqlDetail=false`，阈值为 `500` 毫秒。

6. 查看日志中的 `methodCost`、`sqlTotalCost`、`sqlCount`；开启明细后还会输出每条已记录 SQL 的模板与耗时。请先在目标系统中验证一次正常查询、异常查询以及无 SQL 的方法。

## 保留的行为与限制

提取未改写原算法，接入时请明确以下边界：

- `slowSqlThreshold` 比较的是一次方法调用的 **SQL 总耗时**，不是单条 SQL 的慢查询阈值。
- ThreadLocal 仅覆盖当前线程；异步任务不会自动继承上下文。嵌套代理调用带注解的方法会覆盖外层上下文，内层结束后不会恢复外层统计。
- 关闭明细只控制日志输出，仍会保存每条 SQL 记录；SQL 数量较大时要关注额外的内存开销。
- 插件只拦截声明的四参数 `Executor.query` 与 `Executor.update` 入口，结果受 MyBatis 缓存、批处理及插件顺序影响，不能直接等同于 JDBC 或数据库服务器的实际执行耗时。
- 耗时使用 `System.currentTimeMillis()` 计算，会受系统时钟调整影响。
- SQL 通常保留 `?` 占位符，但动态文本替换可能包含业务数据；开启明细前需要核对日志内容。
- 原方法异常时也会进入 finally；若 SQL 文本提取或追踪日志本身异常，可能干扰原异常或上下文清理。附件没有额外修补这些行为。

附件不包含原业务配置服务、数据库或运行环境。编译通过只确认源码和依赖可构建，不能替代目标系统中的集成验证。
