package com.renewflow.eip.attendance;

import com.renewflow.eip.attendance.AttendanceRuleException.Rule;
import com.renewflow.eip.shared.time.BusinessTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 出勤使用案例。員工 ID 一律由呼叫端（Controller 取自 Session）傳入；時間一律來自注入的 Clock。
 * 業務衝突以 {@link AttendanceRuleException} 表示；其他例外（資料庫故障等）不在此轉換，維持 5xx。
 */
@Service
public class AttendanceService {

    /** 日期範圍上限：含起訖日 31 個日曆日。 */
    static final int MAX_RANGE_DAYS = 31;

    private final AttendanceRepository attendanceRepository;
    private final Clock clock;

    public AttendanceService(AttendanceRepository attendanceRepository, Clock clock) {
        this.attendanceRepository = attendanceRepository;
        this.clock = clock;
    }

    /** 上班打卡。 */
    @Transactional
    public AttendanceRecordResponse clockIn(Long employeeId) {
        Instant now = now();
        LocalDate today = BusinessTime.workDateOf(now);

        int inserted = attendanceRepository.insertIfAbsent(employeeId, today, now);
        if (inserted == 0) {
            // 衝突不會讓交易失效，所以可以在同一個交易內查詢原因，給出精準的訊息
            boolean alreadyCompleted = attendanceRepository.findByEmployeeIdAndWorkDate(employeeId, today)
                    .map(a -> a.getClockOutAt() != null)
                    .orElse(false);
            throw new AttendanceRuleException(Rule.ALREADY_CLOCKED_IN,
                    alreadyCompleted ? "今日已完成上下班，無法再次上班" : "今日已經上班打卡");
        }
        return recordOf(employeeId, today, today);
    }

    /** 下班打卡：只處理「今日」的紀錄，不替昨日補登。 */
    @Transactional
    public AttendanceRecordResponse clockOut(Long employeeId) {
        Instant now = now();
        LocalDate today = BusinessTime.workDateOf(now);

        int updated = attendanceRepository.clockOutIfOpen(employeeId, today, now);
        if (updated == 0) {
            Attendance current = attendanceRepository.findByEmployeeIdAndWorkDate(employeeId, today).orElse(null);
            if (current == null) {
                throw new AttendanceRuleException(Rule.CLOCK_IN_REQUIRED, "今日尚未上班打卡，無法下班打卡");
            }
            if (current.getClockOutAt() != null) {
                throw new AttendanceRuleException(Rule.ALREADY_CLOCKED_OUT, "今日已經下班打卡");
            }
            // 有紀錄、未下班、卻更新 0 筆：只剩「下班時間早於上班時間」，代表伺服器時鐘倒退（假設 A-14）
            throw new IllegalStateException("system clock is earlier than the recorded clock-in time");
        }
        return recordOf(employeeId, today, today);
    }

    @Transactional(readOnly = true)
    public TodayAttendanceResponse today(Long employeeId) {
        LocalDate today = BusinessTime.workDateOf(now());
        LocalDate unfinishedBefore = attendanceRepository
                .findFirstByEmployeeIdAndWorkDateBeforeAndClockOutAtIsNullOrderByWorkDateDesc(employeeId, today)
                .map(Attendance::getWorkDate)
                .orElse(null);

        return attendanceRepository.findByEmployeeIdAndWorkDate(employeeId, today)
                .map(a -> {
                    AttendanceRecordResponse record = AttendanceRecordResponse.from(a, today);
                    return new TodayAttendanceResponse(record.status(), today, BusinessTime.ZONE.getId(),
                            a.getClockInAt(), a.getClockOutAt(), unfinishedBefore);
                })
                .orElseGet(() -> new TodayAttendanceResponse(AttendanceStatus.NOT_STARTED, today,
                        BusinessTime.ZONE.getId(), null, null, unfinishedBefore));
    }

    /**
     * 本人出勤列表。from／to 同時提供或同時省略；省略＝今日所在月份。
     *
     * @throws InvalidDateRangeException 只提供一個、起日大於迄日、或超過 31 個日曆日
     */
    @Transactional(readOnly = true)
    public AttendanceListResponse list(Long employeeId, LocalDate from, LocalDate to) {
        LocalDate today = BusinessTime.workDateOf(now());
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
        long daysInclusive = ChronoUnit.DAYS.between(rangeFrom, rangeTo) + 1;
        if (daysInclusive > MAX_RANGE_DAYS) {
            throw new InvalidDateRangeException("日期範圍最多 " + MAX_RANGE_DAYS + " 天（含起訖日）");
        }

        List<AttendanceRecordResponse> records = attendanceRepository
                .findByEmployeeIdAndWorkDateBetweenOrderByWorkDateDesc(employeeId, rangeFrom, rangeTo).stream()
                .map(a -> AttendanceRecordResponse.from(a, today))
                .toList();
        return new AttendanceListResponse(rangeFrom, rangeTo, BusinessTime.ZONE.getId(), records);
    }

    private Instant now() {
        return BusinessTime.truncateToDatabasePrecision(clock.instant());
    }

    private AttendanceRecordResponse recordOf(Long employeeId, LocalDate workDate, LocalDate today) {
        Attendance attendance = attendanceRepository.findByEmployeeIdAndWorkDate(employeeId, workDate)
                .orElseThrow(() -> new IllegalStateException("attendance row missing right after a successful write"));
        return AttendanceRecordResponse.from(attendance, today);
    }
}
