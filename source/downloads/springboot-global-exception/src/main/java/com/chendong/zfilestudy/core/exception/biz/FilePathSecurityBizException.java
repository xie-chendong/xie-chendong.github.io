package com.chendong.zfilestudy.core.exception.biz;

import com.chendong.zfilestudy.core.exception.ErrorCode;
import com.chendong.zfilestudy.core.exception.core.BizException;
import lombok.Getter;

/**
 * 文件路径非法异常
 *
 * @author xiechendong
 * @date 2026/09/16 17:39:41
 */
@Getter
public class FilePathSecurityBizException extends BizException {

    private final String path;

    public FilePathSecurityBizException(String path) {
        super(ErrorCode.BIZ_FILE_PATH_ILLEGAL);
        this.path = path;
    }

}
