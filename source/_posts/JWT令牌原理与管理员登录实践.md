---
title: "JWT 入门：令牌组成、通用工具类与 Session 对比"
description: "简要理解 JWT 的三部分组成和防篡改原理，使用 Java 实现通用令牌生成与解析工具，并比较 JWT、Session 和随机令牌的优缺点，梳理令牌盗用风险与处理方法。"
date: 2026-11-02
tags: [JWT, SpringBoot]
categories: [JAVA后端]
---

# JWT 入门：令牌组成、通用工具类与 Session 对比

登录成功后，服务器可以向客户端发放一个 Token（令牌），后续请求携带它来证明登录身份。JWT（JSON Web Token）是一种常见的令牌格式，能够携带身份信息，并通过签名校验发现未经授权的修改。

本文先介绍组成和原理，再实现一个不依赖具体业务的 Java 工具类，最后比较不同会话方案及其安全取舍。拦截器与完整登录流程放在后续文章中展开。

## 1. JWT 的组成与防篡改原理

### 1.1 Header、Payload 与 Signature

常见的签名 JWT 由三个部分组成，中间用英文句点分隔：

```text
Base64URL(Header).Base64URL(Payload).Base64URL(Signature)
```

| 部分 | 作用 | 示例 |
| --- | --- | --- |
| Header，头部 | 说明算法、类型等信息 | `{"alg":"HS256","typ":"JWT"}` |
| Payload，载荷 | 保存用户编号、有效期等声明 | `{"sub":"1001","exp":1700000900}` |
| Signature，签名 | 用于校验头部和载荷的完整性 | 根据前两段内容和密钥计算得到 |

Payload 中的一条信息称为 Claim（声明）。常用的声明有：`sub` 表示主体，`exp` 表示失效时间，`iat` 表示签发时间，`iss` 表示签发方，`aud` 表示接收方，`jti` 表示令牌编号。上表中的时间仅演示格式，标准时间声明使用秒级时间戳。

**Base64URL 是编码，不是加密。** 普通签名 JWT 的前两段可以直接解码，因此不要在其中保存密码、密钥等敏感信息。这里讨论的是三段式的签名形式；采用 JWE 加密的 JWT 紧凑格式为五段。相关定义见 [RFC 7519](https://www.rfc-editor.org/rfc/rfc7519.html#section-3)。

### 1.2 为什么修改用户编号后会被发现

以 HS256 为例，签名可以简化理解为：

```text
待签名内容 = 编码后的 Header + "." + 编码后的 Payload
签名字节 = HMAC-SHA256(服务端密钥, 待签名内容)
```

攻击者可以把 Payload 中的用户编号从 `1001` 改成 `1`，但原签名对应的是旧内容。服务器校验时发现签名不匹配，就会拒绝令牌。攻击者虽然知道算法，却在没有密钥的情况下难以生成合法签名。

所以，JWT 防篡改的准确含义是：**内容可以被修改，但未经授权的修改应当无法通过校验。** 这个保证依赖算法实现正确、密钥足够强且保密，以及服务端真正执行了验签。HS256 使用共享密钥，不区分公钥和私钥；密钥应由安全随机源生成，至少 32 字节。算法和密钥要求见 [RFC 7518](https://www.rfc-editor.org/rfc/rfc7518.html#section-3.2)。

## 2. 实现一个通用 JWT 工具类

### 2.1 添加依赖并准备密钥

以下示例使用 Java 17 和 **JJWT 0.13.0**，不依赖 Spring。三个模块保持相同版本，API 之外还需要运行时实现和 JSON 支持，见 [该版本的依赖说明](https://github.com/jwtk/jjwt/blob/0.13.0/README.adoc#installation)。

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.13.0</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.13.0</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.13.0</version>
    <scope>runtime</scope>
</dependency>
```

在已安装 OpenSSL 的环境中，可以生成随机密钥：

```bash
openssl rand -base64 32
```

将结果放入环境变量 `JWT_SECRET_BASE64` 或密钥管理服务，不要写进源码仓库。应用启动时读取并复用，多实例保持一致；每次启动都换一把随机密钥，会导致已有令牌无法校验。

### 2.2 生成令牌与解析令牌

将下面代码保存为 `JwtUtil.java`。它提供两个接口：

- `createToken(subject, extraClaims, ttl)`：传入主体、自定义声明和有效期，生成令牌。
- `parseToken(token)`：验签并校验声明，成功时返回 Claims，失败时抛出异常。

```java
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class JwtUtil {
    private static final Set<String> RESERVED = Set.of(
            "iss", "sub", "aud", "exp", "nbf", "iat", "jti");
    private final SecretKey key;
    private final String issuer;
    private final String audience;
    private final JwtParser parser;

    public JwtUtil(String base64Secret, String issuer, String audience) {
        if (base64Secret == null || base64Secret.isBlank()
                || issuer == null || issuer.isBlank()
                || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("密钥、签发方和接收方不能为空");
        }
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
        this.issuer = issuer;
        this.audience = audience;
        this.parser = Jwts.parser()
                .verifyWith(key)
                .sig().clear().add(Jwts.SIG.HS256).and()
                .requireIssuer(issuer)
                .requireAudience(audience)
                .build();
    }

    public String createToken(String subject, Map<String, ?> extraClaims,
                              Duration ttl) {
        if (subject == null || subject.isBlank()
                || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("主体不能为空，有效期必须为正数");
        }
        if (extraClaims == null
                || !Collections.disjoint(extraClaims.keySet(), RESERVED)) {
            throw new IllegalArgumentException("自定义声明不能为 null，也不能覆盖标准声明");
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .claims(extraClaims)
                .subject(subject)
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .id(UUID.randomUUID().toString())
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public Claims parseToken(String token) {
        Claims claims = parser.parseSignedClaims(token).getPayload();
        if (claims.getExpiration() == null
                || claims.getSubject() == null || claims.getSubject().isBlank()) {
            throw new JwtException("令牌缺少有效期或主体");
        }
        return claims;
    }
}
```

这个类通过构造器配置签发方和接收方，不绑定员工编号等业务类型。主体可以是用户编号字符串，自定义声明可以携带服务端确认的业务信息；没有额外声明时传 `Map.of()`。

几个关键点：

- **解析包含校验**：`parseSignedClaims` 会验签，并检查存在的 `exp`、`nbf` 等时间条件；这里还要求 `exp` 和 `sub` 必须存在。
- **限制接受范围**：只接受 HS256，并要求 `iss`、`aud` 匹配，避免把其他用途的令牌直接拿来使用。相关接口见 [解析器 API](https://javadoc.io/static/io.jsonwebtoken/jjwt-api/0.13.0/io/jsonwebtoken/JwtParserBuilder.html)。
- **控制声明写入**：自定义声明不能覆盖标准字段，身份、角色等值也必须来自服务端可信结果。
- **异常交给调用方处理**：令牌过期、签名错误、格式错误时均不返回可信身份；单纯解码不能替代 `parseToken`。

### 2.3 调用示例

下面的 `JwtDemo.java` 演示生成令牌和读取声明，代码不涉及 Web 请求或拦截器：

```java
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.util.Map;

public class JwtDemo {
    public static void main(String[] args) {
        JwtUtil jwt = new JwtUtil(System.getenv("JWT_SECRET_BASE64"),
                "example-auth", "example-api");
        String token = jwt.createToken("1001", Map.of("role", "USER"),
                Duration.ofMinutes(15));
        try {
            Claims claims = jwt.parseToken(token);
            System.out.println("用户编号：" + claims.getSubject());
            System.out.println("角色：" + claims.get("role", String.class));
        } catch (ExpiredJwtException ex) {
            System.out.println("令牌已过期，需要重新认证或按规则刷新");
        } catch (JwtException | IllegalArgumentException ex) {
            System.out.println("令牌无效");
        }
    }
}
```

配置密钥和依赖后，成功运行会输出用户编号 `1001` 和角色 `USER`。15 分钟只是示例值。过期令牌不能直接放行，也不能仅提取其中的身份就无条件换发新令牌；刷新需要独立的受控流程。

## 3. JWT、Session 与随机令牌有什么区别

### 3.1 先分清比较的对象

**Session 是会话管理机制，JWT 是令牌格式。** 它们不完全处于同一层次。常见 Session 方案让客户端保存一个 Session ID，服务器据此查找登录状态；JWT 方案则将部分身份声明直接放进令牌。

随机令牌也叫不透明令牌（Opaque Token），客户端拿到一串无法直接理解的随机值，由服务端查询其对应身份、权限和有效状态。它与 Session ID 的思路相近，只是通常作为 API 访问凭证使用，服务端也可通过受保护的令牌内省接口查询状态，见 [RFC 7662](https://www.rfc-editor.org/rfc/rfc7662.html)。

Cookie 则是浏览器保存和发送数据的机制，既可以保存 Session ID，也可以保存 JWT；因此不能用“JWT 和 Cookie 谁更好”代替会话方案的比较。

| 对比项 | 自包含签名 JWT | Session ID | 随机访问令牌 |
| --- | --- | --- | --- |
| 身份信息主要存放处 | 令牌中的声明 | 服务端会话 | 服务端令牌记录 |
| 常见校验方式 | 本地验签及声明校验 | 查询会话是否有效 | 查询记录或调用内省接口 |
| 多实例使用 | 分发可信验证密钥和规则 | 通常需要共享会话或其他一致性方案 | 通常需要共享存储或认证服务 |
| 主动注销 | 需要撤销记录等额外设计 | 服务端删除或禁用会话 | 服务端撤销对应记录 |
| 权限变化 | 旧声明可能保留到过期 | 可更新服务端状态并及时检查 | 可更新服务端记录并及时检查 |
| 每次携带的数据量 | 通常较大 | 通常较小 | 通常较小 |

### 3.2 各自的优点、弊端与选择

- **JWT**：便于跨服务传递身份，可以减少查询集中会话存储的次数；代价是撤销较复杂、声明可能过时、密钥需要妥善管理。适合多个服务验证统一签发方的凭证。
- **Session**：服务端集中管理，注销、封禁和会话更新直接；代价是维护会话存储，多实例需要协调。适合重视集中会话控制的传统 Web 系统。
- **随机令牌**：令牌本身不暴露业务声明，撤销控制方便；代价是查询或内省的开销，以及对状态存储、认证服务的依赖。适合强调集中控制的 API 系统。

JWT 的“无状态”通常指不必为恢复身份而查询会话，并不意味着不能查数据库或 Redis。增加撤销检查后，系统就会重新依赖服务端状态。Session ID 和随机令牌同样需要防止泄露，会话保护原则可参见 [OWASP 会话管理建议](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)。

## 4. JWT 被盗用的风险与处理方法

### 4.1 防篡改不等于防盗用

普通 Bearer 令牌遵循“持有者即可使用”的方式。攻击者如果拿到尚未失效的合法 JWT，可以原样发送它，签名仍然有效，服务器无法仅靠验签确认是不是原来的用户。

这叫**重放风险**，Session ID 和随机 Bearer 令牌被盗后也可能出现相同问题。`jti` 只是令牌编号，工具类生成 UUID 并不会自动防重放，仍需服务端检查记录。相关风险见 [RFC 6750](https://www.rfc-editor.org/rfc/rfc6750.html#section-5)。

### 4.2 减少泄露与盗用窗口

| 方法 | 作用 | 边界或代价 |
| --- | --- | --- |
| 全程 HTTPS | 降低传输中被窃听的风险 | 无法解决终端被入侵或主动泄露 |
| 不在 URL、日志、截图中暴露令牌 | 减少意外泄露渠道 | 还需限制日志与调试信息访问 |
| 短期访问令牌 | 缩短被盗后的可用时间 | 不能阻止有效期内的重放 |
| 受控刷新机制 | 减少频繁登录 | 刷新令牌需单独保护、轮换与撤销 |
| 防 XSS，谨慎选择存储位置 | 降低恶意脚本获取凭证的风险 | `localStorage` 可被同源 JavaScript 读取 |
| Cookie 配置 `HttpOnly`、`Secure` 和合适的 `SameSite` | 限制脚本读取和部分携带场景 | 自动携带 Cookie 时仍需 CSRF 防护，不能阻止所有 XSS 操作 |

浏览器存储的取舍可结合 [OWASP 本地存储建议](https://cheatsheetseries.owasp.org/cheatsheets/HTML5_Security_Cheat_Sheet.html#local-storage) 理解。业务接口仍需检查操作权限，不能因为令牌合法就允许访问任意用户的数据。

### 4.3 发现泄露后如何让令牌失效

仅在前端删除 JWT，不能使攻击者手中的副本失效。需要立即阻断时，可以选择：

- **撤销列表**：将令牌的 `jti` 写入 Redis 等存储，每次校验时查询，记录保存到令牌过期；适合撤销单个令牌。
- **账号令牌版本**：令牌携带版本号，服务端维护当前版本，认证时比较；修改密码、封禁或“退出所有设备”时更新版本。
- **服务端会话方案**：如果经常需要即时注销和在线会话管理，直接采用 Session 或随机令牌也很合适。

这些方法都增加了服务端状态，需要保证检查真正执行，缓存也不能让撤销长时间失效。若泄露的是**签名密钥**，影响可能扩展到所有使用该密钥的令牌，应更换密钥并停止接受旧密钥，而不是只撤销某个用户。

JWT 能解决声明传递和防篡改问题；是否采用它，应结合跨服务需求、会话管理成本和令牌失效要求决定。
