package com.chendong.zfilestudy.core.config.mybatis;

import com.chendong.zfilestudy.core.entity.PerfContext;
import com.chendong.zfilestudy.core.util.PerfTracker;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;

@Intercepts({
        @Signature(type = Executor.class,method = "query",args = {MappedStatement.class,Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class,method = "update",args = {MappedStatement.class,Object.class})
})
@Component
public class SqlPerfInterceptor implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 获取当前执行上下文
        PerfContext context = PerfTracker.current();
        if(context == null){
            // 若不存在直接执行方法
            return invocation.proceed();
        }
        long start = System.currentTimeMillis();
        try {
            return invocation.proceed();
        }finally {
            long cost = System.currentTimeMillis() - start;
            // 获取当前执行sql的完整元信息
            MappedStatement mappedStatement = (MappedStatement) invocation.getArgs()[0];
            // 获取当前执行sql的参数
            Object parameter = invocation.getArgs().length > 1 ? invocation.getArgs()[1] : null;
            // 根据元信息与参数获取本次实际要执行的sql信息
            String sql = mappedStatement.getBoundSql(parameter).getSql();
            // 记录sql
            PerfTracker.recordSql(sql, cost);
        }
    }
}
