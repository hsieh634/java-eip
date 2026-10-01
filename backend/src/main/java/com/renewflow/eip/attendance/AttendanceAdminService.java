package com.renewflow.eip.attendance;

import com.renewflow.eip.attendance.AttendanceDtos.AdminRecordsResponse;
import com.renewflow.eip.attendance.AttendanceDtos.AttendanceRequestResponse;
import com.renewflow.eip.attendance.AttendanceDtos.DirectCorrectionRequest;
import com.renewflow.eip.attendance.AttendanceDtos.DirectVoidRequest;
import com.renewflow.eip.attendance.AttendanceDtos.EmployeeSummary;
import com.renewflow.eip.attendance.AttendanceDtos.HistoryEntryResponse;
import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.EmployeeRepository;
import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.shared.time.BusinessTime;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 管理端：審核申請、直接更正／作廢、查詢與歷程。
 *
 * <p>每個公開方法先確認呼叫者是 ADMIN（縱深防禦：URL 規則 /api/admin/** 已擋一次，ActiveAccountFilter 也確認過角色是最新）。
 * 每個「會改資料」的方法都是單一交易：申請狀態、出勤修改、歷程一起成功或一起回滾。
 * 並行與過時的處理都靠資料庫的條件式 UPDATE／INSERT（版本比對、PENDING 條件、部分唯一索引）。
 */
@Service
public class AttendanceAdminService {

    private final AttendanceRepository attendanceRepository;
    private final AttendanceRequestRepository requestRepository;
    private final AttendanceHistoryRepository historyRepository;
    private final EmployeeRepository employeeRepository;
    private final AttendanceAuditLog auditLog;
    private final AttendanceViews views;
    private final Clock clock;

    AttendanceAdminService(AttendanceRepository attendanceRepository,
                           AttendanceRequestRepository requestRepository,
                           AttendanceHistoryRepository historyRepository,
                           EmployeeRepository employeeRepository,
                           AttendanceAuditLog auditLog,
                           AttendanceViews views,
                           Clock clock) {
        this.attendanceRepository = attendanceRepository;
        this.requestRepository = requestRepository;
        this.historyRepository = historyRepository;
        this.employeeRepository = employeeRepository;
        this.auditLog = auditLog;
        this.views = views;
        this.clock = clock;
    }

    // ---------------- 審核 ----------------

    @Transactional
    public AttendanceRequestResponse approve(EmployeeUserDetails actor, Long requestId, String comment) {
        requireAdmin(actor);
        AttendanceRequest request = loadForReview(actor, requestId);
        Instant now = now();
        LocalDate today = BusinessTime.workDateOf(now);
        String note = blankToNull(comment);

        // 先把申請從 PENDING 轉成 APPROVED：這個 UPDATE 鎖住申請列，同一申請的並行審核只會有一個成功
        if (requestRepository.review(requestId, "APPROVED", actor.getEmployeeId(), note, now) == 0) {
            throw new AttendanceConflictException("REQUEST_NOT_PENDING", "這筆申請已被處理過，無法再次審核");
        }

        Attendance before = request.getTargetAttendanceId() == null ? null
                : attendanceRepository.findById(request.getTargetAttendanceId()).orElse(null);
        Long attendanceId = request.getTargetAttendanceId();
        Instant afterIn;
        Instant afterOut;
        boolean voided;

        if (request.getType() == AttendanceRequestType.RESET_TODAY) {
            if (!request.getWorkDate().equals(today)) {
                throw new AttendanceConflictException("RESET_DATE_PASSED",
                        "已經跨過申請當天的日期，無法核准重新打卡；請退回後，請員工改用「更正／補登時間」申請");
            }
            if (attendanceRepository.voidIfCurrent(attendanceId, request.getTargetVersion(), actor.getEmployeeId(), now) == 0) {
                throw stale();
            }
            afterIn = before == null ? null : before.getClockInAt();
            afterOut = before == null ? null : before.getClockOutAt();
            voided = true; // 原時間保留在作廢的那一筆
        } else {
            afterIn = request.getRequestedClockInAt();
            afterOut = request.getRequestedClockOutAt();
            voided = false;
            if (attendanceId != null) {
                if (attendanceRepository.correctIfCurrent(attendanceId, request.getTargetVersion(), afterIn, afterOut, now) == 0) {
                    throw stale();
                }
            } else {
                // 原本沒有紀錄（補登）：該日仍沒有有效紀錄才建立；0 筆＝這段期間員工已經有紀錄了
                if (attendanceRepository.insertManualIfAbsent(request.getEmployeeId(), request.getWorkDate(), afterIn, afterOut, now) == 0) {
                    throw stale();
                }
                attendanceId = attendanceRepository
                        .findByEmployeeIdAndWorkDateAndVoidedAtIsNull(request.getEmployeeId(), request.getWorkDate())
                        .map(Attendance::getId).orElse(null);
            }
        }

        auditLog.record(new AuditEntry(AttendanceOperation.REQUEST_APPROVED, request.getEmployeeId(), request.getWorkDate(),
                requestId, request.getType(), attendanceId, request.getTargetVersion(), note,
                before == null ? request.getOriginalClockInAt() : before.getClockInAt(),
                before == null ? request.getOriginalClockOutAt() : before.getClockOutAt(),
                request.getRequestedClockInAt(), request.getRequestedClockOutAt(),
                afterIn, afterOut, voided, "APPROVED", actor.getEmployeeId(), now));
        return views.request(requestRepository.findById(requestId).orElseThrow(), today);
    }

    @Transactional
    public AttendanceRequestResponse reject(EmployeeUserDetails actor, Long requestId, String reason) {
        requireAdmin(actor);
        AttendanceRequest request = loadForReview(actor, requestId);
        Instant now = now();
        String note = reason == null ? "" : reason.trim();
        if (note.isEmpty()) {
            throw new InvalidRequestException("退回必須填寫原因");
        }
        if (requestRepository.review(requestId, "REJECTED", actor.getEmployeeId(), note, now) == 0) {
            throw new AttendanceConflictException("REQUEST_NOT_PENDING", "這筆申請已被處理過，無法再次審核");
        }
        auditLog.record(new AuditEntry(AttendanceOperation.REQUEST_REJECTED, request.getEmployeeId(), request.getWorkDate(),
                requestId, request.getType(), request.getTargetAttendanceId(), request.getTargetVersion(), note,
                request.getOriginalClockInAt(), request.getOriginalClockOutAt(),
                request.getRequestedClockInAt(), request.getRequestedClockOutAt(),
                null, null, null, "REJECTED", actor.getEmployeeId(), now));
        return views.request(requestRepository.findById(requestId).orElseThrow(), BusinessTime.workDateOf(now));
    }

    @Transactional(readOnly = true)
    public List<AttendanceRequestResponse> listRequests(EmployeeUserDetails actor, String status) {
        requireAdmin(actor);
        LocalDate today = BusinessTime.workDateOf(clock.instant());
        List<AttendanceRequest> found;
        if (status == null || status.isBlank()) {
            found = requestRepository.findTop200ByStatusOrderByIdAsc(AttendanceRequestStatus.PENDING);
        } else if (status.equalsIgnoreCase("ALL")) {
            found = requestRepository.findTop200ByOrderByIdDesc();
        } else {
            try {
                found = requestRepository.findTop200ByStatusOrderByIdAsc(AttendanceRequestStatus.valueOf(status.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("status 必須是 PENDING、APPROVED、REJECTED、CANCELLED 或 ALL");
            }
        }
        return views.requests(found, today);
    }

    @Transactional(readOnly = true)
    public AttendanceRequestResponse getRequest(EmployeeUserDetails actor, Long requestId) {
        requireAdmin(actor);
        AttendanceRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new AttendanceNotFoundException("REQUEST_NOT_FOUND", "找不到這筆申請"));
        return views.request(request, BusinessTime.workDateOf(clock.instant()));
    }

    // ---------------- 直接更正／作廢 ----------------

    @Transactional
    public AttendanceRecordResponse correctDirectly(EmployeeUserDetails actor, DirectCorrectionRequest body) {
        requireAdmin(actor);
        requireNotSelf(actor, body.employeeId());
        requireEmployee(body.employeeId());
        Instant now = now();
        LocalDate today = BusinessTime.workDateOf(now);
        String reason = body.reason().trim();
        if (reason.isEmpty()) {
            throw new InvalidRequestException("請填寫原因");
        }
        // 兩種模式必須明確：更正既有紀錄＝ID＋版本都帶；補登＝兩者都不帶。只帶其一是不完整的請求。
        boolean correctingExisting = body.attendanceId() != null;
        if (correctingExisting != (body.expectedVersion() != null)) {
            throw new InvalidRequestException("更正既有紀錄必須同時提供 attendanceId 與 expectedVersion；補登新紀錄兩者都不提供");
        }
        Instant clockIn = AttendanceTimes.toInstant(body.workDate(), body.clockInTime());
        Instant clockOut = AttendanceTimes.toInstant(body.workDate(), body.clockOutTime());
        AttendanceTimes.validate(body.workDate(), clockIn, clockOut, now, today);

        Attendance before = null;
        if (!correctingExisting) {
            // 補登：該日必須沒有有效紀錄（部分唯一索引裁決；0 筆＝已經有了）
            if (attendanceRepository.insertManualIfAbsent(body.employeeId(), body.workDate(), clockIn, clockOut, now) == 0) {
                throw changed();
            }
        } else {
            // 更正：表單當時看到的「那一筆」——ID、員工、日期、有效狀態、版本全部要相符。
            // 不能改用「現在查到的當日有效紀錄」：舊紀錄被作廢、員工重新打卡後，新紀錄的版本可能剛好相同，
            // 只比版本會誤改到另一筆。ID 一律用前端帶來的，絕不替換。
            before = attendanceRepository.findById(body.attendanceId())
                    .filter(a -> a.getEmployeeId().equals(body.employeeId()))
                    .filter(a -> a.getWorkDate().equals(body.workDate()))
                    .filter(a -> !a.isVoided())
                    .filter(a -> a.getVersion() == body.expectedVersion())
                    .orElseThrow(AttendanceAdminService::changed);
            if (Objects.equals(before.getClockInAt(), clockIn) && Objects.equals(before.getClockOutAt(), clockOut)) {
                throw new InvalidRequestException("設定的時間與目前的紀錄相同，沒有需要更正的內容");
            }
            // 最終仍以原子條件更新為準（id＋version＋未作廢），擋住「查詢之後、更新之前」的並行變動
            if (attendanceRepository.correctIfCurrent(body.attendanceId(), body.expectedVersion(), clockIn, clockOut, now) == 0) {
                throw changed();
            }
        }

        Attendance after = correctingExisting
                ? attendanceRepository.findById(body.attendanceId()).orElseThrow()
                : attendanceRepository.findByEmployeeIdAndWorkDateAndVoidedAtIsNull(body.employeeId(), body.workDate()).orElseThrow();
        auditLog.record(new AuditEntry(AttendanceOperation.DIRECT_CORRECTION, body.employeeId(), body.workDate(), null, null,
                after.getId(), body.expectedVersion(), reason,
                before == null ? null : before.getClockInAt(), before == null ? null : before.getClockOutAt(),
                clockIn, clockOut, after.getClockInAt(), after.getClockOutAt(), false, null, actor.getEmployeeId(), now));
        return AttendanceRecordResponse.from(after, today);
    }

    /** 作廢該員工今日的有效紀錄（原時間保留），讓員工可以重新打卡。只限今日。 */
    @Transactional
    public AttendanceRecordResponse voidToday(EmployeeUserDetails actor, DirectVoidRequest body) {
        requireAdmin(actor);
        requireNotSelf(actor, body.employeeId());
        requireEmployee(body.employeeId());
        Instant now = now();
        LocalDate today = BusinessTime.workDateOf(now);
        String reason = body.reason().trim();
        if (reason.isEmpty()) {
            throw new InvalidRequestException("請填寫原因");
        }

        Attendance target = attendanceRepository.findById(body.attendanceId())
                .filter(a -> a.getEmployeeId().equals(body.employeeId()))
                .orElseThrow(() -> new AttendanceNotFoundException("RECORD_NOT_FOUND", "找不到這筆出勤紀錄"));
        if (target.isVoided()) {
            throw changed();
        }
        if (!target.getWorkDate().equals(today)) {
            throw new AttendanceConflictException("VOID_DATE_PASSED", "只能作廢今日的紀錄；過去的日期請使用「更正時間」");
        }
        if (attendanceRepository.voidIfCurrent(target.getId(), body.expectedVersion(), actor.getEmployeeId(), now) == 0) {
            throw changed();
        }
        Attendance after = attendanceRepository.findById(target.getId()).orElseThrow();
        auditLog.record(new AuditEntry(AttendanceOperation.DIRECT_VOID, body.employeeId(), target.getWorkDate(), null, null,
                target.getId(), body.expectedVersion(), reason,
                target.getClockInAt(), target.getClockOutAt(), null, null,
                target.getClockInAt(), target.getClockOutAt(), true, null, actor.getEmployeeId(), now));
        return AttendanceRecordResponse.from(after, today);
    }

    // ---------------- 查詢 ----------------

    @Transactional(readOnly = true)
    public List<EmployeeSummary> employees(EmployeeUserDetails actor) {
        requireAdmin(actor);
        return employeeRepository.findAll().stream()
                .filter(Employee::isEnabled)
                .map(views::employee)
                .sorted(java.util.Comparator.comparing(EmployeeSummary::employeeNo))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminRecordsResponse records(EmployeeUserDetails actor, Long employeeId, LocalDate from, LocalDate to) {
        requireAdmin(actor);
        Employee employee = requireEmployee(employeeId);
        LocalDate today = BusinessTime.workDateOf(clock.instant());
        DateRange range = DateRange.resolve(from, to, today);
        List<AttendanceRecordResponse> records = attendanceRepository
                .findByEmployeeIdAndWorkDateBetweenOrderByWorkDateDescIdDesc(employeeId, range.from(), range.to()).stream()
                .map(a -> AttendanceRecordResponse.from(a, today)).toList();
        return new AdminRecordsResponse(views.employee(employee), range.from(), range.to(), BusinessTime.ZONE.getId(), records);
    }

    @Transactional(readOnly = true)
    public List<HistoryEntryResponse> history(EmployeeUserDetails actor, Long employeeId, LocalDate from, LocalDate to) {
        requireAdmin(actor);
        requireEmployee(employeeId);
        DateRange range = DateRange.resolve(from, to, BusinessTime.workDateOf(clock.instant()));
        return views.history(historyRepository.findByEmployeeIdAndWorkDateBetweenOrderByIdDesc(employeeId, range.from(), range.to()));
    }

    // ---------------- 內部 ----------------

    private static void requireAdmin(EmployeeUserDetails actor) {
        if (actor == null || actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("administrator role required");
        }
    }

    private static void requireNotSelf(EmployeeUserDetails actor, Long targetEmployeeId) {
        if (actor.getEmployeeId().equals(targetEmployeeId)) {
            throw new AttendanceForbiddenException("SELF_CORRECTION_FORBIDDEN", "不能直接更正自己的出勤，請提出申請並由其他管理員處理");
        }
    }

    private AttendanceRequest loadForReview(EmployeeUserDetails actor, Long requestId) {
        AttendanceRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new AttendanceNotFoundException("REQUEST_NOT_FOUND", "找不到這筆申請"));
        if (request.getEmployeeId().equals(actor.getEmployeeId())) {
            throw new AttendanceForbiddenException("SELF_REVIEW_FORBIDDEN", "不能審核自己提出的申請，請由其他管理員處理");
        }
        return request;
    }

    private Employee requireEmployee(Long employeeId) {
        return employeeRepository.findById(employeeId)
                .orElseThrow(() -> new AttendanceNotFoundException("EMPLOYEE_NOT_FOUND", "找不到這位員工"));
    }

    private Instant now() {
        return BusinessTime.truncateToDatabasePrecision(clock.instant());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static AttendanceConflictException stale() {
        return new AttendanceConflictException("REQUEST_STALE",
                "出勤資料在申請之後已經變動，無法套用這筆申請（不會覆蓋較新的資料）。請員工撤回後重新提出");
    }

    private static AttendanceConflictException changed() {
        return new AttendanceConflictException("RECORD_CHANGED", "這筆出勤資料已被其他操作更新，請重新查詢後再試");
    }
}
