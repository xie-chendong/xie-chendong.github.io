package com.chendong.zfilestudy.core.config.mybatis;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * 自定义 Set 类型处理器, 用于处理数据库 VARCHAR 类型字段和 Java Set 类型属性之间的转换.
 * 支持字符串格式为: "[a, b, c]".
 * 由子类指定具体集合类型：Set<String>、Set<Integer>等
 */
@MappedJdbcTypes(JdbcType.VARCHAR)
public abstract class CollectionTypeHandler<T> extends BaseTypeHandler<Object> {
    /**
     * 集合类型：当子类继承CollectionTypeHandler<Set<String>>时为Set.class
     */
    private final Class<? extends Collection> collectionClazz;
    /**
     * 内部类型：当子类继承CollectionTypeHandler<Set<String>>时为String.class
     */
    private final Class<?> innerType;
    /**
     * 构造函数：运行时解析子类的泛型，解析的结果存入 collectionClazz 字段中
     *
     * @author xiecd
     * @date 2026/07/03 20:11:09
     */
    protected CollectionTypeHandler(Class<? extends Collection> collectionClazz, Class<?> innerType){
        this.collectionClazz=collectionClazz;
        this.innerType=innerType;
    }

    /**
     * 执行数据库写入，将Java集合转成字符串存入数据库
     *
     * @param ps        预编译 SQL 对象，用于给 SQL 语句中的 ? 占位符赋值
     * @param i         当前要复制的第几个问号占位符
     * @param parameter 实体类当前字段的Java值
     * @param jdbcType  jdbc字段类型
     * @author xiecd
     * @date 2026/07/03 19:36:39
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType) throws SQLException {
        // 1、判断是否是集合类型
        if(parameter instanceof Collection collection){
            // 2、将集合中的对象转成以逗号分隔的字符串
            StringJoiner joiner = new StringJoiner(",");
            for(Object o : collection){
                joiner.add(Convert.toStr(o));
            }
            // 3、给 SQL 语句中的 ? 占位符赋值
            ps.setString(i,joiner.toString());
        }else{
            ps.setString(i,Convert.toStr(parameter));
        }
    }

    /**
     * 以下三种为读取数据库的方法：将数据库中的字符串转成实体类的属性
     */
    @Override
    public Object getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String str = rs.getString(columnName);
        return convertToEntityAttribute(str);
    }

    @Override
    public Object getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String str = rs.getString(columnIndex);
        return convertToEntityAttribute(str);
    }

    @Override
    public Object getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String str = cs.getString(columnIndex);
        return convertToEntityAttribute(str);
    }

    /**
     * 实体转换函数，传入以逗号分隔的字符串返回对应的集合
     *
     * @param str 字符串
     * @return {@code Object }
     * @author xiecd
     * @date 2026/07/03 20:45:10
     */
    private Object convertToEntityAttribute(String str){
        if(StrUtil.isEmpty(str)){
            if(List.class.isAssignableFrom(collectionClazz)){
                // 如果是 List 类型的子类或实现类，则返回空 List
                return Collections.emptyList();
            }else if(Set.class.isAssignableFrom(collectionClazz)){
                // 如果是 Set 类型的子类或实现类，则返回空 Set
                return Collections.emptySet();
            }else{
                return null;
            }
        }

        // 初始化返回集合
        Collection collection;
        if(List.class.isAssignableFrom(collectionClazz)){
            collection = new ArrayList();
        }else if (Set.class.isAssignableFrom(collectionClazz)){
            collection = new HashSet();
        }else{
            return null;
        }

        // 将以逗号分隔的字符串转成数组
        String[] data = str.split(",");
        for(String s : data){
            // 将数组中的字符串转成对应类型的对象
            Object o = Convert.convert(innerType,s);
            // 将对象添加到集合中
            collection.add(o);
        }

        return collection;
    }
}
