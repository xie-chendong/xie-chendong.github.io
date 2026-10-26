package com.chendong.zfilestudy.core.exception.core;

import com.chendong.zfilestudy.core.exception.ErrorCode;
import lombok.Getter;

/**\
 * @Author: xiechendong
 * @Description: 业务异常，继承自 RuntimeException，该类异常返回给用户处理，非系统异常
 * @Date: 2026/04/20 19:49
 */
@Getter
public class BizException extends RuntimeException{
    private static final long serialVersionUID = 1L;

    // 错误码
    private String code;

    // 是否打印堆栈信息，默认不打印
    public boolean printExceptionStackTrace(){
        return false;
    }

    public BizException(){
        super();
    }

    // 使用指定的Throwable构造异常
    public BizException(Throwable  cause){
        super(cause);
    }

    // 使用指定的错误信息构造异常
    public BizException(String message){
        super(message);
    }

    // 使用指定的错误码和错误信息构造异常
    public BizException(String code,String message){
        super(message);
        this.code = code;
    }

    //使用错误信息和Throwable构造异常
    public BizException(String message,Throwable cause){
        super(message,cause);
    }

    //使用错误码、错误信息和Throwable构造异常
    public BizException(String code,String message,Throwable cause){
        super(message,cause);
        this.code = code;
    }

    //使用错误码构造异常
    public BizException(ErrorCode errorCode){
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    //使用错误码和Throwable构造异常
    public BizException(ErrorCode errorCode,Throwable cause){
        super(errorCode.getMessage(),cause);
        this.code = errorCode.getCode();
    }
}
