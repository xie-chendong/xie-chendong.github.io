---
title: "MyBatis-Plus 中 @TableField(typeHandler) 的自定义类型转换"
description: "通过 MyBatis-Plus 的字段级 TypeHandler，将数据库逗号分隔文本与 Java 集合互相转换，结合原项目的集合处理器说明参数绑定、结果映射、autoResultMap 与空值和顺序的处理边界。"
date: 2026-08-22
tags: ["MyBatis Plus"]
categories: [JAVA后端]
---

# MyBatis-Plus 中 `@TableField(typeHandler)` 的自定义类型转换

## 配套源码

- [下载配套源码包（ZIP）](/downloads/mybatis-plus-type-handler/mybatis-plus-type-handler.zip)
- [接入说明（README）](/downloads/mybatis-plus-type-handler/README.md)
- [Maven 配置（pom.xml）](/downloads/mybatis-plus-type-handler/pom.xml)

| 文件 | 作用 |
| --- | --- |
| [CollectionTypeHandler.java](/downloads/mybatis-plus-type-handler/src/main/java/com/chendong/zfilestudy/core/config/mybatis/CollectionTypeHandler.java) | 集中处理集合的 JDBC 写入与三个读取入口 |
| [CollectionStrTypeHandler.java](/downloads/mybatis-plus-type-handler/src/main/java/com/chendong/zfilestudy/core/config/mybatis/CollectionStrTypeHandler.java) | 指定 Set 集合和 String 元素类型 |

两个 Java 文件原样提取自 `zfile-study`，保留原包名和实现；正文中的 `StringSetTypeHandler`、`IntegerListTypeHandler` 是教学示例，不属于原项目源码。附件提供最小编译配置，实际接入仍需配置业务实体、Mapper 与数据库。

## 1. 前言

关系型数据库的列通常保存标量值，而 Java 实体可以使用集合、枚举、JSON 对象等更贴近业务的类型。例如，把权限集合直接声明为 `Set<String>`，比在业务代码中反复拆分字符串更清晰。

`@TableField` 的 `typeHandler` 属性用于把这两种表示连接起来：写库时把 Java 值转换为 JDBC 可以写入的值，查库时再把 JDBC 值还原为实体属性。本文以“逗号分隔字符串 <-> `Set<String>`”为例，说明处理器如何实现、哪些方法必须重写，以及一套实际的集合处理器如何工作。

## 2. 核心功能概述

处理链可以概括为：

```text
Set<String> -- INSERT / UPDATE --> TypeHandler -- setString --> TEXT 或 VARCHAR 列
TEXT 或 VARCHAR 列 -- SELECT --> TypeHandler -- getString --> Set<String>
```

在实体字段上显式指定处理器后，MyBatis-Plus 会在该字段参与参数绑定和结果映射时使用它：

```java
@TableName(value = "`user`", autoResultMap = true)
public class User {

    @TableField(value = "default_permissions", typeHandler = CollectionStrTypeHandler.class)
    private Set<String> defaultPermissions;
}
```

这里的数据库列为 `TEXT`，Java 属性为 `Set<String>`。`autoResultMap = true` 是关键配置：它让 MyBatis-Plus 为实体构建结果映射，从而在查询结果填充实体字段时应用 `typeHandler`。没有它时，生成的查询结果映射可能不会带上字段级处理器。

该方式适合格式稳定、查询时不需要按集合单项筛选的小型集合字段。若需要按成员高频筛选、统计或维护外键关系，通常应改用关联表；若值本身可能包含逗号或结构会持续扩展，JSON 格式会更合适。

## 3. 环境与前置依赖

本文基于以下 API：

- Java 17；
- MyBatis 3 的 `org.apache.ibatis.type.BaseTypeHandler`、`JdbcType` 与 `@MappedJdbcTypes`；
- MyBatis-Plus 3.5.x 的 `@TableName` 和 `@TableField`；
- JDBC 的 `PreparedStatement`、`ResultSet` 与 `CallableStatement`。

理解 JDBC 的“参数绑定”和“结果读取”两个阶段即可。处理器不负责执行 SQL，也不需要由 Spring 注入；MyBatis 会在映射过程中创建并调用它。

## 4. 详细用法教程

### 4.1 在字段上绑定处理器

最直接的使用方式是在目标字段上指定处理器类：

```java
@TableName(autoResultMap = true)
public class Account {

    @TableField(value = "roles", typeHandler = StringSetTypeHandler.class)
    private Set<String> roles;
}
```

`value` 是列名，`typeHandler` 是实现 `TypeHandler` 的类。字段级指定的优先级很明确，适合只让某一个字段使用特殊转换规则；不必把这个规则注册为所有同类 Java 字段的全局默认处理器。

### 4.2 编写一个最小可用的处理器

推荐继承 `BaseTypeHandler<T>`，让泛型 `T` 与实体字段类型一致。下面是用于教学的通用化改写，采用逗号分隔格式：

```java
@MappedJdbcTypes(JdbcType.VARCHAR)
public class StringSetTypeHandler extends BaseTypeHandler<Set<String>> {

    @Override
    public void setNonNullParameter(
            PreparedStatement ps, int index, Set<String> parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(index, String.join(",", parameter));
    }

    @Override
    public Set<String> getNullableResult(ResultSet rs, String columnName)
            throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public Set<String> getNullableResult(ResultSet rs, int columnIndex)
            throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public Set<String> getNullableResult(CallableStatement cs, int columnIndex)
            throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private Set<String> parse(String value) {
        if (value == null || value.isEmpty()) {
            return Collections.emptySet();
        }
        return Arrays.stream(value.split(","))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
```

处理器至少需要重写四个方法：

| 方法 | 调用时机 | 本例的职责 |
| --- | --- | --- |
| `setNonNullParameter` | `INSERT`、`UPDATE` 或查询条件绑定非空参数时 | 把 `Set<String>` 序列化后调用 `PreparedStatement#setString` |
| `getNullableResult(ResultSet, String)` | 按列名读取查询结果时 | 读取字符串并反序列化 |
| `getNullableResult(ResultSet, int)` | 按列下标读取查询结果时 | 读取字符串并反序列化 |
| `getNullableResult(CallableStatement, int)` | 读取存储过程输出参数时 | 读取字符串并反序列化 |

三个读取方法不能只保留其中一个。MyBatis 会根据映射与 JDBC 场景选择不同签名；统一委托给 `parse` 可以保证三种入口的规则一致。`BaseTypeHandler` 会在参数为 `null` 时走框架的空值处理，因此 `setNonNullParameter` 只处理非空值。

### 4.3 扩展为其他集合元素类型

若存储格式仍是逗号分隔，只是元素改为整数，可以复用集合基类，并在子类构造器中声明集合和元素类型：

```java
public final class IntegerListTypeHandler
        extends CollectionTypeHandler<List<Integer>> {

    public IntegerListTypeHandler() {
        super(List.class, Integer.class);
    }
}
```

这种做法把“分隔、空值、集合创建”集中在父类，子类只描述目标类型。前提是元素文本可以稳定地转换为对应 Java 类型；格式不同（例如 JSON、加密文本、日期格式）时，应实现与该格式匹配的独立处理器，而不是强行复用逗号分隔逻辑。

## 5. 核心知识点提炼

### `typeHandler` 同时影响读和写

它不是只用于查询结果的转换。写入时，MyBatis 调用 `setNonNullParameter` 把 Java 值绑定到 SQL 占位符；读取时，MyBatis 调用 `getNullableResult` 把列值赋给实体属性。因此，序列化和反序列化格式必须互为约定。

### `@MappedJdbcTypes` 描述 JDBC 类型

`@MappedJdbcTypes(JdbcType.VARCHAR)` 声明该处理器可处理 `VARCHAR` 类型。字段已通过 `typeHandler = ...` 显式指定时，选择该处理器主要由字段映射决定；注解仍能表达该处理器的 JDBC 预期，并可用于注册型使用场景。

实际列可使用 `TEXT`，只要读写实现采用 `getString` / `setString` 且数据库驱动支持该转换即可。若依赖自动注册而非字段显式指定，应该让 `@MappedJdbcTypes` 与真实 JDBC 类型保持一致，并在目标数据库上验证。

### 选择 `Set` 的语义

`Set` 会自动去重；常见哈希实现不保证迭代顺序。因此相同成员可能被写成不同顺序的文本，例如 `read,write` 或 `write,read`。如果字符串的稳定顺序、展示顺序或签名一致性很重要，应使用 `LinkedHashSet`、排序后再写入，或改用 `List`。

## 6. 代码重点与难点解析

以逗号分隔值保存集合的核心约束是“元素中不能出现未转义的逗号”。例如 `"a,b"` 作为一个元素写入后，会被读取为两个元素 `"a"` 与 `"b"`。当前格式没有转义规则，因此只能用于元素本身不含逗号的值。

空值语义也要提前约定：空列、空字符串、空集合是否都表示“没有成员”？教学模板将前两者读成空集合；写入空集合时得到空字符串。若业务必须区分 `null` 与空集合，应在处理器中明确保留这种差异，并配套测试。

异常策略同样属于格式契约的一部分。对于 `Integer`、日期或 JSON 等可能解析失败的元素，建议在转换失败时抛出带列值上下文的 `SQLException` 或领域异常，避免悄悄丢失数据。对权限、金额等敏感值，还应在写入前校验元素是否合法，处理器不应成为绕过业务校验的入口。

## 7. 踩坑总结与注意事项

1. **遗漏 `autoResultMap = true`。** 字段声明了 `typeHandler` 后，实体的 `@TableName` 仍需开启它，尤其是依赖 MyBatis-Plus 自动生成的查询映射时。
2. **只实现写入或只实现一个读取重载。** 这样会在另一方向或另一种 JDBC 读取路径中得到错误类型或未转换的值。
3. **存储格式没有边界。** 分隔符、空值、重复项、顺序和非法元素的处理都应先定义；不能用普通逗号格式表示包含逗号的自由文本。
4. **误以为 `Set` 保序。** 当前实现读取后创建 `HashSet`，不保留数据库文本中的元素顺序。
5. **返回的空集合可能不可变。** `Collections.emptySet()` 和 `Collections.emptyList()` 不能直接 `add`；调用方若要修改，应先复制为可变集合。
6. **把集合列用于复杂检索。** `FIND_IN_SET` 一类表达式不利于索引和可移植性。需要按成员检索时，应优先设计关联表。

建议至少覆盖以下测试：非空集合往返、空集合、`null` 列、重复元素、单元素、非法格式，以及 `selectById` 和 `updateById` 两个真实 ORM 路径。

## 8. 实际实现解析

下面的代码节选来自示例实现，已省略包声明和与转换无关的注释。它将公共逻辑放在抽象父类中：

```java
@MappedJdbcTypes(JdbcType.VARCHAR)
public abstract class CollectionTypeHandler<T> extends BaseTypeHandler<Object> {
    private final Class<? extends Collection> collectionClazz;
    private final Class<?> innerType;

    protected CollectionTypeHandler(
            Class<? extends Collection> collectionClazz, Class<?> innerType) {
        this.collectionClazz = collectionClazz;
        this.innerType = innerType;
    }

    @Override
    public void setNonNullParameter(
            PreparedStatement ps, int index, Object parameter, JdbcType jdbcType)
            throws SQLException {
        if (parameter instanceof Collection collection) {
            StringJoiner joiner = new StringJoiner(",");
            for (Object item : collection) {
                joiner.add(Convert.toStr(item));
            }
            ps.setString(index, joiner.toString());
            return;
        }
        ps.setString(index, Convert.toStr(parameter));
    }
}
```

父类接收集合实现类和元素类型。写入时遍历 `Collection`，使用 `StringJoiner` 生成逗号分隔文本，再通过 `setString` 绑定参数；非集合分支是防御性处理。这里选择 `BaseTypeHandler<Object>`，因此可复用同一套代码处理不同集合类型，但编译期对字段类型的约束会弱一些。若处理器只服务一种类型，上一节的 `BaseTypeHandler<Set<String>>` 会更明确。

读取部分集中到私有转换方法，三个 JDBC 入口全部复用它：

```java
@Override
public Object getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return convertToEntityAttribute(rs.getString(columnName));
}

@Override
public Object getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return convertToEntityAttribute(rs.getString(columnIndex));
}

@Override
public Object getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return convertToEntityAttribute(cs.getString(columnIndex));
}

private Object convertToEntityAttribute(String value) {
    if (StrUtil.isEmpty(value)) {
        if (List.class.isAssignableFrom(collectionClazz)) {
            return Collections.emptyList();
        }
        if (Set.class.isAssignableFrom(collectionClazz)) {
            return Collections.emptySet();
        }
        return null;
    }

    Collection result;
    if (List.class.isAssignableFrom(collectionClazz)) {
        result = new ArrayList();
    } else if (Set.class.isAssignableFrom(collectionClazz)) {
        result = new HashSet();
    } else {
        return null;
    }
    for (String item : value.split(",")) {
        result.add(Convert.convert(innerType, item));
    }
    return result;
}
```

该实现在空字符串或 `null` 时，为 `List` / `Set` 分别返回对应的空集合；非空值按逗号拆分后，调用转换工具把每个元素转为 `innerType`，最后放入 `HashSet` 或 `ArrayList`。除这两类外，源码会返回 `null`。因此它既能支持字符串集合，也能在子类传入 `Integer.class` 等类型后支持数值集合。

具体字符串集合处理器只需要提供构造参数：

```java
public class CollectionStrTypeHandler extends CollectionTypeHandler<Set<String>> {
    public CollectionStrTypeHandler() {
        super(Set.class, String.class);
    }
}
```

业务层构造 `HashSet<String>` 后调用通用更新方法时，MyBatis-Plus 将其交给该处理器写入 `default_permissions`；通过通用查询方法读回实体时，列中的字符串又会被还原为 `Set<String>`。业务层不需要手写 `split` 或 `join`。

## 9. 总结

`@TableField(typeHandler = ...)` 把字段级的持久化格式集中到一个明确的边界：实体保持领域类型，数据库保持可存储类型。正确实现的重点是同时覆盖一次非空写入和三个可空读取入口，并让读写格式、空值语义和异常策略保持一致。

对于本例的逗号分隔集合，`@TableName(autoResultMap = true)`、显式字段处理器和集合子类的构造参数共同完成了映射。它适合简单且格式受控的集合字段；当数据需要复杂查询、保序或容纳任意文本时，应重新评估为关联表或 JSON 类型，而不是继续扩展分隔符规则。
