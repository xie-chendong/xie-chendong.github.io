---
title: "Spring Boot 全局异常处理：把错误响应、HTTP 语义与可观测性收敛到一处"
description: "结合 zfile-study 的全局异常处理器，介绍统一 JSON 响应、认证与授权异常映射、参数校验和页面重定向，并区分原项目实现与 Trace ID、异常分层等扩展设计。"
date: 2026-08-25
tags: [SpringBoot]
categories: [JAVA后端]
---

# Spring Boot 全局异常处理：把错误响应、HTTP 语义与可观测性收敛到一处

## 配套源码

- [下载原项目源码快照（ZIP）](/downloads/springboot-global-exception/springboot-global-exception.zip)
- [接入说明与源码边界（README）](/downloads/springboot-global-exception/README.md)

| 文件 | 作用 |
| --- | --- |
| [GlobalExceptionHandler.java](/downloads/springboot-global-exception/src/main/java/com/chendong/zfilestudy/core/exception/GlobalExceptionHandler.java) | 异常映射、参数校验与 API/页面响应分流 |
| [AjaxJson.java](/downloads/springboot-global-exception/src/main/java/com/chendong/zfilestudy/core/util/AjaxJson.java) | 原项目统一响应对象 |
| [ErrorCode.java](/downloads/springboot-global-exception/src/main/java/com/chendong/zfilestudy/core/exception/ErrorCode.java) | 业务错误码 |
| [BizException.java](/downloads/springboot-global-exception/src/main/java/com/chendong/zfilestudy/core/exception/core/BizException.java) | 通用业务异常 |
| [BadRequestAccessException.java](/downloads/springboot-global-exception/src/main/java/com/chendong/zfilestudy/core/exception/status/BadRequestAccessException.java) | 请求错误异常 |
| [UnauthorizedAccessException.java](/downloads/springboot-global-exception/src/main/java/com/chendong/zfilestudy/core/exception/status/UnauthorizedAccessException.java) | 未授权访问异常 |
| [ForbiddenAccessException.java](/downloads/springboot-global-exception/src/main/java/com/chendong/zfilestudy/core/exception/status/ForbiddenAccessException.java) | 禁止访问异常 |
| [FilePathSecurityBizException.java](/downloads/springboot-global-exception/src/main/java/com/chendong/zfilestudy/core/exception/biz/FilePathSecurityBizException.java) | 文件路径校验异常 |
| [InitializeStorageSourceBizException.java](/downloads/springboot-global-exception/src/main/java/com/chendong/zfilestudy/core/exception/biz/InitializeStorageSourceBizException.java) | 存储源初始化异常 |

> **源码范围说明：**附件中的 15 个 Java 文件原样提取自当前 `zfile-study`，包含异常处理器、响应对象及配套工具类。当前项目使用 `@RestControllerAdvice`，没有本文教学扩展中的 `SystemException`、404/405 专用异常、`ResponseBodyAdvice`、MDC 入口过滤器或 SQLite 根因分类；`AjaxJson` 虽有 `traceId` 字段，但没有自动填充链路。附件是需要适配原项目 `SystemConfigService` 的源码快照，不是可独立启动的通用组件。以下通用示例与原项目实现请结合阅读。

> 记忆式：**业务层说明“出了什么错”，异常处理器决定“如何返回”，响应切面补齐“如何定位”。**

## 1. 前言

服务端错误处理最常见的失控信号，是控制器中散落着 `try/catch`、不同接口返回不同 JSON 结构，或者把数据库、网络调用的原始异常直接暴露给客户端。这样既增加前端判断成本，也让排障缺少统一入口。

在 Spring Boot 中，可以用 `@ControllerAdvice` 集中处理跨控制器异常，再结合异常分层、HTTP 状态码、统一响应体和 Trace ID，建立一套可扩展的错误契约。本文以实际文件服务应用中的异常处理为切入点，并给出异常分层与 Trace ID 的通用扩展示例。

## 2. 核心功能概述

这套方案的输入是控制器、服务层、AOP 或框架组件抛出的异常。下面将原项目的异常映射与教学扩展的 Trace ID 响应增强合并展示；最后一步不属于当前原项目实现：

```text
业务代码抛出异常
        |
        v
@ControllerAdvice 按异常类型选择最具体的处理器
        |
        +-- API 请求：HTTP 状态码 + 统一 JSON 错误体
        |
        +-- 页面请求：重定向到登录、无权限、未找到或错误页
        |
        v
ResponseBodyAdvice 为响应补入 Trace ID
```

输出至少包含三层信息：

| 层次 | 职责 | 典型内容 |
| --- | --- | --- |
| HTTP 状态 | 表达协议语义 | `400`、`401`、`403`、`404`、`405`、`500` |
| 业务错误码 | 供前端稳定判断 | 参数错误、未登录、文件不存在、操作不允许 |
| 响应消息与 Trace ID | 供用户提示和排障 | 可读提示、请求关联标识 |

这三者不能互相替代。HTTP 状态适合网关、缓存、监控和通用客户端；业务码承载具体业务分支；Trace ID 将用户看到的一次失败与服务端日志连接起来。

## 3. 环境与前置依赖

本文所述机制依赖 Spring MVC：

- Spring Boot 3.x / Spring Framework 6.x；
- Jakarta Servlet API；
- Bean Validation，用于 `@Valid`、`@Validated` 的参数校验；
- SLF4J 与 MDC，用于请求级日志上下文。

需要理解两个 Spring MVC 扩展点：`@ControllerAdvice` 用于集中选择异常处理方法，`ResponseBodyAdvice` 在消息转换为 JSON 前统一补充响应内容。

## 4. 详细用法教程

### 4.1 先定义稳定的响应信封

错误不应只返回一个字符串。一个最小响应对象可以包含业务码、消息、数据和 Trace ID：

```java
public final class ApiResponse<T> {
    private final String code;
    private final String message;
    private final T data;
    private String traceId;

    private ApiResponse(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return new ApiResponse<>(code, message, data);
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
```

业务错误码应集中维护，且在发布后尽量保持含义稳定。调用端依赖 `code` 分支，展示文本只依赖 `message`。不要让前端通过匹配中文错误消息判断业务状态。

### 4.2 用异常层次表达错误性质

下面的教学示例将异常分为三个维度；原项目当前实现的是 `BizException` 及部分业务、状态子类：

```java
public class BizException extends RuntimeException {
    private final String code;

    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

public class SystemException extends RuntimeException {
    private final String code;
    // 构造器与 BizException 类似
}

public class NotFoundException extends BizException {
    public NotFoundException(String code, String message) {
        super(code, message);
    }
}
```

- `BizException`：用户可以纠正或预期会发生的业务分支，例如参数不合法、资源不存在、无操作权限。
- `SystemException`：网络、存储、第三方服务等用户通常无法自行修复的问题。
- HTTP 语义子类：例如 `BadRequestException`、`UnauthorizedException`、`ForbiddenException`、`NotFoundException`、`MethodNotAllowedException`，用于明确映射 `4xx`。
- 携带上下文的专用子类：例如第三方请求失败时保留 URL、响应码、响应体；上传失败时保留目标类型、路径和上游响应。这些字段用于日志，不应直接原样返回给客户端。

这样，服务层只需要在恰当位置 `throw`，无需知道 JSON 格式、重定向地址或日志格式。

### 4.3 在全局处理器中优先处理具体类型

`@ExceptionHandler` 会选择最匹配的异常类型，因此应先定义有明确 HTTP 语义或需要额外日志上下文的处理器，再保留通用兜底：

```java
@ControllerAdvice
@Order(1)
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(NotFoundException ex) {
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(BizException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleBusiness(BizException ex) {
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(SystemException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleSystem(SystemException ex) {
        log.error("system exception", ex);
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }
}
```

当前原项目中，`400`、`401`、`403` 有对应处理器；`404`、`405` 和 `SystemException` 处理属于这里的扩展示例。未显式指定状态的 `@ResponseStatus` 使用默认 `500`。这意味着“业务异常”并不自动等于 HTTP `400`：状态码必须按调用方和协议语义明确建模。

### 4.4 对 API 请求和页面请求分别返回

浏览器直接打开页面时，重定向通常优于一段 JSON；异步 API 调用则需要可解析的 JSON。案例通过自定义请求头识别异步请求，并为未登录、无权限、未找到和错误页分别配置跳转地址：

```java
@ExceptionHandler(UnauthorizedException.class)
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public ApiResponse<Void> handleUnauthorized() throws IOException {
    if (requestClassifier.isApiRequest()) {
        return ApiResponse.error("AUTH_REQUIRED", "请先登录");
    }
    response.sendRedirect(pageUrls.login());
    return null;
}
```

真实实现还会在重定向写入失败时退回 JSON，避免异常处理器本身再次抛出未处理的 `IOException`。如果采用此设计，请让请求分类规则可审计，例如约定专用请求头，或基于 `Accept` 头统一判断；不要仅靠 URL 前缀猜测。

静态资源未命中是单独的边界：对于使用 History 路由的单页应用，未找到的前端路由应返回入口页，而不是交给通用 `404` 处理器。API 资源不存在仍应返回 `404` JSON，两类路径需要清晰区分。

### 4.5 统一处理参数校验错误

`MethodArgumentNotValidException` 与 `BindException` 都携带 `BindingResult`。原项目只处理前者，并取第一条字段错误作为消息。下面扩展为两种异常，并将字段名和默认提示整理为 `data`，返回 `400`：

```java
@ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
@ResponseBody
@ResponseStatus(HttpStatus.BAD_REQUEST)
public ApiResponse<Map<String, String>> handleValidation(Exception ex) {
    BindingResult result = ex instanceof MethodArgumentNotValidException valid
            ? valid.getBindingResult()
            : ((BindException) ex).getBindingResult();

    Map<String, String> errors = new LinkedHashMap<>();
    result.getFieldErrors().forEach(error ->
            errors.put(error.getField(), error.getDefaultMessage()));
    return ApiResponse.error("INVALID_ARGUMENT", "请求参数异常", errors);
}
```

同一字段存在多个约束时，`Map` 会保留最后一次写入的错误信息。若前端需要展示全部错误，应把值改为 `List<String>`，而不是无意中依赖遍历顺序。

### 4.6 让每个 JSON 响应携带 Trace ID

Trace ID 应在请求入口创建并在请求结束时清理。之后在 `ResponseBodyAdvice` 中，只对统一响应对象填充该值：

```java
@ControllerAdvice
public class TraceResponseAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter type,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return AbstractJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter type, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType,
            ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ApiResponse<?> result) {
            result.setTraceId(MDC.get("traceId"));
        }
        return body;
    }
}
```

这部分是扩展设计，当前原项目没有自动填充 Trace ID 的 advice 或入口过滤器。若应用已经使用 `MappingJacksonValue` 包装响应，可先取得其中的统一响应对象再修改。引入 MDC 后，入口过滤器必须在 `finally` 中执行清理，否则复用线程可能携带上一请求的上下文。

## 5. 核心知识点提炼

1. **异常类型是控制流的契约。** 选择 `BizException`、状态语义子类或系统异常，本质上是在定义处理器的行为、日志级别和 HTTP 响应。
2. **具体处理器优先于父类处理器。** 一个 `NotFoundException` 即使继承自 `BizException`，也会命中专用的 `404` 处理器；通用业务处理器只接住剩余分支。
3. **`@ControllerAdvice` 不等于统一 JSON。** 页面请求可能需要重定向，文件流响应可能已提交；全局处理器要识别这些输出通道。
4. **`ResponseBodyAdvice` 适合补充元数据。** 它不负责把所有成功响应重新设计成统一对象，而是在可 JSON 序列化的统一信封上补充 Trace ID。
5. **MDC 是线程上下文，不是跨线程上下文。** 若请求转入异步执行器、消息队列或响应式链路，需要显式传递或使用相应框架的上下文传播机制。

## 6. 代码重点与难点解析

### 有限深度的根因扫描

下面演示一种可选的通用兜底设计，检查异常及其有限层级的 `cause`，将客户端主动中断、SQLite “database is locked” 等情况归类处理；当前原项目没有这段根因扫描：

```java
private ExceptionKind classify(Exception ex) {
    for (int depth = 0; ex != null && depth < 10; depth++) {
        if (ex instanceof ClientAbortException) {
            return ExceptionKind.IGNORE;
        }
        if (ex instanceof SQLiteException sqlite
                && sqlite.getMessage().contains("database is locked")) {
            return ExceptionKind.RETRY_LATER;
        }
        ex = (Exception) ex.getCause();
    }
    return ExceptionKind.UNEXPECTED;
}
```

扫描上限避免异常链异常长或意外成环时拖慢错误处理。实际实现应使用 `Throwable` 遍历 cause，不能假定每个 cause 都是 `Exception`。如果用 `ThreadLocal` 暂存面向用户的提示，使用后要及时清理，避免线程池中串请求；这也属于扩展设计。

### 日志信息与客户端信息分离

专用异常携带的 URL、存储路径、上游响应体等诊断字段，会由处理器写入警告或错误日志，而客户端只收到固定的业务码和可读提示。这减少了实现细节、授权信息或第三方响应泄露的机会。

需要进一步审视日志字段：上游响应体、请求 URL 和文件路径中可能含有令牌、个人信息或内部地址。生产环境应配置脱敏、长度上限和访问控制，不能因为数据只写日志就认为它安全。

## 7. 踩坑总结与注意事项

1. **不要把所有异常都映射为 `200 OK`。** 这会破坏缓存、监控和通用 HTTP 客户端的判断；协议错误要返回相应的 `4xx`/`5xx`。
2. **不要让通用 `Exception` 回传原始异常消息。** 驱动、SQL、网络和文件系统错误常包含敏感实现细节。当前原项目没有 `Exception.class` 的自定义兜底处理器；若补充，通用策略应仅返回受控提示，并在具备请求追踪能力后附上 Trace ID。
3. **错误码枚举不应可变。** 如果枚举实例提供 `setMessage`、`setCode` 一类写方法，它是 JVM 共享对象；请求期修改会在并发下污染后续响应。动态消息应创建独立错误对象或直接构造异常。
4. **异常处理器也可能失败。** 重定向、写响应、序列化都可能抛异常，必须有降级路径，并避免在响应已提交后再次写 JSON。
5. **校验错误不要丢失多条约束。** 单值 `Map<String, String>` 简洁但只保留一个提示；需求需要完整反馈时改为字段到错误列表的映射。
6. **自定义请求头不能替代安全认证。** 它只决定错误的呈现形式，身份认证与授权仍必须由安全框架和服务端规则完成。

## 8. 关键代码解析

下面的节选是对案例中“状态异常 + API/页面分流”的通用化改写。输入为授权失败异常，输出由请求类型决定。

```java
@ExceptionHandler(ForbiddenException.class)
@ResponseBody
@ResponseStatus(HttpStatus.FORBIDDEN)
public ApiResponse<Void> handleForbidden(ForbiddenException ex) {
    if (requestClassifier.isApiRequest()) {
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }

    try {
        response.sendRedirect(pageUrls.forbidden(ex.getCode(), ex.getMessage()));
        return null;
    } catch (IOException redirectFailure) {
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }
}
```

`@ResponseStatus(HttpStatus.FORBIDDEN)` 为 JSON 分支声明 `403`。直接页面访问走 `sendRedirect` 时，响应会改为重定向状态，不能同时保证最终状态仍为 `403`。`null` 表示重定向已经写入响应；若写入失败，catch 分支提供可序列化的降级结果。

## 9. 总结

一套可靠的全局异常处理不只是一个 `@ExceptionHandler(Exception.class)`。它需要：用异常层次表达错误性质，用精确处理器绑定 HTTP 语义，对 API 与页面请求做输出分流，用统一响应信封提供业务码，并让 Trace ID 和受控日志支撑排障。

实践时优先从四类异常开始：参数校验、认证与授权、资源不存在、未知系统异常。等错误契约稳定后，再为第三方调用、文件上传、并发繁忙等需要额外诊断信息的场景增加专用异常类型。
