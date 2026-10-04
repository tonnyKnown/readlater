package com.readlater.common;

import com.readlater.enums.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：统一输出 { code, message }，HTTP 状态与 Node 版契约对齐
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .body(Result.error(e.getErrorCode(), e.getMessage()));
    }

    /** 参数校验/解析失败 → 40001 */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<Result<Void>> handleBadRequest(Exception e) {
        return ResponseEntity.status(ErrorCode.PARAM_ERROR.getHttpStatus())
                .body(Result.error(ErrorCode.PARAM_ERROR, e.getMessage()));
    }

    /** /api/** 未匹配到资源 → 40401；非 /api/** 的 404 由静态资源 fallback 处理，不会走到这里 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.getHttpStatus())
                .body(Result.error(ErrorCode.NOT_FOUND, "接口不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleInternal(Exception e) {
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(Result.error(ErrorCode.INTERNAL_ERROR, "服务内部错误"));
    }
}
