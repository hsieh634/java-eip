package com.renewflow.eip.attendance;

/** 歷程中的操作類型。 */
public enum AttendanceOperation {
    REQUEST_SUBMITTED,
    REQUEST_CANCELLED,
    REQUEST_APPROVED,
    REQUEST_REJECTED,
    DIRECT_CORRECTION,
    DIRECT_VOID
}
