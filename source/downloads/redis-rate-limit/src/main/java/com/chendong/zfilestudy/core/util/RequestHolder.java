package com.chendong.zfilestudy.core.util;

import cn.hutool.extra.servlet.JakartaServletUtil;
import com.chendong.zfilestudy.core.constant.ZFileHttpHeaderConstant;
import com.chendong.zfilestudy.core.util.stringUtil.StringUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Objects;

public class RequestHolder {
    /**
     * 获取当前线程发送的请求HttpServletRequest
     *
     * @return {@link HttpServletRequest }
     */
    public static HttpServletRequest getRequest() {
        return ((ServletRequestAttributes) Objects.requireNonNull(RequestContextHolder.getRequestAttributes())).getRequest();
    }

    /**
     * 判断当前请求是否为axios请求（axios请求出现在不刷新页面能与服务器交互的场景，如前端调用后端api、异步获取数据等）
     *
     * @return boolean
     */
    public static boolean isAxiosRequest() {
        HttpServletRequest request = RequestHolder.getRequest();
        String axioRequest = JakartaServletUtil.getHeaderIgnoreCase(request, ZFileHttpHeaderConstant.AXIOS_REQUEST);
        return StringUtils.isNotEmpty(axioRequest);
    }

    /**
     * 获取当前线程返回的响应HttpServletResponse
     *
     * @return {@link HttpServletRequest }
     */
    public static HttpServletResponse getResponse() {
        return ((ServletRequestAttributes) Objects.requireNonNull(RequestContextHolder.getRequestAttributes())).getResponse();
    }

    /**
     * 获取请求头中的 Axios-From 字段
     *
     * @return 字符串
     * @author xiechendong
     * @date 2026/06/21 19:05:28
     */
    public static String getAxiosFrom() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return null;
        }
        return JakartaServletUtil.getHeaderIgnoreCase(getRequest(), ZFileHttpHeaderConstant.AXIOS_FROM);
    }

    /**
     * 获取reuqest中的后端服务地址
     *
     * @return 字符串
     * @author xiechendong
     * @date 2026/06/21 19:17:21
     */
    public static String getRequestServerAddress() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return null;
        }
        HttpServletRequest request = getRequest();

        // 主机名
        String forwardedHost = JakartaServletUtil.getHeaderIgnoreCase(request, "X-Forwarded-Host");
        // 端口号
        String forwardedPort = JakartaServletUtil.getHeaderIgnoreCase(request, "X-Forwarded-Port");
        // 协议
        String forwardedProto = JakartaServletUtil.getHeaderIgnoreCase(request, "X-Forwarded-Proto");

        // 确定协议，若proto为空则Servlet请求对象里的：request.getScheme()
        String scheme = StringUtils.isBlank(forwardedProto) ? request.getScheme() : forwardedProto;
        // 确定域名，若host为空则使用 Host 头，若 Host 头为空则使用 Servlet 请求对象里的：request.getServerName()
        String serverName;
        String hostHeader = StringUtils.isNotBlank(forwardedHost) ? forwardedHost : request.getHeader("Host");
        if (StringUtils.isNotBlank(hostHeader)) {
            // 分割端口信息
            String[] hostParts = hostHeader.split(":");
            serverName = hostParts[0];
            // 如果 Host 头包含端口信息且没有设置 X-Forwarded-Port ，则使用 Host 头中的端口
            if (hostParts.length > 1 && StringUtils.isBlank(forwardedPort)) {
                forwardedPort = hostParts[1];
            }
        } else {
            serverName = request.getServerName();
        }
        // 确定端口
        String port;
        if (StringUtils.isNotBlank(forwardedPort)) {
            port = forwardedPort;
        } else if (StringUtils.isNotBlank(forwardedProto)) {
            // 如果没有设置 X-Forwarded-Port ，则使用协议的默认端口：HTTP 80，HTTPS 443
            port = "https".equalsIgnoreCase(forwardedProto) ? "443" : "80";
        } else {
            port = String.valueOf(request.getServerPort());
        }

        // 移除默认端口
        if ("443".equals(port) && "https".equalsIgnoreCase(scheme)) {
            port = "";
        }
        if ("80".equals(port) && "http".equalsIgnoreCase(scheme)) {
            port = "";
        }

        if (StringUtils.isBlank(port)) {
            return scheme + "://" + serverName;
        } else {
            return scheme + "://" + serverName + ":" + port;
        }
    }

    /**
     * 获取当前请求的 Origin 请求头
     *
     * @return 字符串
     * @author xiechendong
     * @date 2026/06/21 20:55:08
     */
    public static String getOriginAddress() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return null;
        }
        HttpServletRequest request = getRequest();
        return JakartaServletUtil.getHeaderIgnoreCase(request, HttpHeaders.ORIGIN);
    }
}
