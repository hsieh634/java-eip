package com.renewflow.eip.attendance;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 今日出勤狀態。沒有紀錄時也有明確的 NOT_STARTED（不是 404）。
 * unfinishedBefore：今日之前最近一筆未下班的業務日期，沒有則為 null。
 */
public record TodayAttendanceResponse(
        AttendanceStatus status,
        LocalDate workDate,
        String timeZone,
        Instant clockInAt,
        Instant clockOutAt,
        LocalDate unfinishedBefore) {
}
