package com.chendong.zfilestudy.core.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.chendong.zfilestudy.core.annotation.ApiLimit;
import com.chendong.zfilestudy.core.constant.RedisKeyConstant;
import com.chendong.zfilestudy.core.exception.ErrorCode;
import com.chendong.zfilestudy.core.exception.core.BizException;
import com.chendong.zfilestudy.core.util.IpUtils;
import com.chendong.zfilestudy.core.util.RequestHolder;
import com.chendong.zfilestudy.core.util.stringUtil.StringUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.TimeoutUtils;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
@Slf4j
public class ApiLimitAspect {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private RedisScript<Long> redisScript;

    /**
     * 定义切点，在标注了 ApiLimit 注解的方法前执行拦截
     *
     * @author xiechendong
     * @date 2026/08/26 13:54:27
     */
    @Pointcut("@annotation(com.chendong.zfilestudy.core.annotation.ApiLimit)")
    public void apiLimit(){

    }

    /**
     * 在进入方法之前
     *
     * @author xiechendong
     * @date 2026/08/26 13:55:11
     */
    @Before("@annotation(apiLimit)")
    public void before(JoinPoint joinPoint, ApiLimit apiLimit){

        // 获取注解的属性：timeout、timeUnit、maxCount
        int timeout = apiLimit.timeout();
        TimeUnit timeUnit = apiLimit.timeUnit();
        // 计算秒数
        long seconds = TimeoutUtils.toSeconds(timeout, timeUnit);
        // 最大访问次数
        long maxCount = apiLimit.maxCount();

        // 获取当前用户请求对应的key
        String key = getApiLimitKey();
        // 获得当前时间戳
        Instant now = Instant.now();
        // 滑动窗口开始：当前时间 - 过期时间的时间戳
        long timeoutAgoMills = now.minus(timeout, toTemporalUnit(timeUnit)).toEpochMilli();
        // 滑动窗口结束：当前时间戳
        long nowMills = now.toEpochMilli();
        // redis 中的 member，不能重复故设置为 uuid
        String uuid = UUID.randomUUID().toString();

        // 获取执行 lua 脚本需要的键值和参数
        List<String> keys = Collections.singletonList(key);
        // Lua 脚本参数不能使用 RedisTemplate 的 JSON value serializer，必须传纯字符串
        Object[] args = new Object[]{
                String.valueOf(timeoutAgoMills),
                String.valueOf(nowMills),
                String.valueOf(maxCount),
                String.valueOf(seconds),
                uuid
        };

        // 执行 lua 脚本，确保相关 redis 操作的原子性
        RedisSerializer<Long> resultSerializer = new GenericToStringSerializer<>(Long.class);
        Object raw = redisTemplate.execute(
                redisScript,
                RedisSerializer.string(),
                resultSerializer,
                keys,
                args
        );
        long ret;
        if(raw instanceof Number num){
            ret = num.longValue();
        }else {
            log.error("ApiLimitAspect: lua 限流脚本执行异常");
            throw new BizException(ErrorCode.BIZ_ADMIN_ERROR);
        }
        // result = 1则超出限流，result = 0则未超出限流放行
        if(ret == 1){
            throw new BizException(ErrorCode.BIZ_ACCESS_TOO_FREQUENT);
        }
    }

    /**
     * 获取存入 redis 的接口限流 key
     *
     * @return 字符串
     * @author xiechendong
     * @date 2026/08/29 22:16:48
     */
    @NonNull
    private static String getApiLimitKey() {
        // 获得注解的接口url
        String url = RequestHolder.getRequest().getRequestURI();
        String key = "";
        // 获取当前用户id
        String loginId = null;
        if(StpUtil.isLogin()){
            loginId = StpUtil.getLoginIdAsString();
        }
        // 若为空则当前用户未登录，若为空则用 ip + 接口名 作为 redis 的 key
        if(StringUtils.isEmpty(loginId)) {
            // 获取当前用户ip
            String ip = IpUtils.getClientIp();
            key = String.format("%s:%s:%s", RedisKeyConstant.API_LIMIT_KEY_PREFIX, ip, url);
        }else{
            // 不为空则用 userId + 接口名 作为 redis 的 key
            key = String.format("%s:%s:%s", RedisKeyConstant.API_LIMIT_KEY_PREFIX, loginId, url);
        }
        return key;
    }

    /**
     * 将 TimeUnit 转换为 TemporalUnit
     *
     * @param timeUnit 时间单位
     * @return 时间单位
     * @author xiechendong
     * @date 2026/08/29 22:17:07
     */
    public static TemporalUnit toTemporalUnit(TimeUnit timeUnit){
        return switch (timeUnit){
            case NANOSECONDS -> ChronoUnit.NANOS;
            case MICROSECONDS -> ChronoUnit.MICROS;
            case MILLISECONDS -> ChronoUnit.MILLIS;
            case SECONDS -> ChronoUnit.SECONDS;
            case MINUTES -> ChronoUnit.MINUTES;
            case HOURS -> ChronoUnit.HOURS;
            case DAYS -> ChronoUnit.DAYS;
        };
    }


}
