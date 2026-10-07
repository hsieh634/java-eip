package com.renewflow.eip.attendance;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 一筆出勤紀錄的對外表示。時間以 Instant 輸出為 ISO 8601（例如 2026-10-01T01:05:00Z），
 * 前端依台北時區顯示。同一個日期可能有多筆（已作廢的舊紀錄＋有效紀錄），所以前端以 id 當作 key。
 *
 * @param source    CLOCK（員工打卡）或 MANUAL（補登）
 * @param corrected 時間是否被更正過
 * @param version   管理員直接更正時用來偵測「資料已被改過」
 */
public record AttendanceRecordResponse(
        Long id,
        LocalDate workDate,
        Instant clockInAt,
        Instant clockOutAt,
        AttendanceStatus status,
        String source,
        boolean corrected,
        Instant voidedAt,
        long version,
        java.util.UUID requestId) {

    /** 打卡回應帶原識別碼；一般查詢沒有提交識別碼。 */
    AttendanceRecordResponse withRequestId(java.util.UUID key) {
        return new AttendanceRecordResponse(id, workDate, clockInAt, clockOutAt, status, source, corrected, voidedAt, version, key);
    }

    /** @param today 今日業務日期：用來判斷「沒下班」是進行中（今日）還是未完成（過去）。 */
    static AttendanceRecordResponse from(Attendance attendance, LocalDate today) {
        AttendanceStatus status;
        if (attendance.isVoided()) {
            status = AttendanceStatus.VOIDED;
        } else if (attendance.getClockOutAt() != null) {
            status = AttendanceStatus.COMPLETED;
        } else if (attendance.getWorkDate().equals(today)) {
            status = AttendanceStatus.WORKING;
        } else {
            status = AttendanceStatus.INCOMPLETE;
        }
        return new AttendanceRecordResponse(
                attendance.getId(), attendance.getWorkDate(), attendance.getClockInAt(), attendance.getClockOutAt(),
                status, attendance.getSource(), attendance.getCorrectedAt() != null,
                attendance.getVoidedAt(), attendance.getVersion(), null);
    }
}
