package com.renewflow.eip.attendance;

import com.renewflow.eip.attendance.AttendanceDtos.AttendanceRequestResponse;
import com.renewflow.eip.attendance.AttendanceDtos.CreateRequest;
import com.renewflow.eip.attendance.AttendanceDtos.HistoryEntryResponse;
import com.renewflow.eip.shared.time.BusinessTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 員工端：提出、撤回、查看自己的申請與歷程。員工 ID 一律由呼叫端（Session）傳入。
 * 申請只是「希望值」——核准前不會改動任何正式出勤資料。
 */
@Service
public class AttendanceRequestService {

    private final AttendanceRepository attendanceRepository;
    private final AttendanceRequestRepository requestRepository;
    private final AttendanceHistoryRepository historyRepository;
    private final AttendanceAuditLog auditLog;
    private final AttendanceViews views;
    private final Clock clock;
    private final AttendanceWriteRules rules;

    AttendanceRequestService(AttendanceRepository attendanceRepository,
                             AttendanceRequestRepository requestRepository,
                             AttendanceHistoryRepository historyRepository,
                             AttendanceAuditLog auditLog,
                             AttendanceViews views,
                             Clock clock, AttendanceWriteRules rules) {
        this.attendanceRepository = attendanceRepository;
        this.requestRepository = requestRepository;
        this.historyRepository = historyRepository;
        this.auditLog = auditLog;
        this.views = views;
        this.clock = clock;
        this.rules = rules;
    }

    @Transactional
    public AttendanceRequestResponse submit(Long employeeId, CreateRequest body) {
        rules.lock(employeeId);
        Instant now = BusinessTime.truncateToDatabasePrecision(clock.instant());
        LocalDate today = BusinessTime.workDateOf(now);
        String reason = body.reason().trim();
        if (reason.isEmpty()) {
            throw new InvalidRequestException("請填寫原因");
        }

        boolean hasSnapshot = body.expectedAttendanceId() != null;
        if (hasSnapshot != (body.expectedVersion() != null)) {
            throw new InvalidRequestException("表單快照不完整：expectedAttendanceId 與 expectedVersion 必須同時提供或同時省略");
        }

        LocalDate workDate;
        Attendance target;
        Instant requestedIn = null;
        Instant requestedOut = null;
        if (body.type() == AttendanceRequestType.RESET_TODAY) {
            workDate = today; // 重新打卡只針對「今日」，日期由伺服器決定
            target = target(employeeId, today, body.expectedAttendanceId());
            if (!hasSnapshot && attendanceRepository.findAllByEmployeeIdAndWorkDateAndVoidedAtIsNullOrderByClockInAtAscIdAsc(employeeId, today).isEmpty()) {
                throw new AttendanceConflictException("NO_RECORD_TO_RESET", "今日沒有出勤紀錄，不需要申請重新打卡");
            }
            if (!hasSnapshot) {
                throw new InvalidRequestException("申請整組作廢必須附上你看到的紀錄（expectedAttendanceId／expectedVersion）");
            }
            requireSameSnapshot(target, body);
        } else {
            if (body.workDate() == null || body.clockInTime() == null) {
                throw new InvalidRequestException("更正申請必須提供日期與上班時間");
            }
            workDate = body.workDate();
            requestedIn = AttendanceTimes.toInstant(workDate, body.clockInTime());
            requestedOut = AttendanceTimes.toInstant(workDate, body.clockOutTime());
            AttendanceTimes.validate(workDate, requestedIn, requestedOut, now, today);
            target = target(employeeId, workDate, body.expectedAttendanceId());
            requireSameSnapshot(target, body);
            rules.validate(employeeId, workDate, body.expectedAttendanceId(), requestedIn, requestedOut);
            if (target != null && Objects.equals(target.getClockInAt(), requestedIn)
                    && Objects.equals(target.getClockOutAt(), requestedOut)) {
                throw new InvalidRequestException("申請的時間與目前的紀錄相同，沒有需要更正的內容");
            }
        }

        Instant originalIn = target == null ? null : target.getClockInAt();
        Instant originalOut = target == null ? null : target.getClockOutAt();
        Long id = requestRepository.insertPending(employeeId, body.type().name(), workDate,
                        target == null ? null : target.getId(), target == null ? null : target.getVersion(),
                        originalIn, originalOut, requestedIn, requestedOut, reason, now)
                .orElseThrow(() -> new AttendanceConflictException("DUPLICATE_PENDING_REQUEST",
                        "這個日期已有待審申請，請先撤回或等待審核"));

        auditLog.record(new AuditEntry(AttendanceOperation.REQUEST_SUBMITTED, employeeId, workDate, id, body.type(),
                target == null ? null : target.getId(), target == null ? null : target.getVersion(), reason,
                originalIn, originalOut, requestedIn, requestedOut, null, null, null, null, employeeId, now));
        return views.request(requestRepository.findById(id).orElseThrow(), today);
    }

    private Attendance target(Long employeeId, LocalDate date, Long id) {
        if (id == null) return null; // 明確補登，不以同日其他紀錄替換目標
        return attendanceRepository.findById(id).filter(a -> a.getEmployeeId().equals(employeeId))
                .filter(a -> a.getWorkDate().equals(date)).filter(a -> !a.isVoided()).orElse(null);
    }

    /**
     * 表單快照必須與「現在」的有效紀錄一致：帶了 ID／版本 → 必須正是那一筆、那個版本；
     * 沒帶 → 明確申請補登新時段，跨列合法性另在同一鎖內驗證。若指定目標不符則代表過期畫面（例如紀錄被更正、
     * 被作廢並重新打卡），不能用舊的時間提出申請。
     */
    private static void requireSameSnapshot(Attendance current, CreateRequest body) {
        boolean matches = body.expectedAttendanceId() == null
                ? current == null
                : current != null && current.getId().equals(body.expectedAttendanceId())
                        && current.getVersion() == body.expectedVersion();
        if (!matches) {
            throw new AttendanceConflictException("RECORD_CHANGED", "出勤資料在你開啟表單後已經變動，請重新確認目前的紀錄後再提出");
        }
    }

    /** 撤回自己的待審申請。別人的申請一律回 404（不洩漏是否存在）。 */
    @Transactional
    public AttendanceRequestResponse cancel(Long employeeId, Long requestId) {
        rules.lock(employeeId);
        Instant now = BusinessTime.truncateToDatabasePrecision(clock.instant());
        int updated = requestRepository.cancel(requestId, employeeId, now);
        if (updated == 0) {
            if (requestRepository.findByIdAndEmployeeId(requestId, employeeId).isEmpty()) {
                throw new AttendanceNotFoundException("REQUEST_NOT_FOUND", "找不到這筆申請");
            }
            throw new AttendanceConflictException("REQUEST_NOT_PENDING", "這筆申請已不是待審狀態，無法撤回");
        }
        AttendanceRequest request = requestRepository.findByIdAndEmployeeId(requestId, employeeId).orElseThrow();
        auditLog.record(new AuditEntry(AttendanceOperation.REQUEST_CANCELLED, employeeId, request.getWorkDate(), requestId,
                request.getType(), request.getTargetAttendanceId(), request.getTargetVersion(), request.getReason(),
                request.getOriginalClockInAt(), request.getOriginalClockOutAt(),
                request.getRequestedClockInAt(), request.getRequestedClockOutAt(), null, null, null, null, employeeId, now));
        return views.request(request, BusinessTime.workDateOf(now));
    }

    @Transactional(readOnly = true)
    public List<AttendanceRequestResponse> listMine(Long employeeId) {
        LocalDate today = BusinessTime.workDateOf(clock.instant());
        return views.requests(requestRepository.findTop50ByEmployeeIdOrderByIdDesc(employeeId), today);
    }

    @Transactional(readOnly = true)
    public List<HistoryEntryResponse> historyOf(Long employeeId) {
        return views.history(historyRepository.findTop200ByEmployeeIdOrderByIdDesc(employeeId));
    }
}
