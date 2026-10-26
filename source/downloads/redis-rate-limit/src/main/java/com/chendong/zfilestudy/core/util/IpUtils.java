package com.chendong.zfilestudy.core.util;

import cn.hutool.extra.servlet.JakartaServletUtil;
import jakarta.servlet.http.HttpServletRequest;

/**
 * ip工具类
 *
 * @author xiecd
 * @date 2026/08/27 01:24:19
 */
public class IpUtils {

    public static String getClientIp(){
        HttpServletRequest request = RequestHolder.getRequest();
        return JakartaServletUtil.getClientIP(request);
    }
}
