package com.chendong.zfilestudy.core.exception.status;

import com.chendong.zfilestudy.core.exception.ErrorCode;
import com.chendong.zfilestudy.core.exception.core.BizException;

/**
 * 错误请求访问异常，表示请求参数有误或者服务器无法理解，返回400状态码
 *
 * @author xiecd
 * @date 2026/07/04 22:54:54
 */
public class BadRequestAccessException extends BizException {

    public BadRequestAccessException(String message){super(ErrorCode.BIZ_ERROR.getCode(), message);}
}
