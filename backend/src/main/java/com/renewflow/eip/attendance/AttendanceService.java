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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 出勤、原始事件與去重快照同交易；所有寫入使用一致的鎖順序。 */
@Service
public class AttendanceService {
    private final AttendanceRepository attendanceRepository;
    private final GeofenceGate geofenceGate;
    private final ClockEventLog clockEventLog;
    private final Clock clock;
    private final AttendanceWriteRules rules;
    private final AttendanceClockSubmissions submissions;
    public AttendanceService(AttendanceRepository attendanceRepository, GeofenceGate geofenceGate,
                             ClockEventLog clockEventLog, Clock clock, AttendanceWriteRules rules,
                             AttendanceClockSubmissions submissions) {
        this.attendanceRepository = attendanceRepository;
        this.geofenceGate = geofenceGate;
        this.clockEventLog = clockEventLog;
        this.clock = clock;
        this.rules = rules;
        this.submissions = submissions;
    }
    @Transactional
    public AttendanceRecordResponse clockIn(Long employeeId, ClockLocationRequest body) { return write(employeeId, body, ClockAction.CLOCK_IN); }
    @Transactional
    public AttendanceRecordResponse clockOut(Long employeeId, ClockLocationRequest body) { return write(employeeId, body, ClockAction.CLOCK_OUT); }

    private AttendanceRecordResponse write(Long employeeId, ClockLocationRequest body, ClockAction action) {
        rules.lock(employeeId);
        if (body == null || body.requestId() == null) throw new InvalidRequestException("打卡必須提供 requestId");
        String fingerprint = submissions.fingerprint(body);
        AttendanceRecordResponse replay = submissions.replay(employeeId, body, action, fingerprint);
        if (replay != null) return replay; // 已確認原操作：不重新定位、不寫第二個事件
        Instant now = now(); // 等待鎖之後取時間，避免排隊期間跨日
        LocalDate today = BusinessTime.workDateOf(now);
        var decision = geofenceGate.checkForClock(action, body, now);
        Attendance open = attendanceRepository.findFirstByEmployeeIdAndWorkDateAndClockOutAtIsNullAndVoidedAtIsNull(employeeId, today).orElse(null);
        Long targetId;
        if (action == ClockAction.CLOCK_IN) {
            if (open != null) throw new AttendanceRuleException(Rule.ALREADY_CLOCKED_IN, "目前已有未下班時段，請先下班");
            rules.validate(employeeId, today, null, now, null);
            if (attendanceRepository.insertIfAbsent(employeeId, today, now) != 1) throw new AttendanceRuleException(Rule.ALREADY_CLOCKED_IN, "目前已有未下班時段");
            targetId = attendanceRepository.findFirstByEmployeeIdAndWorkDateAndVoidedAtIsNullOrderByIdDesc(employeeId, today).orElseThrow().getId();
        } else {
            if (open == null) {
                boolean completed = !attendanceRepository.findAllByEmployeeIdAndWorkDateAndVoidedAtIsNullOrderByClockInAtAscIdAsc(employeeId, today).isEmpty();
                throw new AttendanceRuleException(completed ? Rule.ALREADY_CLOCKED_OUT : Rule.CLOCK_IN_REQUIRED, "目前沒有未下班時段，無法下班打卡");
            }
            if (body.expectedAttendanceId() == null || body.expectedVersion() == null) throw new InvalidRequestException("下班必須指定時段 ID 與版本");
            if (!open.getId().equals(body.expectedAttendanceId()) || open.getVersion() != body.expectedVersion())
                throw new AttendanceConflictException("RECORD_CHANGED", "下班目標已變更，請重新查詢；不會改動另一組時段");
            if (now.isBefore(open.getClockInAt())) throw new IllegalStateException("system clock is earlier than the recorded clock-in time");
            targetId = open.getId();
            rules.validate(employeeId, today, targetId, open.getClockInAt(), now);
            if (attendanceRepository.clockOutIfOpen(employeeId, today, now, targetId, open.getVersion()) != 1) throw new IllegalStateException("open attendance write failed");
        }
        AttendanceRecordResponse response = AttendanceRecordResponse.from(attendanceRepository.findById(targetId).orElseThrow(), today).withRequestId(body.requestId());
        clockEventLog.record(employeeId, targetId, action, now, decision);
        submissions.record(employeeId, body, action, fingerprint, response);
        return response;
    }

    @Transactional(readOnly = true)
    public TodayAttendanceResponse today(Long employeeId) {
        LocalDate today = BusinessTime.workDateOf(now());
        LocalDate unfinishedBefore = attendanceRepository
                .findFirstByEmployeeIdAndWorkDateBeforeAndClockOutAtIsNullAndVoidedAtIsNullOrderByWorkDateDesc(employeeId, today)
                .map(Attendance::getWorkDate)
                .orElse(null);

        List<AttendanceRecordResponse> records = attendanceRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateDescIdDesc(employeeId, today, today)
                .stream().map(a -> AttendanceRecordResponse.from(a, today))
                .sorted(java.util.Comparator.comparing(AttendanceRecordResponse::clockInAt).thenComparing(AttendanceRecordResponse::id)).toList();
        var active = records.stream().filter(r -> r.status() != AttendanceStatus.VOIDED).toList();
        var current = active.stream().filter(r -> r.clockOutAt() == null).findFirst().orElse(active.isEmpty() ? null : active.getLast());
        Duration total = active.stream().filter(r -> r.clockOutAt() != null)
                .map(r -> Duration.between(r.clockInAt(), r.clockOutAt())).reduce(Duration.ZERO, Duration::plus);
        return new TodayAttendanceResponse(current == null ? AttendanceStatus.NOT_STARTED : current.status(), today, BusinessTime.ZONE.getId(),
                current == null ? null : current.clockInAt(), current == null ? null : current.clockOutAt(), unfinishedBefore,
                current == null ? null : current.id(), current == null ? null : current.version(), geofenceGate.policy(), records, total.getSeconds());
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

}
