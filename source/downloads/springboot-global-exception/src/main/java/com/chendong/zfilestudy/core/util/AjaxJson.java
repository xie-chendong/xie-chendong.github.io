package com.chendong.zfilestudy.core.util;

import com.chendong.zfilestudy.core.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * ajax 请求返回 JSON 格式数据的封装
 * @Author: xiechendong
 * @Description:
 * @Date: 2026/04/19 18:10
 */
@Data
public class AjaxJson<T> implements Serializable {
    // 序列化版本号
    private static final long serialVersionUID = 1L;

    // 成功状态码
    public static final String CODE_SUCCESS = "0";

    private final String code; // 业务状态码，0 为正常，其他值均为异常，异常情况下见响应消息

    private String msg; // 响应消息

    private T data; // 响应数据

    private final Long dataCount; // 数据总条数，分页情况有效

    private String traceId; // 跟踪 ID

    public AjaxJson(String code,String message){
        if(code == null){
            code = ErrorCode.SYSTEM_ERROR.getCode();
        }
        this.code = code;
        this.msg = message;
        this.data = null;
        this.dataCount = null;
    }

    public AjaxJson(String code,String message,T data){
        this.code = code;
        this.msg = message;
        this.data = data;
        this.dataCount = null;
    }

    public AjaxJson(String code,String message,T data,Long dataCount){
        this.code = code;
        this.msg = message;
        this.data = data;
        this.dataCount = dataCount;
    }

    // 返回成功
    public static AjaxJson<Void> getSuccess(){
        return new AjaxJson<>(CODE_SUCCESS,"ok");
    }

    public static AjaxJson<Void> getSuccess(String msg){
        return new AjaxJson<>(CODE_SUCCESS,msg);
    }
    public static <T> AjaxJson<T> getSuccess(T data){
        return new AjaxJson<>(CODE_SUCCESS,"ok",data);
    }

    public static <T> AjaxJson<T> getSuccess(String msg,T data){
        return new AjaxJson<>(CODE_SUCCESS,msg,data);
    }

    public static <T> AjaxJson<T> getSuccessData(T data){
        return new AjaxJson<>(CODE_SUCCESS,"ok",data);
    }

    // 返回分页数据
    public static <T> AjaxJson<T> getPageData(Long dataCount,T data){
        return new AjaxJson<>(CODE_SUCCESS,"ok",data,dataCount);
    }

    // 返回错误
    public static AjaxJson<Void> getError(String msg){
        return new AjaxJson<>(ErrorCode.SYSTEM_ERROR.getCode(),  msg);
    }

    // 返回未登录
    public static AjaxJson<?> getUnauthorizedResult(){
        return new AjaxJson<>(ErrorCode.BIZ_UNAUTHORIZED.getCode(),  "未登录，请登陆后再次访问");
    }

    // 返回权限不足
    public static AjaxJson<?> getForbiddenResult(){
        return new AjaxJson<>(ErrorCode.NO_FORBIDDEN.getCode(),  "未授权，请登录正确权限账号再试");
    }

    // 返回未找到的
    public static AjaxJson<?> getNotFoundResult(){
        return new AjaxJson<>(ErrorCode.BIZ_NOT_FOUND.getCode(),  ErrorCode.BIZ_NOT_FOUND.getMessage());
    }

    public static AjaxJson<?> getError(String code,String msg){
        return new AjaxJson<>(code,msg);
    }
}
