package com.renewflow.eip.attendance;

/** 日期範圍不合法（對外是 400 INVALID_DATE_RANGE）。 */
public class InvalidDateRangeException extends RuntimeException {

    public InvalidDateRangeException(String message) {
        super(message);
    }
}
