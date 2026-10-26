package com.chendong.zfilestudy.core.aspect;

import com.chendong.zfilestudy.core.annotation.PerfTrack;
import com.chendong.zfilestudy.core.util.PerfTracker;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 性能追踪器切面类
 *
 * @author xiecd
 * @date 2026/07/01 09:32:49
 */
@Aspect
@Component
public class PerfTrackAspect {
    @Around("@annotation(perfTrack)")
    public Object around(ProceedingJoinPoint joinPoint, PerfTrack perfTrack) throws Throwable{
        // 获取方法名
        String methodName = joinPoint.getSignature().getName();
        // 启动性能追踪
        PerfTracker.start(methodName, perfTrack);
        long start = System.currentTimeMillis();
        // 执行方法
        try{
            return joinPoint.proceed();
        } finally {
            // 计算耗时
            long cost = System.currentTimeMillis() - start;
            // 打印性能追踪信息
            PerfTracker.end(cost);
        }
    }
}
