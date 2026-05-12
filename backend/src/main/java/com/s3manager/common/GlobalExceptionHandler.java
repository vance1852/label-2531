package com.s3manager.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public org.springframework.http.ResponseEntity<R<Void>> handleBizException(BizException e) {
        log.warn("Business exception: code={}, msg={}", e.getCode(), e.getMessage());
        org.springframework.http.HttpStatus httpStatus = mapCodeToHttpStatus(e.getCode());
        return new org.springframework.http.ResponseEntity<>(R.fail(e.getCode(), e.getMessage()), httpStatus);
    }

    private org.springframework.http.HttpStatus mapCodeToHttpStatus(int code) {
        return switch (code) {
            case 400 -> org.springframework.http.HttpStatus.BAD_REQUEST;
            case 401 -> org.springframework.http.HttpStatus.UNAUTHORIZED;
            case 403 -> org.springframework.http.HttpStatus.FORBIDDEN;
            case 404 -> org.springframework.http.HttpStatus.NOT_FOUND;
            case 409 -> org.springframework.http.HttpStatus.CONFLICT;
            default -> org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", msg);
        return R.fail(400, msg);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleBind(BindException e) {
        String msg = e.getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return R.fail(400, msg);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMaxUpload(MaxUploadSizeExceededException e) {
        log.warn("File too large: {}", e.getMessage());
        return R.fail(400, "文件大小超出限制");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleException(Exception e) {
        log.error("Unexpected error", e);
        return R.fail(500, "服务器内部错误: " + e.getMessage());
    }
}
