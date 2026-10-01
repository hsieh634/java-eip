package com.renewflow.eip.attendance;

/** 申請狀態。只能從 PENDING 轉到其他三種之一，且只轉一次（由條件式 UPDATE 保證）。 */
public enum AttendanceRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED
}
