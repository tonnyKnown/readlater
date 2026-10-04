package com.readlater.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.readlater.enums.ErrorCode;

/**
 * 统一 API 响应体
 * <p>成功 { code: 0, data: ... }；错误 { code: 错误码, message: ... }（无 data 字段，对齐 Node 版契约）</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    private final int code;
    private final T data;
    private final String message;

    private Result(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), data, null);
    }

    public static <T> Result<T> error(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), null, message);
    }

    public int getCode() {
        return code;
    }

    public T getData() {
        return data;
    }

    public String getMessage() {
        return message;
    }
}
