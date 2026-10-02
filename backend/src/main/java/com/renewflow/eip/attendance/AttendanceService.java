package com.renewflow.eip.attendance;

import com.renewflow.eip.attendance.AttendanceRuleException.Rule;
import com.renewflow.eip.geofence.ClockAction;
import com.renewflow.eip.geofence.GeofenceDtos.ClockLocationRequest;
import com.renewflow.eip.geofence.GeofenceGate;
import com.renewflow.eip.geofence.LocationDecision;
import com.renewflow.eip.shared.time.BusinessTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 出勤使用案例。員工 ID 一律由呼叫端（Controller 取自 Session）傳入；時間一律來自注入的 Clock。
 * 業務衝突以 {@link AttendanceRuleException} 表示；其他例外（資料庫故障等）不在此轉換，維持 5xx。
 *
 * <p>T03.2：打卡交易的順序是「定位關卡（鎖住設定快照並驗證）→ 原本的原子打卡 SQL → 寫入打卡證據」，
 * 三者在同一個交易內：定位被拒絕時還沒有寫入任何東西；證據寫入失敗時出勤一起回滾。
 */
@Service
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final GeofenceGate geofenceGate;
    private final ClockEventLog clockEventLog;
    private final Clock clock;

    public AttendanceService(AttendanceRepository attendanceRepository, GeofenceGate geofenceGate, ClockEventLog clockEventLog, Clock clock) {
        this.attendanceRepository = attendanceRepository;
        this.geofenceGate = geofenceGate;
        this.clockEventLog = clockEventLog;
        this.clock = clock;
    }

    /** 上班打卡。location 可為 null（不受定位限制時沿用無本文的請求）。 */
    @Transactional
    public AttendanceRecordResponse clockIn(Long employeeId, ClockLocationRequest location) {
        Instant now = now();
        LocalDate today = BusinessTime.workDateOf(now);
        // 先過定位關卡：被拒絕時丟出例外，出勤、版本、證據都還沒被動到
        LocationDecision decision = geofenceGate.checkForClock(ClockAction.CLOCK_IN, location, now);

        int inserted = attendanceRepository.insertIfAbsent(employeeId, today, now);
        if (inserted == 0) {
            // 衝突不會讓交易失效，所以可以在同一個交易內查詢原因，給出精準的訊息
            boolean alreadyCompleted = attendanceRepository.findByEmployeeIdAndWorkDateAndVoidedAtIsNull(employeeId, today)
                    .map(a -> a.getClockOutAt() != null)
                    .orElse(false);
            throw new AttendanceRuleException(Rule.ALREADY_CLOCKED_IN,
                    alreadyCompleted ? "今日已完成上下班，無法再次上班" : "今日已經上班打卡");
        }
        return recordWithEvidence(employeeId, today, ClockAction.CLOCK_IN, now, decision);
    }

    /** 下班打卡：只處理「今日」的紀錄，不替昨日補登。 */
    @Transactional
    public AttendanceRecordResponse clockOut(Long employeeId, ClockLocationRequest location) {
        Instant now = now();
        LocalDate today = BusinessTime.workDateOf(now);
        LocationDecision decision = geofenceGate.checkForClock(ClockAction.CLOCK_OUT, location, now);

        int updated = attendanceRepository.clockOutIfOpen(employeeId, today, now);
        if (updated == 0) {
            Attendance current = attendanceRepository.findByEmployeeIdAndWorkDateAndVoidedAtIsNull(employeeId, today).orElse(null);
            if (current == null) {
                throw new AttendanceRuleException(Rule.CLOCK_IN_REQUIRED, "今日尚未上班打卡，無法下班打卡");
            }
            if (current.getClockOutAt() != null) {
                throw new AttendanceRuleException(Rule.ALREADY_CLOCKED_OUT, "今日已經下班打卡");
            }
            // 有紀錄、未下班、卻更新 0 筆：只剩「下班時間早於上班時間」，代表伺服器時鐘倒退（假設 A-14）
            throw new IllegalStateException("system clock is earlier than the recorded clock-in time");
        }
        return recordWithEvidence(employeeId, today, ClockAction.CLOCK_OUT, now, decision);
    }

    @Transactional(readOnly = true)
    public TodayAttendanceResponse today(Long employeeId) {
        LocalDate today = BusinessTime.workDateOf(now());
        LocalDate unfinishedBefore = attendanceRepository
                .findFirstByEmployeeIdAndWorkDateBeforeAndClockOutAtIsNullAndVoidedAtIsNullOrderByWorkDateDesc(employeeId, today)
                .map(Attendance::getWorkDate)
                .orElse(null);

        return attendanceRepository.findByEmployeeIdAndWorkDateAndVoidedAtIsNull(employeeId, today)
                .map(a -> {
                    AttendanceRecordResponse record = AttendanceRecordResponse.from(a, today);
                    return new TodayAttendanceResponse(record.status(), today, BusinessTime.ZONE.getId(),
                            a.getClockInAt(), a.getClockOutAt(), unfinishedBefore, a.getId(), a.getVersion(), geofenceGate.policy());
                })
                .orElseGet(() -> new TodayAttendanceResponse(AttendanceStatus.NOT_STARTED, today,
                        BusinessTime.ZONE.getId(), null, null, unfinishedBefore, null, null, geofenceGate.policy()));
    }

    /**
     * 本人出勤列表。from／to 同時提供或同時省略；省略＝今日所在月份。
     *
     * @throws InvalidDateRangeException 只提供一個、起日大於迄日、或超過 31 個日曆日
     */
    @Transactional(readOnly = true)
    public AttendanceListResponse list(Long employeeId, LocalDate from, LocalDate to) {
        LocalDate today = BusinessTime.workDateOf(now());
        DateRange range = DateRange.resolve(from, to, today);

        List<AttendanceRecordResponse> records = attendanceRepository
                .findByEmployeeIdAndWorkDateBetweenOrderByWorkDateDescIdDesc(employeeId, range.from(), range.to()).stream()
                .map(a -> AttendanceRecordResponse.from(a, today))
                .toList();
        return new AttendanceListResponse(range.from(), range.to(), BusinessTime.ZONE.getId(), records);
    }

    private Instant now() {
        return BusinessTime.truncateToDatabasePrecision(clock.instant());
    }

    /** 打卡成功後：讀回剛寫入的紀錄，並在同一交易寫入打卡證據（含實際採用的設定快照）。 */
    private AttendanceRecordResponse recordWithEvidence(Long employeeId, LocalDate today, ClockAction action,
                                                        Instant now, LocationDecision decision) {
        Attendance attendance = attendanceRepository.findByEmployeeIdAndWorkDateAndVoidedAtIsNull(employeeId, today)
                .orElseThrow(() -> new IllegalStateException("attendance row missing right after a successful write"));
        clockEventLog.record(employeeId, attendance.getId(), action, now, decision);
        return AttendanceRecordResponse.from(attendance, today);
    }
}
