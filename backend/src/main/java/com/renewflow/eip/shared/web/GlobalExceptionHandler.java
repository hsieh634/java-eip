package com.renewflow.eip.shared.web;

import com.renewflow.eip.attendance.AttendanceConflictException;
import com.renewflow.eip.attendance.AttendanceForbiddenException;
import com.renewflow.eip.attendance.AttendanceNotFoundException;
import com.renewflow.eip.attendance.AttendanceRuleException;
import com.renewflow.eip.attendance.InvalidDateRangeException;
import com.renewflow.eip.attendance.InvalidRequestException;
import com.renewflow.eip.identity.AuthenticationUnavailableException;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> onTypeMismatch(MethodArgumentTypeMismatchException e) {
        // 例如日期格式不是 YYYY-MM-DD；只回參數名稱，不回傳使用者輸入的值
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "參數格式不正確：" + e.getName());
    }

    @ExceptionHandler(InvalidDateRangeException.class)
    ResponseEntity<ApiError> onInvalidDateRange(InvalidDateRangeException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", e.getMessage());
    }

    /** 輸入格式正確但不符合業務規則（未來時間、下班早於上班…）→ 400 INVALID_REQUEST。 */
    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ApiError> onInvalidRequest(InvalidRequestException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    /** 申請／審核的預期衝突（過時、已處理、重複待審…）→ 409，code 由例外指定。 */
    @ExceptionHandler(AttendanceConflictException.class)
    ResponseEntity<ApiError> onAttendanceConflict(AttendanceConflictException e) {
        return build(HttpStatus.CONFLICT, e.getCode(), e.getMessage());
    }

    /** 已驗證但不被允許（管理員審核自己的申請等）→ 403，與 Security 的 FORBIDDEN 用 code 區分。 */
    @ExceptionHandler(AttendanceForbiddenException.class)
    ResponseEntity<ApiError> onAttendanceForbidden(AttendanceForbiddenException e) {
        return build(HttpStatus.FORBIDDEN, e.getCode(), e.getMessage());
    }

    @ExceptionHandler(AttendanceNotFoundException.class)
    ResponseEntity<ApiError> onAttendanceNotFound(AttendanceNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getCode(), e.getMessage());
    }

    /** 預期的出勤業務衝突 → 409，code 為規則名稱（ALREADY_CLOCKED_IN 等）。其他例外不會走到這裡。 */
    @ExceptionHandler(AttendanceRuleException.class)
    ResponseEntity<ApiError> onAttendanceRule(AttendanceRuleException e) {
        return build(HttpStatus.CONFLICT, e.getRule().name(), e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> onUnreadable(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "請求內容無法解析");
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiError> onBadCredentials(InvalidCredentialsException e) {
        return build(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "帳號或密碼錯誤");
    }

    @ExceptionHandler(AuthenticationUnavailableException.class)
    ResponseEntity<ApiError> onAuthenticationUnavailable(AuthenticationUnavailableException e) {
        // 完整原因（含底層例外）只記在伺服器日誌供排查；回應只有固定文字，不含 SQL、連線資訊或帳密
        log.error("Authentication backend failure", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "系統發生錯誤，請稍後再試");
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
