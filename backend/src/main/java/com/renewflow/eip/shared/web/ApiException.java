package com.renewflow.eip.shared.web;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 預期的業務拒絕（T04 起使用）：狀態碼、穩定的 code、給人看的訊息，以及必要時附加的欄位
 * （例如 DAILY_LIMIT_EXCEEDED 的分鐘數、DUPLICATE_SUBMISSION 的 entryId）。
 * 資料庫故障等其他例外不會被包裝成它，維持 500。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
