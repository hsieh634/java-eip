package com.renewflow.eip.attendance;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 要寫入歷程的一筆內容。操作者與時間由後端 Service 填入（不接受前端指定）。
 *
 * @param afterVoided null＝這個操作沒有修改出勤（提出、撤回、退回）
 */
record AuditEntry(
        AttendanceOperation operation,
        Long employeeId,
        LocalDate workDate,
        Long requestId,
        AttendanceRequestType requestType,
        Long attendanceId,
        Long targetVersion,
        String reason,
        Instant originalIn,
        Instant originalOut,
        Instant requestedIn,
        Instant requestedOut,
        Instant afterIn,
        Instant afterOut,
        Boolean afterVoided,
        String result,
        Long actorId,
        Instant occurredAt) {
}
