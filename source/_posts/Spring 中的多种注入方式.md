---
title: "Spring 依赖注入入门：构造器、Setter 与字段注入"
description: "介绍 Spring 的构造器、Setter 和字段注入，比较各自的优缺点与适用场景，说明 @Autowired、@Resource、@Qualifier 与 @Primary 的选择规则，并梳理测试、可选依赖和循环依赖的常见误区。"
date: 2026-08-20 10:25:42
updated: 2026-10-27
tags: [Spring]
categories: [JAVA后端]
---

# Spring 依赖注入入门：构造器、Setter 与字段注入

一个订单服务需要发送通知，通知功能又可能由短信、邮件等不同实现提供。服务之间如何建立联系，是学习 Spring 时会遇到的第一个实际问题。

Spring 的依赖注入让容器负责提供这些协作对象。理解它时，先分清两件事：**依赖从哪里传进来，以及有多个实现时选哪一个。** 前者对应构造器、Setter、字段等注入位置，后者才涉及 `@Autowired`、`@Resource` 和限定条件。

> 阅读顺序：先理解对象关系，再比较三种注入方式，最后看多 Bean 选择和使用边界。示例以 Java 17、Spring Boot 3.x / Spring Framework 6.x 为背景，代码中的发送行为只是教学模拟。

## 1. 先理解依赖注入解决什么问题

### 1.1 从“自己创建依赖”到“接收依赖”

假设 `NotificationService` 负责发送订单通知。如果它在内部直接写 `new SmsMessageSender()`，就把自己与短信实现绑在一起。想换成邮件，或者在测试中换成假的发送器，都需要修改它的内部代码。

更灵活的方式是：通知服务只依赖一个 `MessageSender` 接口，外部把具体发送器交给它。这个“外部提供协作对象”的过程，就是**依赖注入**，英文为 Dependency Injection，简称 DI。

它并不要求所有对象都由框架创建。手工调用构造器传入依赖，也是一种依赖注入；Spring 只是帮我们自动完成对象查找、创建和装配。

### 1.2 Bean、容器与注入点分别是什么

**Bean** 是由 Spring 管理的对象。例如，一个被扫描到的 `@Service` 服务，或一个由 `@Bean` 方法返回的对象，都可以成为 Bean。

**容器**负责保存 Bean 的定义，并按配置创建、组织这些对象。日常使用的 Spring 应用上下文 `ApplicationContext`，就是访问这些能力的入口。

**注入点**是对象接收依赖的位置，可以是构造器参数、字段或方法参数。容器根据注入点的类型、名称和限定条件查找合适的对象。

以订单通知为例，正常过程是：

1. 容器发现通知服务需要一个 `MessageSender`。
2. 容器找到已注册的短信发送器。
3. 容器创建通知服务，并把发送器传进去。
4. 业务代码调用通知服务，服务再调用发送器完成协作。

这就是控制反转 IoC 在对象组装上的体现：创建和连接依赖的工作交给了容器，业务类专注于自己的行为。

### 1.3 准备一个统一示例

先定义发送接口，保存为 `MessageSender.java`：

```java
public interface MessageSender {
    String send(String message);
}
```

再提供短信实现，保存为 `SmsMessageSender.java`：

```java
import org.springframework.stereotype.Component;

@Component("smsSender")
public class SmsMessageSender implements MessageSender {
    @Override
    public String send(String message) {
        return "SMS: " + message;
    }
}
```

`@Component("smsSender")` 让扫描到它的容器注册这个组件，并明确指定 Bean 名称为 `smsSender`。后文仍使用这组接口与实现，便于比较不同写法。

这些类需要位于 Spring Boot 应用的组件扫描范围内。各个 `public` 类分别保存为同名 Java 文件，包名和同包类型的 import 按自己的工程调整。

## 2. 三种常见的依赖注入方式

### 2.1 构造器注入：对象创建时就拿到依赖

把必须使用的依赖写进构造器，是最直观的声明方式。下面保存为 `NotificationService.java`：

```java
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
    private final MessageSender sender;

    public NotificationService(MessageSender sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    public String notifyOrder(String orderNo) {
        return sender.send("订单 " + orderNo + " 已创建");
    }
}
```

容器创建服务时，会先解析 `MessageSender`，再调用构造器。`final` 表明这个依赖引用在初始化后不再重新赋值，`requireNonNull` 则让手工传入 `null` 时也能立即报错。

这里没有写 `@Autowired`。从 Spring 4.3 起，**一个类只有一个构造器时，通常可以省略构造器上的 `@Autowired`**，不要求构造器只有一个参数。多个构造器的情况，需要按构造器解析规则明确选择。[单构造器规则](https://docs.spring.io/spring-framework/reference/6.2/core/beans/annotation-config/autowired.html)

构造器把必选依赖直接呈现在类的入口，也让单元测试可以通过普通 `new` 创建对象。Spring 官方通常推荐业务组件优先使用这种方式。[构造器注入的取舍](https://docs.spring.io/spring-framework/reference/6.2/core/beans/dependencies/factory-collaborators.html)

**优点：**

- **依赖清晰。** 看构造器就能知道这个类必须与哪些对象协作，不必逐个查找内部字段。
- **初始化约束明确。** 创建对象时就要提供必选依赖，配合非空校验，可让不完整的对象尽早失败。
- **可以使用 `final`。** 依赖引用完成初始化后不再被重新赋值，方便表达稳定的对象关系；这不等于依赖对象本身线程安全。
- **便于单元测试。** 测试可以直接传入假实现，不需要启动容器或通过反射修改私有字段。

**局限：**

- 依赖很多时，构造器和测试中的创建代码会变长。这通常也提示类的职责过多，应先检查设计，而不是简单改成字段注入隐藏参数。
- 不适合依赖运行时随意更换的场景；对于真正可选的依赖，需要明确采用可选参数、默认实现或其他获取方式。
- A、B 互相依赖时，纯构造器注入不能直接完成这个创建环。应调整依赖关系，不能把它理解为构造器注入制造了循环依赖。

**适用场景：**依赖在对象整个生命周期内保持稳定的业务服务、控制器和其他需要明确声明必选协作者的组件。

### 2.2 Setter 注入：创建对象后再设置依赖

Setter 注入通过一个设置方法接收依赖：

```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SetterNotificationService {
    private MessageSender sender;

    @Autowired
    public void setSender(MessageSender sender) {
        this.sender = sender;
    }
}
```

容器先创建对象，再调用这个方法完成装配。这里省略了业务方法，重点观察依赖进入对象的位置。

Setter 适合可以提供合理默认行为的可选依赖，以及确实需要通过设置方法调整的配置。但 **Setter 本身不代表依赖可选**：上例的 `@Autowired` 默认仍然要求找到候选 Bean。

容器也不会因为你提供了 Setter，就持续监听并自动替换依赖。运行期间是否允许重新设置，需要由组件自身的设计决定。

**优点：**

- **适合有默认行为的可选依赖。** 不提供特定组件时可以沿用默认值，确实需要时再替换。
- **配置入口明确。** 调用方可以通过公开方法设置依赖，普通测试也能直接调用 Setter 完成组装。
- **允许有意重新配置。** 如果组件本身支持调整协作者，Setter 提供了显式入口。

**局限：**

- 对象在构造完成与设置依赖之间可能处于不完整状态；手工创建对象的调用方容易漏掉设置步骤。
- 通过 Setter 后续赋值的依赖引用不适合声明为 `final`，也较难单靠构造器表达完整性约束。
- 允许运行时修改依赖会增加状态管理成本；多线程环境下，修改和读取之间的可见性及一致性需要另行设计。

**适用场景：**具有合理默认实现的可选能力、需要显式重新配置的组件，以及已采用属性装配约定的既有代码。

### 2.3 字段注入：写法简短，但依赖隐藏在类内部

字段注入直接在成员变量上声明装配需求：

```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class FieldNotificationService {
    @Autowired
    private MessageSender sender;
}
```

这同样是 Spring 支持的用法，并非被禁止的 API。它的问题主要体现在对象设计和测试：依赖没有出现在构造器中，手工 `new FieldNotificationService()` 时也不会触发 Spring 注入，字段仍然是 `null`。

对于希望通过构造器明确保证完整性的必选依赖，字段注入通常不如构造器清晰。把字段上的 `@Autowired` 换成 `@Resource`，仍然属于字段注入，不会改变这个事实。

**优点：**

- **写法简短。** 不需要为装配额外编写构造器或 Setter，声明依赖所需的代码较少。
- **便于维持既有代码的一致性。** 对于长期采用字段注入、完全由容器管理的旧模块，局部维护时不必为了形式统一而大范围改造。

**局限：**

- 必选依赖隐藏在类内部，调用方无法从创建入口看出对象需要哪些协作者。
- 普通 `new` 不会触发字段注入，未初始化的对象容易在调用时遇到空指针。
- 编写不启动容器的单元测试时，通常需要额外的反射或测试工具来设置私有字段。
- 不能像构造器初始化那样自然地用 `final` 表达这些依赖的初始化约束；添加字段也容易掩盖依赖数量不断增加的问题。

**适用场景：**维护明确采用字段注入规范的既有组件，或框架管理的测试对象。新业务类需要清晰的必选依赖和普通单元测试时，通常优先考虑构造器注入。

## 3. 注入注解如何选择 Bean

### 3.1 `@Autowired`：以类型匹配为基础

`@Autowired` 可以用于构造器、字段和方法。它主要根据注入点声明的类型寻找候选，再结合限定条件等信息选择对象。

只有一个 `MessageSender` 实现时，通常不必额外指定名称。如果同时存在短信和邮件两个实现，就需要明确自己想要哪一个；候选不唯一时，不能依赖“容器随便选一个”。

Spring 在某些条件下也会利用注入点名称辅助匹配，但这不等于一个适用于所有情况的“先类型、再变量名”固定流程。代码依赖特定实现时，显式声明意图更容易维护。

**优点：**支持构造器、字段和多参数方法，适用位置较多；可以配合 `@Qualifier`、`@Primary`、可选依赖及集合注入，表达不同的装配需求。只有一个类型候选时，也不需要把业务类与 Bean 名称绑定。

**局限：**注解本身属于 Spring API；存在多个同类型候选时，需要进一步说明选择规则。字段测试困难、依赖不显式等问题来自字段注入的位置，不能归结为 `@Autowired` 在所有位置上的缺点。

**适用场景：**按类型声明依赖，尤其是需要构造器、多参数方法或限定条件的场景。单构造器可以省略注解，业务类因此也可以保持普通 Java 类的写法。

### 3.2 `@Resource`：适合明确按名称引用

在 Spring Boot 3.x / Spring 6.x 中，使用 `jakarta.annotation.Resource`：

```java
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class ResourceNotificationService {
    @Resource(name = "smsSender")
    private MessageSender sender;
}
```

这表示按名称引用 `smsSender`，并检查类型是否适合这个注入点。它适合想表达“我要这个指定 Bean”的场景。

需要区分两种情况：

- **显式指定 `name`**：按给定名称解析，名称不存在时不会自动改成随意按类型选择。
- **没有指定 `name`**：默认从字段名或 Setter 的属性名推导名称；默认配置下，名称没有匹配时可以回退到类型解析，仍然可能遇到候选歧义。

`@Resource` 是标准注解，Spring 支持它用于字段和单参数的属性 Setter，不能用来标注构造器。标准化的注解来源，也不意味着切换容器时无需检查配置、生命周期和其他框架依赖。[官方名称解析说明](https://docs.spring.io/spring-framework/reference/6.2/core/beans/annotation-config/resource.html)

**优点：**注解来自 Jakarta 标准 API；显式指定名称时，能够直接表达“引用这个 Bean”的意图。在多个实现并存、业务约定按名称区分的场景中，不必依赖默认候选来猜测目标。

**局限：**不能用于构造器或任意多参数方法；显式名称与 Bean 注册名称形成约定，名称调整时也要同步修改引用。省略 `name` 时，字段或属性重命名可能改变解析结果。用于字段时，仍保留字段注入在初始化和测试方面的局限。

**适用场景：**团队明确采用按名称装配约定，或者需要引用某个特定名称的 Bean。需要构造器注入时，应使用对应的参数装配机制，而不是尝试把 `@Resource` 放到构造器上。

### 3.3 `@Qualifier` 与 `@Primary`：解决多个实现的选择

现在再加入邮件实现：

```java
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component("emailSender")
public class EmailMessageSender implements MessageSender {
    @Override
    public String send(String message) {
        return "EMAIL: " + message;
    }
}
```

`@Primary` 表示：当进行单值、按类型装配，并且没有其他更具体限定条件时，优先考虑这个候选。加入上述组件后，前面的普通 `NotificationService` 会优先得到邮件实现。

如果某个业务明确需要短信，可以在构造器参数上加限定条件：

```java
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class SmsNotificationService {
    private final MessageSender sender;

    public SmsNotificationService(
            @Qualifier("smsSender") MessageSender sender) {
        this.sender = sender;
    }
}
```

`@Qualifier` 用来缩小类型候选范围。这里用 Bean 名作为限定值，表达特定选择；它还可以配合自定义限定注解。相比之下，`@Resource(name = "smsSender")` 更直接表达按名称引用。[Qualifier 的语义](https://docs.spring.io/spring-framework/reference/6.2/core/beans/annotation-config/autowired-qualifiers.html)

`@Primary` 也不会让集合注入只保留一个对象。注入 `List<MessageSender>` 时，通常希望取得所有匹配实现，优先候选与“只注入一个”不是同一个问题。

两者的取舍是：`@Primary` 适合表达一个实现的默认地位，可以减少重复限定；但它是类型候选的通用偏好，不能替代具体业务的选择。`@Qualifier` 能把选择写在依赖使用处，意图更明确，代价是需要维护限定值和候选之间的对应关系。

## 4. `@Bean` 方法参数也能接收依赖

### 4.1 用配置类组装对象

一些对象来自第三方库，无法直接添加 `@Component`。这时可以在配置类中创建它们，并通过 `@Bean` 方法参数接收依赖：

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class NotificationConfig {
    @Bean
    public NotificationService notificationService(MessageSender sender) {
        return new NotificationService(sender);
    }
}
```

容器调用这个工厂方法前，会先解析 `MessageSender` 参数。参数上通常不需要再添加 `@Autowired`；有多个候选时，也可以用 `@Qualifier` 限定。[Bean 工厂方法的依赖](https://docs.spring.io/spring-framework/reference/6.2/core/beans/java/bean-annotation.html#beans-java-dependencies)

这是另一种注册 `NotificationService` 的方式。采用它时，应移除服务类上的 `@Service`，或确保没有同时扫描注册同一个服务，避免重复定义。

### 4.2 把“注册方式”和“注入方式”分开看

`@Component`、`@Service` 和 `@Bean` 主要回答“对象如何进入容器”；构造器、Setter、字段则回答“对象如何拿到依赖”。两组概念可以配合使用，不需要放在同一个优先级榜单中比较。

业务服务一般可以由组件扫描注册，再通过构造器接收依赖。第三方对象则常由配置类中的 `@Bean` 方法负责组装。

**优点：**无需修改第三方类的源码，也能把对象交给 Spring 管理；创建过程和依赖关系集中在配置类中，便于表达初始化参数和不同配置下的对象组装方式。

**局限：**需要额外维护配置代码，并避免与组件扫描重复注册。手工调用普通 Java 方法不等于让容器完成装配，不能脱离配置方式和调用位置来假定对象一定经过 Spring 管理。

**适用场景：**第三方客户端、工具对象，以及需要集中控制创建过程的组件。

## 5. 可选依赖和集合依赖如何表达

### 5.1 可选依赖要有明确的缺省行为

如果一个组件没有审计插件也能正常工作，应该在接口和行为中说明“没有插件时怎么办”，而不只是把一个字段改成允许为空。

常见表达包括 `Optional<T>`、`ObjectProvider<T>`，以及配置合理默认值后使用 `@Autowired(required = false)` 的 Setter。`ObjectProvider<T>` 还能把依赖的获取推迟到调用时，但仍需处理缺失或候选歧义。

这些机制解决的是“是否必须、何时获取”，不是自动消除多个实现冲突。依赖是业务必需条件时，仍应优先明确声明为必选。[可选依赖说明](https://docs.spring.io/spring-framework/reference/6.2/core/beans/annotation-config/autowired.html#beans-autowired-annotation-required)

### 5.2 需要所有实现时，使用集合

某些扩展点希望同时通知短信、邮件等多个渠道，可以接收 `List<MessageSender>`，由容器收集匹配实现，再逐个调用。

若行为依赖执行顺序，应通过 `@Order`、`Ordered` 等机制明确排序。不要把 Bean 名称的字母顺序或组件扫描顺序当成稳定的业务约定。

这与“在多个实现中选一个”的场景不同：前者使用集合，后者通过限定条件或默认候选表达选择。

## 6. 四个容易混淆的结论

### 6.1 字段注入并不意味着缺 Bean 时仍能启动

在正常的容器管理流程中，必需字段上的 `@Autowired` 找不到依赖，通常也会导致创建 Bean 失败。它与构造器注入的区别，不是“一个会报错，一个不会报错”。

更值得关注的是对象离开容器后的表现：手工 `new` 一个字段注入类，不会自动填充字段；而构造器会要求调用方在创建对象时明确提供参数。

### 6.2 `final` 不是线程安全保证

`final MessageSender sender` 限制的是依赖引用的重新赋值，不会把发送器内部的可变状态变成不可变，也不会自动保护多个线程对业务数据的并发修改。

构造器注入有助于建立清晰的初始化约束；整个组件是否线程安全，还要检查它的状态、依赖和调用方式。

### 6.3 循环依赖是对象关系问题

如果 A 必须先拿到 B 才能创建，而 B 又必须先拿到 A，纯构造器注入会暴露无法完成的创建环。

改为字段或 Setter 不能普遍保证这个环可以工作。是否允许提前暴露对象，还受到 Bean 作用域、代理和容器配置的限制。Spring Boot 3.3 的 `spring.main.allow-circular-references` 默认是 `false`，不能假定“三级缓存会自动解决”。[Boot 配置说明](https://docs.spring.io/spring-boot/3.3/appendix/application-properties/index.html#application-properties.core.spring.main.allow-circular-references)

遇到这种情况，先检查职责是否需要拆分、公共能力能否下沉。`@Lazy` 或延迟查找有特定使用价值，但不应替代对依赖关系的理解。

### 6.4 注解选择不能代替对象设计

`@Autowired` 没有被 Spring 禁止，`@Resource` 也不是所有场景都更好的替代品。应先确定依赖的位置、是否必需，以及是否有多个候选，再选择对应的声明方式。

同样，不宜把没有核实的团队规范转述成通用的官方要求。对于已有项目，遵守明确的项目约定，并理解该约定解决的问题，比机械替换注解更有价值。

## 7. 如何在项目中选择和验证

### 7.1 按问题选择，而不是按注解排名

| 注入方式 | 主要优点 | 主要局限 | 适用场景 |
| --- | --- | --- | --- |
| 构造器注入 | 依赖显式、支持 `final`、便于普通单元测试 | 参数可能较多，不能直接处理纯构造器循环依赖 | 业务组件的必选依赖 |
| Setter 注入 | 配置入口明确，可为可选依赖提供调整方式 | 存在未完成配置的阶段，依赖引用可被修改 | 有合理默认值的可选能力 |
| 字段注入 | 声明简短，便于维护已有字段装配风格 | 依赖隐藏、手工创建不自动装配、普通测试较麻烦 | 既有约定或框架管理的测试对象 |
| `@Bean` 方法参数 | 适合第三方类，集中控制对象创建 | 需要配置代码，注意重复注册和调用方式 | 第三方对象和显式组装 |

选定注入位置后，再考虑如何选 Bean：按类型装配时使用相应的自动注入规则；需要构造器选择特定实现时加 `@Qualifier`；希望提供默认候选时用 `@Primary`；字段或属性 Setter 需要明确按名称引用时，可以用 `@Resource(name = "...")`。

### 7.2 用一个小测试理解构造器注入

前面示例中的接口可以由 Lambda 实现，因此测试通知服务时，不必启动整个 Spring Boot 应用：

```java
MessageSender fakeSender = message -> "TEST: " + message;
NotificationService service = new NotificationService(fakeSender);

String result = service.notifyOrder("A001");
assert "TEST: 订单 A001 已创建".equals(result);
```

这是可以放入测试方法或 `main` 方法中的片段；使用 Java 原生 `assert` 时要加 `-ea`，或在项目中改用测试框架的断言。预期结果是通知内容经过测试发送器处理，整个过程不依赖短信平台。

这个测试验证业务类如何使用已传入的依赖；还应通过 Spring 容器测试验证组件扫描、多个候选和限定条件是否按预期装配。两种测试关注的问题不同。
