package com.chendong.zfilestudy.core.util.stringUtil;

import cn.hutool.cache.CacheUtil;
import cn.hutool.cache.impl.LRUCache;
import cn.hutool.core.net.URLEncodeUtil;
import com.chendong.zfilestudy.core.util.charUtil.CharPool;
import com.chendong.zfilestudy.core.util.charUtil.CharSequenceUtil;

import java.nio.charset.StandardCharsets;

public class StringUtils extends CharSequenceUtil implements StrPool, CharPool {


    /**
     * url协议头分隔符
     */
    public static final String PROTOCOL_MARKER = "://";
    /**
     * url缓存，缓存最近1000个处理了重复分隔符的url，避免多次处理重复导致性能问题
     */
    private static final LRUCache<String, String> CACHE = CacheUtil.newLRUCache(1000);

    /**
     * 路径拼接
     * 拼接URL成 "/str1/str2" 的格式，并去除重复的分隔符'/'
     * @param strs strs
     * @return 字符串
     * @author xiechendong
     * @date 2026/06/21 18:15:22
     */
    public static String pathConcat(String... strs){
        StringBuilder sb = new StringBuilder(SLASH_STRING);
        for(int i = 0;i<strs.length;i++){
            String str = strs[i];
            if(isEmpty(str)){
                // 如果字符串为 null 或 "" 则跳过
                continue;
            }
            // 否则进行拼接
            sb.append(str);
            if(i != strs.length - 1){
                sb.append(SLASH_STRING);
            }
        }
        return removeDuplicateSlashes(sb.toString());
    }

    /**
     * 去除url中所有重复的分隔符 "/"，如果存在协议头也需要对协议头的重复分隔符进行处理
     * @param path
     * @return {@link String }
     */
    public static String removeDuplicateSlashes(String path){
        if(isEmpty(path)){
            return path;
        }
        // 从缓存中获取，如果缓存不存在则执行处理并添加缓存
        return CACHE.get(path, false, () -> {
            StringBuilder sb = new StringBuilder();
            int protocolMarkerIndex = path.indexOf(PROTOCOL_MARKER);

            int pathStartIndex = 0;
            // 1、如果存在协议头，若协议头前后存在分隔符，则去除
            if(protocolMarkerIndex != -1){
                // 获取协议头的起始下标
                int protocolStartIndex = 0;
                while(protocolStartIndex < protocolMarkerIndex && path.charAt(protocolStartIndex) == SLASH_CHAR){
                    protocolStartIndex++;
                }
                // 获取协议头的结束下标
                int protocolEndIndex = protocolMarkerIndex - 1;
                while(protocolEndIndex > protocolStartIndex && path.charAt(protocolEndIndex) == SLASH_CHAR){
                    protocolEndIndex--;
                }
                // 添加协议头（append 的 end 下标排他，需 +1 才能包含协议名最后一个字符）
                sb.append(path, protocolStartIndex, protocolEndIndex + 1);
                sb.append(PROTOCOL_MARKER);
                // 获取路径的非分隔符'/'起始下标
                while(pathStartIndex < path.length() && path.charAt(pathStartIndex) == SLASH_CHAR){
                    pathStartIndex++;
                }
                pathStartIndex = protocolMarkerIndex + PROTOCOL_MARKER.length();
            }
            // 2、去除路径中的重复分隔符
            if(pathStartIndex < path.length()){
                // "://"后的非'/'开始字符
                char currentChar = path.charAt(pathStartIndex);
                sb.append(currentChar);
                char lastChar = currentChar;
                for(int i = pathStartIndex + 1;i<path.length();i++){
                    currentChar = path.charAt(i);
                    // 如果当前字符为'/'且上一个字符也为'/'，则跳过
                    if(currentChar != SLASH_CHAR || lastChar != SLASH_CHAR){
                        sb.append(currentChar);
                        lastChar = currentChar;
                    }
                }
            }
            return sb.toString();
        });
    }

    /**
     * 全部编码忽略斜杠
     *
     * @param path 路径
     * @return {@code String }
     * @author xiecd
     * @date 2026/09/17 01:03:03
     */
    public static String encodeAllIgnoreSlash(String path) {
        if(StringUtils.isEmpty(path)) {
            return path;
        }
        StringBuilder sb = new StringBuilder();
        int size = path.length();
        int left = 0;
        while(left < size) {
            // 找到下一个斜杠
            int right = path.indexOf(SLASH_CHAR, left);
            if(right == -1) {
                // 如果后面没有斜杠，则截取到末尾
                String segment = path.substring(left);
                sb.append(URLEncodeUtil.encode(segment, StandardCharsets.UTF_8));
                break;
            }
            // 获取[left, right) 下一个斜杠前的内容
            String segment = path.substring(left,right);
            sb.append(URLEncodeUtil.encode(segment, StandardCharsets.UTF_8));
            // 斜杠直接追加
            sb.append(SLASH_CHAR);
            // 指针移动到下一位
            left = right + 1;
        }
        return sb.toString();
    }
}
