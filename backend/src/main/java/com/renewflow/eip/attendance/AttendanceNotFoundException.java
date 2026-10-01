package com.renewflow.eip.attendance;

/** 資源不存在，或不屬於呼叫者（對外 404；不洩漏別人的資料是否存在）。 */
public class AttendanceNotFoundException extends RuntimeException {

    private final String code;

    public AttendanceNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
