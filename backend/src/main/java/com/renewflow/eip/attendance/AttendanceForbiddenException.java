package com.renewflow.eip.attendance;

/** 已驗證但不允許的操作（對外 403），例如管理員審核自己的申請。 */
public class AttendanceForbiddenException extends RuntimeException {

    private final String code;

    public AttendanceForbiddenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
