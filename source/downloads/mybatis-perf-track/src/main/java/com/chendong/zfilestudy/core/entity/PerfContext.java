package com.chendong.zfilestudy.core.entity;

import com.chendong.zfilestudy.core.annotation.PerfTrack;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 性能计算上下文
 *
 * @author xiecd
 * @date 2026/07/01 09:48:24
 */
@Data
public class PerfContext {
    // 方法名
    private String methodName;
    // 是否打印sql明细
    private final boolean isSqlDetail;
    // 慢sql阈值
    private final long slowSqlThreshold;

    // 执行sql总耗时
    private long sqlTotalCost;
    // 执行sql数量
    private int sqlCount;
    // sql记录
    private List<SqlRecord> sqlRecords = new ArrayList<>();

    // 不可变方法，自动生成构造方法与 getter 方法等
    public static record SqlRecord(String sql, long cost){}

    /**
     * 构造方法
     *
     * @param methodName 方法名称
     * @param perfTrack  性能跟踪注解
     * @author xiecd
     * @date 2026/07/01 15:47:11
     */
    public PerfContext(String methodName, PerfTrack perfTrack){
        this.methodName = methodName;
        this.isSqlDetail = perfTrack.sqlDetail();
        this.slowSqlThreshold = perfTrack.slowSqlThreshold();
    }

    /**
     * 添加sql记录
     *
     * @param sql  SQL
     * @param cost 成本
     * @author xiecd
     * @date 2026/07/01 15:47:57
     */
    public void addSqlRecord(String sql, long cost){
        sqlCount++;
        sqlTotalCost += cost;
        sqlRecords.add(new SqlRecord(sql, cost));
    }
}
