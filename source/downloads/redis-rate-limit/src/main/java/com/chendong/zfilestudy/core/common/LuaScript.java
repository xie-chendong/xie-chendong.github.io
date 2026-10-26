package com.chendong.zfilestudy.core.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * lua脚本
 *
 * @author xiechendong
 * @date 2026/08/30 17:40:43
 */
@Configuration(proxyBeanMethods = false)
public class LuaScript {
    /**
     * 接口限流脚本
     *
     * @return 字符串
     * @author xiechendong
     * @date 2026/08/30 17:41:03
     */
    @Bean
    public RedisScript<Long> apiLimitScript(){
        StringBuilder lua = new StringBuilder();
        // 第一个键值即为当前请求在 redis 中对应的 key
        lua.append("local key = KEYS[1]").append("\n");
        // 第一个参数为滑动窗口左起始位置
        lua.append("local startTs = tonumber(ARGV[1])").append("\n");
        // 第二个参数为滑动窗口右结束位置
        lua.append("local endTs = tonumber(ARGV[2])").append("\n");
        // 第二个参数为接口最大访问次数
        lua.append("local maxCount = tonumber(ARGV[3])").append("\n");
        // 第三个参数为滑动窗口时间
        lua.append("local seconds = tonumber(ARGV[4])").append("\n");
        // 第四个参数为生成的member：uuid
        lua.append("local uuid = ARGV[5]").append("\n");

        // 1、首先删除窗口外所有的请求
        lua.append("redis.call('ZREMRANGEBYSCORE', key, 0, startTs)").append("\n");
        // 2、然后判断窗口内的请求数量是否大于等于最大访问次数
        lua.append("local count = redis.call('ZCARD', key)").append("\n");
        lua.append("if count >= maxCount then").append("\n");
        lua.append("return 1").append("\n");
        lua.append("end").append("\n");
        // 3、如果小于最大访问次数，则将当前请求加入滑动窗口
        lua.append("redis.call('ZADD', key, endTs, uuid)").append("\n");
        // 4、最后设置滑动窗口过期时间
        lua.append("redis.call('EXPIRE', key, seconds)").append("\n");
        // 5、返回 0 表示请求成功
        lua.append("return 0").append("\n");

        return RedisScript.of(lua.toString(), Long.class);
    }
}
