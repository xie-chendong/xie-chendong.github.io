package com.chendong.zfilestudy.core.util.charUtil;

import jakarta.annotation.Nullable;
import org.apache.commons.lang3.ArrayUtils;

/**
 * @Author: xiechendong
 * @Description: 字符串工具类
 * @Date: 2026/04/26 20:28
 */
public class CharSequenceUtil implements CharPool{
    /**
     * 判断字符串是否为空：null 或 ""
     * @param cs：可以是 String 或 StringBuilder 或 StringBuffer
     * @return boolean
     */
    public static boolean isEmpty(final @Nullable CharSequence cs){
        return cs == null || cs.isEmpty();
    }

    /**
     * 判断字符串是否为空白：null 或 "" 或 " " 或 "\t" 或 "\n" 或 "\r" 或 "\t\n"
     *
     * @param cs
     * @return boolean
     * @author xiechendong
     * @date 2026/06/21 18:53:50
     */
    public static boolean isBlank(final @Nullable CharSequence cs){
        final int strLen = cs == null ? 0 : cs.length();
        if(strLen == 0){
            return true;
        }
        for(int i = 0; i < strLen; i++){
            // 如果当前字符串有至少一个非空白字符，则返回 false
            if(!Character.isWhitespace(cs.charAt(i))){
                return false;
            }
        }
        return true;
    }

    /**
     * 判断字符串是否不为空
     *
     * @param cs
     * @return boolean
     * @author xiechendong
     * @date 2026/06/21 19:16:05
     */
    public static boolean isNotEmpty(final @Nullable CharSequence cs){
        return !isEmpty(cs);
    }

    /**
     * 判断字符串是否不为空白
     *
     * @param cs
     * @return boolean
     * @author xiechendong
     * @date 2026/06/21 19:02:45
     */
    public static boolean isNotBlank(final @Nullable CharSequence cs){
        return !isBlank(cs);
    }


    /**
     * 第一个非null，若给定的数组为空或数组中元素都为空，则返回null
     *
     * @param strs strs
     * @return <T>
     * @author xiechendong
     * @date 2026/06/21 18:26:30
     */
    public static <T extends CharSequence> T firstNonNull(T... strs){
        if(ArrayUtils.isNotEmpty(strs)){
            for(T str : strs){
                if(str != null){
                    return str;
                }
            }
        }
        return null;
    }

    /**
     * 比较两个字符串是否相等
     *
     * @param cs1 cs1
     * @param cs2 cs2
     * @return boolean
     * @author xiecd
     * @date 2026/08/03 02:10:32
     */
    public static boolean equals(final @Nullable CharSequence cs1, final @Nullable CharSequence cs2){
        if(cs1 == cs2){
            return true;
        }
        if (cs1 == null || cs2 == null){
            return false;
        }
        if(cs1.length() != cs2.length()){
            return false;
        }
        // 如果两个字符串都是 String 类型，则直接调用 String 的 equals 方法
        if(cs1 instanceof String && cs2 instanceof String){
            return cs1.equals(cs2);
        }
        // 否则逐个比较字符
        for(int i = 0;i<cs1.length();i++){
            if(cs1.charAt(i) != cs2.charAt(i)){
                return false;
            }
        }
        return true;
    }
}
