package com.renewflow.eip.attendance;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 一筆出勤紀錄的對外表示。時間以 Instant 輸出為 ISO 8601（例如 2026-10-01T01:05:00Z），
 * 前端依台北時區顯示。不含員工 ID、內部 ID 或時間戳。
 */
public record AttendanceRecordResponse(
        LocalDate workDate,
        Instant clockInAt,
        Instant clockOutAt,
        AttendanceStatus status) {

    /** @param today 今日業務日期：用來判斷「沒下班」是進行中（今日）還是未完成（過去）。 */
    static AttendanceRecordResponse from(Attendance attendance, LocalDate today) {
        AttendanceStatus status;
        if (attendance.getClockOutAt() != null) {
            status = AttendanceStatus.COMPLETED;
        } else if (attendance.getWorkDate().equals(today)) {
            status = AttendanceStatus.WORKING;
        } else {
            status = AttendanceStatus.INCOMPLETE;
        }
        return new AttendanceRecordResponse(
                attendance.getWorkDate(), attendance.getClockInAt(), attendance.getClockOutAt(), status);
    }
}
