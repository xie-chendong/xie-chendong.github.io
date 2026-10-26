# Spring Boot 全局异常处理原项目源码快照

对应文章《Spring Boot 全局异常处理：把错误响应、HTTP 语义与可观测性收敛到一处》。

- 来源项目：`zfile-study`，来源提交 `e436162cfbb4d31879cb7399509313f61d244b38`。
- 提取日期：2026-10-27；文章日期按作者要求设为 2026-08-25，不代表代码快照的提交日期。
- `src/main/java/` 下 15 个 Java 文件逐字节复制自原项目，包名、注释和行为均保留。

## 文件说明

文件均位于 `src/main/java/com/chendong/zfilestudy/core/`：

| 相对路径 | 作用 |
| --- | --- |
| `exception/GlobalExceptionHandler.java` | 全局异常映射及 API/页面分流 |
| `exception/ErrorCode.java` | 业务错误码 |
| `exception/core/BizException.java` | 通用业务异常 |
| `exception/status/BadRequestAccessException.java` | 请求错误 |
| `exception/status/UnauthorizedAccessException.java` | 未授权访问 |
| `exception/status/ForbiddenAccessException.java` | 禁止访问 |
| `exception/biz/FilePathSecurityBizException.java` | 文件路径校验失败 |
| `exception/biz/InitializeStorageSourceBizException.java` | 存储源初始化失败 |
| `util/AjaxJson.java` | 统一响应对象 |
| `util/RequestHolder.java` | Servlet 请求和响应上下文 |
| `util/stringUtil/StringUtils.java` | URL 拼接与字符串工具 |
| `util/stringUtil/StrPool.java` | 字符串常量 |
| `util/charUtil/CharSequenceUtil.java` | 字符串判断工具 |
| `util/charUtil/CharPool.java` | 字符常量 |
| `constant/ZFileHttpHeaderConstant.java` | 请求分类使用的请求头常量 |

## 接入前必须适配的依赖

**此附件不是独立 Maven 工程，也不是开箱即用的 starter。** 全局处理器直接注入原项目的业务配置服务 `com.chendong.zfilestudy.module.config.service.SystemConfigService`。该服务还连接业务 Mapper、DTO、缓存等，因此没有把整套配置模块和数据库配置混入附件，也没有伪造一个同名实现。

迁移时应将该依赖替换为目标系统的错误页配置，或有意调整为纯 JSON 响应。处理器实际调用以下返回字符串的方法：

- `getUnauthorizedUrl()`：未授权页面地址。
- `getForbiddenUrl()`：无权限页面地址。
- `getForbiddenUrl(String code, String message)`：带错误信息的无权限地址。
- `getRealFrontDomain()`：构造登录跳转使用的前端域名。

还需要合并以下原项目依赖版本：Java 17、Spring Boot 3.3.2 的 Web 与 Validation starter、Sa-Token Spring Boot 3 starter 1.38.0、Lombok、Hutool 5.8.31 和 Commons Lang 3。Spring Boot parent 管理的依赖可沿用目标项目兼容版本；附件没有包含数据库连接、账号、令牌或应用配置。

处理器需要由 Spring 扫描。它使用 `@RestControllerAdvice`；原 `RequestHolder.isAxiosRequest()` 通过 `Axios-Request` 请求头区分 API 请求，部分权限异常还对 `/pd/` 路径直接返回 JSON。目标前端必须遵守该约定，或按自己的请求分类规则修改；该请求头不承担身份认证。

## 真实源码与文章教学示例的区别

- 统一响应类是 `AjaxJson`，字段为 `code`、`msg`、`data`、`dataCount`、`traceId`；文章中的 `ApiResponse` 是教学命名。
- 当前快照有业务异常、400/401/403 状态异常和 Sa-Token 异常处理，没有 `SystemException`、404/405 专用异常和通用 `Exception.class` 自定义兜底。
- 参数校验只处理 `MethodArgumentNotValidException`，提取第一条字段错误消息；没有文章中扩展的字段错误 Map 或 `BindException` 处理。
- `AjaxJson` 有 `traceId` 字段，但没有自动填充的 `ResponseBodyAdvice` 或 MDC 入口过滤器。
- 没有 SQLite 根因扫描、客户端中断分类、上游 HTTP 请求异常等扩展处理器。
- 文件路径异常日志记录路径，存储初始化异常记录存储 ID 和异常；其他诊断类型属于文章扩展讨论。

## HTTP 与安全边界

- 无参数的 `@ResponseStatus` 默认声明 500；不能假定所有业务异常都会返回 400。
- `sendRedirect` 会写入重定向响应；它与 JSON 分支的 401/403 状态不能混为一谈。
- 原处理器在部分分支回传异常消息或拼接到跳转参数。迁移时要核对消息、路径、跳转目标和日志中的信息边界。
- 现有异常处理器可能与目标项目的 advice 冲突；应按异常类型和 advice 优先级整合，而不是重复注册。

本附件核对原始文件与依赖边界，不承诺能脱离原项目独立编译或启动。接入后需要实际验证校验失败、未登录、无权限、页面跳转和异常处理器自身失败等路径。
