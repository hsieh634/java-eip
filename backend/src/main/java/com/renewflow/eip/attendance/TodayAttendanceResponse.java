package com.renewflow.eip.attendance;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 今日出勤狀態。沒有紀錄時也有明確的 NOT_STARTED（不是 404）。
 * unfinishedBefore：今日之前最近一筆未下班的業務日期，沒有則為 null。
 * recordId／version：今日有效紀錄的識別與版本（沒有紀錄則為 null）。員工提出申請時把它們當作「我看到的快照」送回，
 * 後端據此拒絕以過期畫面提出的申請。
 */
public record TodayAttendanceResponse(
        AttendanceStatus status,
        LocalDate workDate,
        String timeZone,
        Instant clockInAt,
        Instant clockOutAt,
        LocalDate unfinishedBefore,
        Long recordId,
        Long version) {
}
