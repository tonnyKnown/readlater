package com.readlater.enums;

/**
 * 业务错误码
 * <p>依据 API 契约 v0.2</p>
 */
public enum ErrorCode {
    SUCCESS(0, 200, "成功"),
    PARAM_ERROR(40001, 400, "参数错误"),
    FETCH_ERROR(40002, 400, "抓取失败"),
    NOT_FOUND(40401, 404, "文章不存在"),
    RATE_LIMIT(42901, 429, "请求过于频繁，请稍后再试"),
    INTERNAL_ERROR(50001, 500, "服务内部错误");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
