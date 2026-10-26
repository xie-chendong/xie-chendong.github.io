package com.chendong.zfilestudy.core.exception.biz;

import com.chendong.zfilestudy.core.exception.core.BizException;
import lombok.Getter;

/**
 * 初始化存储源异常
 *
 * @author xiechendong
 * @date 2026/09/16 17:39:34
 */
@Getter
public class InitializeStorageSourceBizException extends BizException {

    private final Long storageId;

    public InitializeStorageSourceBizException(String message, Long storageId) {
        super(message);
        this.storageId = storageId;
    }

    public InitializeStorageSourceBizException(String code, String message, Long storageId, Throwable cause) {
        super(code, message, cause);
        this.storageId = storageId;
    }
}
