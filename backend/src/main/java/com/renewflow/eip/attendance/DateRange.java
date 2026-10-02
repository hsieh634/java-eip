package com.renewflow.eip.attendance;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** 查詢用的日期範圍：同時提供或同時省略（省略＝今日所在月份）；含起訖日最多 31 個日曆日。員工端與管理端共用。 */
public record DateRange(LocalDate from, LocalDate to) {

    public static final int MAX_DAYS = 31;

    public static DateRange resolve(LocalDate from, LocalDate to, LocalDate today) {
        if ((from == null) != (to == null)) {
            throw new InvalidDateRangeException("請同時提供起日與迄日，或兩者都不提供（預設為本月）");
        }
        LocalDate rangeFrom = from;
        LocalDate rangeTo = to;
        if (rangeFrom == null) {
            rangeFrom = today.withDayOfMonth(1);
            rangeTo = today.withDayOfMonth(today.lengthOfMonth());
        }
        if (rangeFrom.isAfter(rangeTo)) {
            throw new InvalidDateRangeException("起日不可晚於迄日");
        }
        if (ChronoUnit.DAYS.between(rangeFrom, rangeTo) + 1 > MAX_DAYS) {
            throw new InvalidDateRangeException("日期範圍最多 " + MAX_DAYS + " 天（含起訖日）");
        }
        return new DateRange(rangeFrom, rangeTo);
    }
}
