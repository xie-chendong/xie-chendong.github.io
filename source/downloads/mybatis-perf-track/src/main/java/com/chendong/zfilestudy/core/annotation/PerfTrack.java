package com.chendong.zfilestudy.core.annotation;

import lombok.Data;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自定义性能追踪注解
 *
 * @author xiechendong
 * @date 2026/06/26 20:05:26
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PerfTrack {
    /**
     * 是否打印sql明细
     */
    boolean sqlDetail() default false;

    /**
     * 慢SQL阈值，单位：毫秒
     * 默认500ms
     */
    long slowSqlThreshold() default 500;
}
