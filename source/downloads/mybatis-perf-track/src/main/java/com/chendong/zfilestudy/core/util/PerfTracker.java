package com.chendong.zfilestudy.core.util;

import com.chendong.zfilestudy.core.annotation.PerfTrack;
import com.chendong.zfilestudy.core.entity.PerfContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 性能跟踪器
 *
 * @author xiechendong
 * @date 2026/06/26 19:43:32
 */
@Slf4j
public class PerfTracker {
    private static final ThreadLocal<PerfContext> CONTEXT = new ThreadLocal<>();

    /**
     * 开始性能追踪
     *
     * @param methodName 方法名称
     * @param perfTrack  性能跟踪
     * @author xiecd
     * @date 2026/07/01 18:36:57
     */
    public static void start(String methodName, PerfTrack perfTrack){
        // 设置当前线程的性能追踪上下文
        CONTEXT.set(new PerfContext(methodName,perfTrack));
    }

    /**
     * 获取当前线程的性能追踪上下文
     *
     * @return {@code PerfContext }
     * @author xiecd
     * @date 2026/07/01 18:37:18
     */
    public static PerfContext current(){
        return CONTEXT.get();
    }

    /**
     * 获取当前线程的性能追踪上下文并记录sql
     *
     * @param sql  SQL
     * @param cost 成本
     * @author xiecd
     * @date 2026/07/01 18:55:16
     */
    public static void recordSql(String sql, long cost){
        PerfContext context = CONTEXT.get();
        if(context != null){
            context.addSqlRecord(sql, cost);
        }
    }

    /**
     * 结束性能追踪
     *
     * @param cost 成本
     * @author xiecd
     * @date 2026/07/01 22:50:21
     */
    public static void end(long cost){
        PerfContext context = CONTEXT.get();
        if(context == null){
            CONTEXT.remove();
            return;
        }
        boolean isSlow = context.getSlowSqlThreshold() <= context.getSqlTotalCost();
        if(isSlow){
            log.warn("【性能监控】method={} methodCost={}ms sqlTotalCost={}ms sqlCount={} 超阈值{}",
                    context.getMethodName(),cost,context.getSqlTotalCost(),context.getSqlCount(),context.getSlowSqlThreshold());
        }else{
            log.info("【性能监控】method={} methodCost={}ms sqlTotalCost={}ms sqlCount={} 性能正常",
                    context.getMethodName(),cost,context.getSqlTotalCost(),context.getSqlCount());
        }
        if(context.isSqlDetail()){
            // 如果当前方法需要打印sql明细
            for(int i = 0;i < context.getSqlRecords().size(); i++){
                log.info("【SQL明细】SQL[{}] cost={}ms sql={}",i+1,context.getSqlRecords().get(i).cost(),context.getSqlRecords().get(i).sql());
            }
        }
        CONTEXT.remove();
    }
}
