package com.renewflow.eip.leave;

import com.renewflow.eip.leave.LeaveEnums.LeaveStatus;
import com.renewflow.eip.leave.LeaveEnums.LeaveType;
import com.renewflow.eip.leave.LeaveEnums.StatusFilter;
import com.renewflow.eip.shared.web.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 請假的正規化規則與錯誤（STMAX 示範規則／本批工程假設，不是正式人事制度）。
 * 時段：同一個台北業務日期內 [開始, 結束)、半小時刻度、結束晚於開始；分鐘數由這裡計算，不接受前端指定。
 */
final class LeaveRules {

    static final int STEP_MINUTES = 30;
    static final int REASON_MAX = 500;
    /** 日期限制在今天前後 365 天內（A-81）：允許補申請，也擋下明顯打錯的年份。 */
    static final int DATE_WINDOW_DAYS = 365;
    /** 清單的日期範圍上限（A-86）。 */
    static final int MAX_RANGE_DAYS = 366;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private LeaveRules() {
    }

    /** 一份正規化後的提交內容（requestId 重送時以它比對）。 */
    record Submission(LeaveType leaveType, LocalDate workDate, LocalTime startTime, LocalTime endTime, int minutes, String reason) {
    }

    static Submission normalize(LeaveType type, LocalDate workDate, LocalTime start, LocalTime end, String rawReason, LocalDate today) {
        if (workDate.isBefore(today.minusDays(DATE_WINDOW_DAYS)) || workDate.isAfter(today.plusDays(DATE_WINDOW_DAYS))) {
            throw invalid("請假日期需在今天（" + today + "，台北時間）前後 " + DATE_WINDOW_DAYS + " 天內");
        }
        if (!onGrid(start) || !onGrid(end)) {
            throw invalid("開始與結束時間需以半小時為單位（例如 09:00、09:30）");
        }
        if (!end.isAfter(start)) {
            throw invalid("結束時間必須晚於開始時間；不支援跨午夜，跨日請分開申請");
        }
        int minutes = (int) Duration.between(start, end).toMinutes();
        return new Submission(type, workDate, start, end, minutes, requireReason(rawReason, "請假原因"));
    }

    private static boolean onGrid(LocalTime t) {
        return t.getSecond() == 0 && t.getNano() == 0 && t.getMinute() % STEP_MINUTES == 0;
    }

    /** 必填原因：去除前後空白後 1～500 字。 */
    static String requireReason(String raw, String what) {
        String reason = optionalReason(raw);
        if (reason == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", what + "必填");
        }
        return reason;
    }

    /** 選填原因：空白視為沒有填；超過 500 字 400。 */
    static String optionalReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String reason = raw.trim();
        if (reason.length() > REASON_MAX) {
            throw invalid("原因最多 " + REASON_MAX + " 個字");
        }
        return reason;
    }

    /** 清單查詢參數：狀態、日期範圍（同時提供或都不提供，最多 366 天）、分頁。 */
    record Query(Set<LeaveStatus> statuses, boolean anyDate, LocalDate from, LocalDate to, PageRequest page) {

        static Query of(StatusFilter status, LocalDate from, LocalDate to, Integer page, Integer size) {
            Set<LeaveStatus> statuses = status == null || status == StatusFilter.ALL
                    ? EnumSet.allOf(LeaveStatus.class) : EnumSet.of(LeaveStatus.valueOf(status.name()));
            if ((from == null) != (to == null)) {
                throw rangeError("請同時提供起日與迄日，或兩者都不提供");
            }
            if (from != null) {
                if (from.isAfter(to)) {
                    throw rangeError("起日不可晚於迄日");
                }
                if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
                    throw rangeError("日期範圍最多 " + MAX_RANGE_DAYS + " 天（含起訖日）");
                }
            }
            int p = page == null || page < 0 ? 0 : page;
            int s = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
            Sort sort = Sort.by(Sort.Order.desc("workDate"), Sort.Order.desc("startTime"), Sort.Order.desc("id"));
            // 沒有日期條件時參數仍需有值（不是 null）：用固定的哨兵日期，不會被使用
            LocalDate sentinel = LocalDate.of(1970, 1, 1);
            return new Query(statuses, from == null, from == null ? sentinel : from, to == null ? sentinel : to, PageRequest.of(p, s, sort));
        }
    }

    static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LEAVE", message);
    }

    static ApiException rangeError(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", message);
    }

    /** 不存在、他人的、不是指派給我的、已改派走的：一律相同的 404，不洩漏是否存在。 */
    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "LEAVE_NOT_FOUND", "找不到這筆請假申請");
    }

    static ApiException notPending(LeaveStatus status) {
        return new ApiException(HttpStatus.CONFLICT, "LEAVE_NOT_PENDING", "這筆申請已經不是待審狀態，請重新載入", Map.of("status", status.name()));
    }

    static ApiException changed() {
        return new ApiException(HttpStatus.CONFLICT, "LEAVE_CHANGED", "這筆申請已被更新（例如已改派），請重新載入後再操作");
    }
}
