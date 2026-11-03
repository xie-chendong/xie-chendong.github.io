---
title: "JWT + 拦截器实现登录：从基础校验到双层拦截器无感续期"
description: "先用 JWT 和单层拦截器完成基础登录，再分析固定有效期和公开接口不续期的问题，通过双层拦截器拆分身份恢复、令牌续期与登录检查，并配合前端接收新令牌。"
date: 2026-11-02
tags: [JWT, SpringBoot, 拦截器]
categories: [JAVA后端]
---

# JWT + 拦截器实现登录：从基础校验到双层拦截器无感续期

上一节已经介绍了 [JWT 的组成、通用工具类与 Session 对比](/2026/11/02/JWT令牌原理与管理员登录实践/)。接下来把 JWT 接入 Web 请求：先完成一个能用的登录方案，再解决用户明明一直在浏览页面，却因为令牌到期被要求重新登录的问题。

两份笔记对应两个阶段：**第一阶段完成登录校验，第二阶段把身份恢复、续期与登录限制拆开。** 本文沿用上一篇的 `JwtUtil`，示例基于 Java 17、Spring Boot 3、JJWT 0.13.0，不再重复 JWT 的基础原理。

## 1. 第一阶段：JWT + 单层拦截器完成登录校验

### 1.1 登录接口签发，业务接口验证

基本流程可以分成五步：

1. 客户端提交账号和密码，登录服务核验账号、密码和启用状态。
2. 校验成功后，服务器签发 JWT，将用户编号写入 `sub`，设置有效期。
3. 客户端保存令牌，后续请求通过 `Authorization: Bearer <token>` 携带。
4. 拦截器在业务方法执行前验签、检查有效期，成功后记录当前请求的用户身份。
5. 缺少或无效的令牌返回 `401`；通过认证后，业务层继续检查操作权限。

Spring MVC 的 `HandlerInterceptor` 是请求处理过程中的拦截器，`preHandle` 在匹配的处理器执行前调用：返回 `true` 继续处理，返回 `false` 中止后续执行。**写一个拦截器类还不够，必须注册并配置拦截范围。**

### 1.2 登录成功后返回 JWT

假设项目已有 `loginService.authenticate(username, password)`，能够校验密码及账号状态，失败时抛出业务异常，成功时返回可信的用户编号。登录方法中签发令牌的核心部分如下：

```java
String userId = loginService.authenticate(username, password);
String token = jwtUtil.createToken(userId, Map.of(), Duration.ofHours(2));
return Map.of("token", token);
```

这是现有登录方法内的片段，省略了 Controller、DTO 和数据库查询。`Map`、`Duration` 分别来自 `java.util`、`java.time`。不要根据客户端传入的用户编号直接签发令牌。

应用启动时创建并复用上一篇的 `JwtUtil` Bean：

```java
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {
    @Bean
    public JwtUtil jwtUtil(@Value("${security.jwt.secret-base64}") String secret) {
        return new JwtUtil(secret, "example-auth", "example-api");
    }
}
```

在 `application.yml` 中引用环境变量，密钥生成方式见上一篇：

```yaml
security:
  jwt:
    secret-base64: ${JWT_SECRET_BASE64}
```

### 1.3 用一个拦截器保护业务接口

```java
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

public final class LoginInterceptor implements HandlerInterceptor {
    private final JwtUtil jwt;

    public LoginInterceptor(JwtUtil jwt) {
        this.jwt = jwt;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response, Object handler) {
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            try {
                Claims claims = jwt.parseToken(header.substring(7).trim());
                request.setAttribute("currentUserId", claims.getSubject());
                return true;
            } catch (JwtException | IllegalArgumentException ex) {
                // 凭证无效，统一按未认证处理。
            }
        }
        response.setHeader("WWW-Authenticate", "Bearer realm=\"api\"");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return false;
    }
}
```

用户身份放在当前请求的属性中，后续处理可以通过 `request.getAttribute("currentUserId")` 获取。这样无需为同步请求专门引入 `ThreadLocal`；如果沿用原项目的线程上下文，则必须在请求完成后清理，避免线程复用造成身份串用。

将下面配置放入项目扫描范围内。示例约定 `/api/public/**` 是公开接口，其余 `/api/**` 默认要求登录；登录、验证码接口单独放行：

```java
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final JwtUtil jwt;

    public WebConfig(JwtUtil jwt) {
        this.jwt = jwt;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor(jwt))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/login", "/api/code", "/api/public/**");
    }
}
```

到这里，登录签发、携带凭证、统一校验已经接通。前端保存和发送令牌的代码在第五节给出，先忽略其中接收新令牌的部分，即可用于这一阶段。

## 2. 能登录之后，还存在哪些问题

### 2.1 固定有效期不关心用户是否活跃

假设用户 10:00 登录，JWT 在 12:00 失效。即使他一直查看订单，原令牌的 `exp` 也不会改变，12:00 后仍会被拒绝。简单粗暴地把有效期改成一个月，会扩大令牌被盗后的使用窗口。

更合适的体验是：**用户携带仍有效的令牌持续访问时，在临近到期前换发新令牌；长时间没有请求、令牌已经过期时，再要求重新认证。**

这里的“无感”是客户端自动替换令牌，用户无需手动操作，并不表示过期令牌可以无限使用。

### 2.2 在原拦截器里续期，为什么还不够

第一阶段的拦截器排除了公开接口。若直接在其中添加续期逻辑，只有访问受保护接口才有机会续期。

例如用户登录后一直浏览 `/api/public/shops`，这些请求根本不经过登录拦截器。尽管一直在使用系统，令牌仍会到期。下一次访问个人中心，用户就被要求重新登录。

问题并不在于一个类绝对做不到，而在于**身份恢复与续期需要覆盖更多接口，登录限制却只应该作用于受保护接口**。把两件事绑在同一组排除规则上，职责容易冲突。

### 2.3 基础方案还有哪些边界

- JWT 校验通过只确认凭证符合规则，业务仍需检查权限、账号封禁等状态。
- 客户端删除令牌不能让其他副本立即失效，主动注销需要额外设计。
- 签名可以发现篡改，但不能阻止合法令牌被盗后原样重放。

第二阶段主要解决活跃用户续期和接口职责划分，不能顺带消除这些限制。

## 3. 第二阶段：将职责拆成两层拦截器

### 3.1 第一层恢复身份并按需续期，第二层检查是否登录

**第一层 `RefreshTokenInterceptor`** 覆盖登录、验证码之外的 API，包括公开接口：没有有效令牌就按匿名请求继续；有有效令牌则恢复身份，在临近到期时签发新令牌，并通过响应头返回。

**第二层 `LoginRequiredInterceptor`** 只处理受保护接口：检查第一层是否已建立可信身份，没有就返回 `401`。它不重复解析 JWT。

| 请求情况 | 第一层 | 第二层 | 最终结果 |
| --- | --- | --- | --- |
| 公开接口，无令牌 | 继续，保持匿名 | 不执行 | 可以匿名访问 |
| 公开接口，失效令牌 | 继续，保持匿名，不续期 | 不执行 | 可以匿名访问 |
| 公开接口，有效且临近过期 | 恢复身份，返回新令牌 | 不执行 | 正常访问并续期 |
| 受保护接口，无令牌或令牌无效 | 继续，保持匿名 | 返回 `401` | 拒绝访问 |
| 受保护接口，令牌有效 | 恢复身份，按需续期 | 放行 | 继续业务权限检查 |

这也明确了一个策略选择：**公开接口不会因为客户端遗留了一个坏令牌而强制登录**。这是本文对笔记行为的调整；公开接口是否允许匿名，最终仍由接口业务约定决定。

### 3.2 JWT 续期与 Redis 续期的区别

Redis 随机令牌方案可以保持令牌字符串不变，只延长服务端登录记录的 TTL。这里使用的是 JWT，其失效时间在已签名内容中，不能通过修改原字符串来延长有效期。

因此本方案的续期流程是：**校验旧 JWT → 生成新 JWT → 响应头返回 → 前端替换 → 后续请求携带新 JWT**。

它也不是 Access Token + Refresh Token 双令牌方案。这里仍然只凭一个尚未过期的访问令牌进行换发；旧令牌已经过期时不能靠这条流程恢复登录。

## 4. 后端实现双层拦截与临近过期续期

### 4.1 第一层：恢复身份并返回新令牌

沿用两小时有效期，在剩余时间不超过 40 分钟时换发。公开接口也可以触发，因此不用等到用户进入个人中心才续期。

```java
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public final class RefreshTokenInterceptor implements HandlerInterceptor {
    private static final Duration TOKEN_TTL = Duration.ofHours(2);
    private static final Duration REFRESH_WINDOW = Duration.ofMinutes(40);
    private final JwtUtil jwt;

    public RefreshTokenInterceptor(JwtUtil jwt) {
        this.jwt = jwt;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response, Object handler) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return true;
        }
        Claims claims;
        try {
            claims = jwt.parseToken(header.substring(7).trim());
        } catch (JwtException | IllegalArgumentException ex) {
            return true;
        }
        request.setAttribute("currentUserId", claims.getSubject());
        Duration remaining = Duration.between(Instant.now(),
                claims.getExpiration().toInstant());
        if (remaining.compareTo(REFRESH_WINDOW) <= 0) {
            String newToken = jwt.createToken(claims.getSubject(),
                    Map.of(), TOKEN_TTL);
            response.setHeader("X-Refresh-Token", newToken);
            response.setHeader("Cache-Control", "no-store");
        }
        return true;
    }
}
```

捕获范围只包住凭证解析，配置加载、令牌签发等系统错误应进入正常错误处理。示例令牌只保存用户编号，所以换发时传入新的 `Map.of()`。若业务需要其他声明，应从可信来源重新构造白名单字段，不要把旧 `Claims` 整份复制进去。

笔记中“复制旧 Claims 一定会覆盖新有效期”的结论需要限定：**是否覆盖取决于构建顺序**。先设置旧 Claims、再设置新的 `expiration`，新值可以覆盖旧值，具体可对照 [JJWT 0.9.1 的构建器实现](https://github.com/jwtk/jjwt/blob/0.9.1/src/main/java/io/jsonwebtoken/impl/DefaultJwtBuilder.java)。这里直接使用上一篇的工具类，它禁止自定义声明覆盖标准字段，更容易保证新令牌具有新的 `iat`、`exp` 和 `jti`。

### 4.2 第二层：只判断是否建立了可信身份

```java
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

public final class LoginRequiredInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response, Object handler) {
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        if (request.getAttribute("currentUserId") != null) {
            return true;
        }
        response.setHeader("WWW-Authenticate", "Bearer realm=\"api\"");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return false;
    }
}
```

第二层依赖第一层先执行。用户编号来自验签后的声明，不能从客户端自带的 `userId` 请求头中直接恢复身份。

### 4.3 替换注册配置，保证先恢复身份再检查登录

用下面的 `WebConfig` **替换第一阶段的配置**，不要同时注册旧的 `LoginInterceptor`。`JwtConfig` 继续保留。

```java
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final JwtUtil jwt;

    public WebConfig(JwtUtil jwt) {
        this.jwt = jwt;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RefreshTokenInterceptor(jwt))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/login", "/api/code")
                .order(0);
        registry.addInterceptor(new LoginRequiredInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/login", "/api/code", "/api/public/**")
                .order(1);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .exposedHeaders("X-Refresh-Token");
    }
}
```

`order` 越小越先执行。跨域配置中的来源是开发示例，上线时改成自己的可信前端地址。`allowedHeaders` 允许前端发送凭证，`exposedHeaders` 则允许浏览器脚本读取新令牌；两者不能互相替代。登录检查通过 `CorsUtils.isPreFlightRequest` 识别并放行 CORS 预检，再由 Spring 校验来源和请求头；预检成功后的实际业务请求仍需正常认证。参见 [Spring CORS 说明](https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html)。

> Spring 官方指出 MVC 拦截器与控制器路径匹配可能存在差异，不适合作为完整安全层。这里用它说明两份笔记的演进思路；正式系统应优先在 Spring Security 或 Servlet Filter 链中统一落实认证与授权。参见 [Spring 拦截器说明](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-config/interceptors.html)。

## 5. 前端接收新令牌，续期流程才真正完成

### 5.1 每次请求读取当前令牌，每次响应检查是否换发

假设页面已引入 Axios 1.x，登录成功后保存 `sessionStorage.setItem("token", response.data.token)`。随后通过同一个 `api` 实例调用接口：

```javascript
const api = axios.create({ baseURL: "http://localhost:8080" });

api.interceptors.request.use(config => {
  const token = sessionStorage.getItem("token");
  config.sentToken = token;
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

function acceptNewToken(response) {
  const newToken = response.headers["x-refresh-token"];
  if (newToken && sessionStorage.getItem("token") === response.config.sentToken) {
    sessionStorage.setItem("token", newToken);
  }
}

api.interceptors.response.use(response => {
  acceptNewToken(response);
  return response;
}, error => {
  if (error.response?.status === 401) {
    if (sessionStorage.getItem("token") === error.config?.sentToken) {
      sessionStorage.removeItem("token");
    }
    // 由页面统一提示或跳转登录，不在这里自动重试失效凭证。
  } else if (error.response) {
    acceptNewToken(error.response);
  }
  return Promise.reject(error);
});
```

请求拦截器每次读取存储，避免永远使用初始化时保存的旧值。响应拦截器先接收新令牌，再将响应交给页面处理；即使业务返回 `403` 等错误，也可能已经完成了合法续期。Axios 的默认成功、失败响应分支见 [官方拦截器说明](https://axios-http.com/docs/interceptors)。

代码还记录了本次请求发出时的 `sentToken`，只有客户端仍使用它时才替换或删除。这能避免旧响应覆盖刚登录的新令牌，或退出登录后被迟到的响应重新写入令牌。并发续期响应中，接受一个后其他旧响应会被忽略；这不等于服务端实现了严格的一次性令牌轮换。

### 5.2 用一条时间线检查是否生效

1. 10:00 登录，获得 12:00 过期的令牌 A。
2. 10:30 请求公开列表：第一层恢复身份，剩余时间充足，不换发。
3. 11:30 请求公开列表：第一层生成令牌 B，设置为 13:30 过期，通过响应头返回。
4. 前端保存 B，下一次请求携带 B，即使用户未进入受保护页面也完成了续期。
5. 如果用户没有再发送请求，直到令牌过期后才访问，公开页面仍可匿名访问，受保护接口则返回 `401`。

后端只返回新令牌、前端没有替换，或者浏览器因 CORS 没有暴露响应头，都会导致下一次仍使用 A。存储新令牌要调用 `setItem`，不是 `getItem`，也不要在控制台或服务端日志打印完整令牌。

## 6. 改进的收益与仍然存在的限制

| 方案 | 收益 | 局限 |
| --- | --- | --- |
| 单层拦截器，只校验 | 结构简单，能完成基础登录保护 | 活跃用户也会因固定有效期重新登录 |
| 单层拦截器内续期 | 访问受保护接口时可延续登录 | 被排除的公开接口无法触发续期 |
| 双层拦截器 + 前端换发 | 公开页面上的有效请求也可续期，登录限制与续期职责清晰 | 需配合前端和跨域配置，仍有凭证盗用与撤销问题 |

还需要记住四个边界：

- **活跃请求续期不等于永久免登录**。已过期令牌不能直接换新；需要离线后恢复登录时，可进一步设计独立刷新令牌。
- **旧令牌没有自动失效**。签发 B 不会撤销 A；若要求立即注销，需要服务端撤销列表、账号版本等机制。
- **被盗令牌也可能续期**。攻击者持有有效令牌并持续请求，也可能拿到新令牌。应结合 HTTPS、凭证保护、撤销策略，并为高风险系统设置最长会话期限，达到后重新认证；本例未实现绝对期限。
- **前端存储和身份上下文有边界**。`sessionStorage` 可被同源脚本读取，需防范 XSS；同步请求属性不能直接替代异步任务中的身份传递方案。

从第一阶段到第二阶段，核心变化是把“识别是谁、是否需要续期”和“这个接口是否必须登录”拆开，再由前后端共同完成令牌替换，从而减少活跃用户被迫重新登录的次数。
