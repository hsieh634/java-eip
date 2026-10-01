package com.renewflow.eip.attendance;

import com.renewflow.eip.shared.time.BusinessTime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** 申請／更正的時間換算與驗證（員工申請、核准、管理員直接更正共用）。 */
final class AttendanceTimes {

    private AttendanceTimes() {
    }

    /** 台北當地的日期＋時間 → 時間點。台北沒有日光節約時間，換算沒有歧義。 */
    static Instant toInstant(LocalDate workDate, LocalTime time) {
        return time == null ? null : BusinessTime.truncateToDatabasePrecision(
                workDate.atTime(time).atZone(BusinessTime.ZONE).toInstant());
    }

    /**
     * 驗證希望值：本輪仍是「同一台北日的白班」——日期不得在未來、時間不得在未來、下班不得早於上班。
     * 時間是以 workDate 為基準換算的，所以一定落在該業務日。
     */
    static void validate(LocalDate workDate, Instant clockIn, Instant clockOut, Instant now, LocalDate today) {
        if (workDate.isAfter(today)) {
            throw new InvalidRequestException("不能針對未來的日期申請");
        }
        if (clockIn == null) {
            throw new InvalidRequestException("必須提供上班時間");
        }
        if (clockIn.isAfter(now)) {
            throw new InvalidRequestException("上班時間不能是未來的時間");
        }
        if (clockOut != null) {
            if (clockOut.isAfter(now)) {
                throw new InvalidRequestException("下班時間不能是未來的時間");
            }
            if (clockOut.isBefore(clockIn)) {
                throw new InvalidRequestException("下班時間不能早於上班時間");
            }
        }
    }
}
