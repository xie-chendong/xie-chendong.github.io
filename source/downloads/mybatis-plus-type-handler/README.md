# MyBatis-Plus 集合字段类型处理器

对应文章《MyBatis-Plus 中 @TableField(typeHandler) 的自定义类型转换》。

- 来源项目：`zfile-study`，来源提交 `e436162cfbb4d31879cb7399509313f61d244b38`。
- 提取日期：2026-10-27；文章日期为作者指定的 2026-08-22。
- 两个 Java 文件逐字节复制自原项目，保留包名、注释和实现。
- `pom.xml` 是为附件整理的最小编译配置，不是原项目的完整 POM。

## 源码

文件位于 `src/main/java/com/chendong/zfilestudy/core/config/mybatis/`：

| 文件 | 用途 |
| --- | --- |
| `CollectionTypeHandler.java` | 集合参数写入、按列名/列下标/存储过程参数读取 |
| `CollectionStrTypeHandler.java` | `Set<String>` 与逗号分隔字符串转换 |

正文中的 `StringSetTypeHandler`、`IntegerListTypeHandler` 是教学示例，未混入原始源码。

## 编译与接入

基线来自原项目：Java 17、Spring Boot 3.3.2、MyBatis-Plus 3.5.6、Hutool 5.8.31。

解压后进入包含 `pom.xml` 的目录，执行 `mvn package` 可构建库 JAR。需要在本机其他 Maven 项目中引用时，可执行 `mvn install` 并添加依赖 `com.chendong:mybatis-plus-type-handler:1.0.0-SNAPSHOT`。这不会发布到公共仓库，团队和 CI 需要自己构建或使用内部构件仓库。

也可以直接复制两个类并合并依赖；已有 MyBatis-Plus 项目应复用自己的 starter，避免重复引入不同版本。附件没有启动类、业务实体、Mapper、数据库驱动或真实连接信息。

在业务实体上启用结果映射，再指定字段处理器：

```java
@TableName(value = "`user`", autoResultMap = true)
public class User {
    @TableField(value = "default_permissions", typeHandler = CollectionStrTypeHandler.class)
    private Set<String> defaultPermissions;
}
```

以上是原项目 `module/user/model/entity/User.java` 中相关映射的节选，并非完整实体。注解来自 MyBatis-Plus，集合来自 `java.util`；更换包名后也要调整处理器 import。字段级处理器不需要作为 Spring Bean 注册。

## 原实现的边界

- 实际存储格式是 `a,b,c`，不是源码注释中写的 `[a, b, c]`；附件保留了原注释，读写格式以实现为准。
- 元素内的逗号没有转义，不能用这个格式无损保存包含逗号的自由文本。
- 读取 Set 使用 HashSet，不保证顺序；空字符串和 SQL NULL 返回不可变空集合。
- 自定义集合类型只支持 List/Set 分支；其他集合类型会返回 null。
- 非字符串元素通过 Hutool 转换，非法值的行为需要在目标系统中验证；附件没有增加校验。

编译检查不等于数据库集成验证。实际使用前应验证查询与更新、空值、重复项和包含分隔符的输入。
