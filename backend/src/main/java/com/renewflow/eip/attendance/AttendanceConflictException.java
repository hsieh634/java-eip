package com.renewflow.eip.attendance;

/** 與資源目前狀態衝突（對外 409）。code 是機器可讀的原因，例如 REQUEST_STALE。 */
public class AttendanceConflictException extends RuntimeException {

    private final String code;

    public AttendanceConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
