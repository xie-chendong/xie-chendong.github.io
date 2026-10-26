package com.chendong.zfilestudy.core.exception.status;

import com.chendong.zfilestudy.core.exception.core.BizException;

/**
 * 未授权访问异常，表示用户未进行身份认证
 * @Author: xiechendong
 * @Description:
 * @Date: 2026/04/20 19:43
 */
public class UnauthorizedAccessException extends BizException {

    public UnauthorizedAccessException(String message){super(message);}
}
