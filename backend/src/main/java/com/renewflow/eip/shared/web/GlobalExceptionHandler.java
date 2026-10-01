package com.renewflow.eip.shared.web;

import com.renewflow.eip.identity.InvalidCredentialsException;
import com.renewflow.eip.identity.UnauthenticatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 把 Controller 層拋出的例外統一轉成 ApiError。Security Filter 層的 401／403 由 SecurityConfig 另外處理。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> onValidation(MethodArgumentNotValidException e) {
        // 只回「哪個欄位有問題」，不回傳使用者輸入的值（可能是密碼）
        String fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField())
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse("request");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "欄位格式不正確：" + fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> onUnreadable(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "請求內容無法解析");
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiError> onBadCredentials(InvalidCredentialsException e) {
        return build(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "帳號或密碼錯誤");
    }

    @ExceptionHandler(UnauthenticatedException.class)
    ResponseEntity<ApiError> onUnauthenticated(UnauthenticatedException e) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "請先登入");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> onNotFound(NoResourceFoundException e) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "找不到資源");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> onMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "不支援的請求方法");
    }

    /**
     * 原因：下面的萬用處理器會攔截所有例外，包含 Spring Security 的 AccessDeniedException／AuthenticationException。
     * 若不原樣丟回去，本該是 401／403 的情況會變成 500。丟回去後由 Security 的 ExceptionTranslationFilter 處理。
     */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    void rethrowSecurityExceptions(Exception e) throws Exception {
        throw e;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> onUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "系統發生錯誤，請稍後再試");
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }
}
