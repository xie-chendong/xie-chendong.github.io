package com.chendong.zfilestudy.core.config.mybatis;

import java.util.Collection;
import java.util.Set;

/**
 * 集合转字符串类型处理程序
 *
 * @author xiecd
 * @date 2026/07/03 20:41:41
 */
public class CollectionStrTypeHandler extends CollectionTypeHandler<Set<String>>{
    /**
     * 构造函数：子类显式指定集合类型
     *
     * @author xiecd
     * @date 2026/07/03 20:11:09
     */
    public CollectionStrTypeHandler() {
        super(Set.class,String.class);
    }
}
