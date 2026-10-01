package com.renewflow.eip.attendance;

/**
 * 出勤狀態。
 * <ul>
 *   <li>NOT_STARTED：今日還沒有紀錄（只出現在「今日」查詢）</li>
 *   <li>WORKING：今日已上班、尚未下班</li>
 *   <li>COMPLETED：已有上班與下班時間</li>
 *   <li>INCOMPLETE：過去日期有上班、沒有下班（只出現在歷史列表；保留原樣，不自動補下班）</li>
 * </ul>
 */
public enum AttendanceStatus {
    NOT_STARTED,
    WORKING,
    COMPLETED,
    INCOMPLETE
}
