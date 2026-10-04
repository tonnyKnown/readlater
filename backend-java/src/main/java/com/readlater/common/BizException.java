package com.readlater.common;

import com.readlater.enums.ErrorCode;

/**
 * 业务异常：携带错误码，由 GlobalExceptionHandler 统一转 JSON 响应
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
