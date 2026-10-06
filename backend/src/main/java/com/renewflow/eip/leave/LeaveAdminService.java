package com.renewflow.eip.leave;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.EmployeeRepository;
import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.leave.LeaveDtos.ApproverSettingResponse;
import com.renewflow.eip.leave.LeaveDtos.EmployeeBrief;
import com.renewflow.eip.leave.LeaveDtos.LeaveDetail;
import com.renewflow.eip.leave.LeaveDtos.LeaveHistoryEntry;
import com.renewflow.eip.leave.LeaveDtos.LeavePage;
import com.renewflow.eip.leave.LeaveDtos.ReassignRequest;
import com.renewflow.eip.leave.LeaveDtos.UpdateApproverSettingRequest;
import com.renewflow.eip.leave.LeaveEnums.HistoryAction;
import com.renewflow.eip.leave.LeaveEnums.LeaveStatus;
import com.renewflow.eip.leave.LeaveRules.Query;
import com.renewflow.eip.organization.OrganizationLock;
import com.renewflow.eip.shared.time.BusinessTime;
import com.renewflow.eip.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 請假管理（僅 ADMIN）：查詢所有申請與歷程、改派待審申請、管理指定請假審核人。
 * <b>沒有</b>「以 ADMIN 身分核准／退回」的功能——核准與退回只在 {@link LeaveRequestService}，且必須是目前審核人。
 * 縱深防禦：/api/admin/** 的 URL 規則已要求 ADMIN，這裡再檢查一次。
 */
@Service
public class LeaveAdminService {

    private final LeaveRequestRepository requestRepository;
    private final LeaveApproverSettingRepository settingRepository;
    private final LeaveHistoryRepository historyRepository;
    private final EmployeeRepository employeeRepository;
    private final ApproverResolver approverResolver;
    private final LeaveAuditLog auditLog;
    private final LeaveViews views;
    private final OrganizationLock lock;
    private final Clock clock;

    LeaveAdminService(LeaveRequestRepository requestRepository, LeaveApproverSettingRepository settingRepository,
                      LeaveHistoryRepository historyRepository, EmployeeRepository employeeRepository, ApproverResolver approverResolver,
                      LeaveAuditLog auditLog, LeaveViews views, OrganizationLock lock, Clock clock) {
        this.requestRepository = requestRepository;
        this.settingRepository = settingRepository;
        this.historyRepository = historyRepository;
        this.employeeRepository = employeeRepository;
        this.approverResolver = approverResolver;
        this.auditLog = auditLog;
        this.views = views;
        this.lock = lock;
        this.clock = clock;
    }

    // ================================================================ 申請

    @Transactional(readOnly = true)
    public LeavePage search(EmployeeUserDetails actor, Query query, Long employeeId) {
        requireAdmin(actor);
        return views.page(requestRepository.search(employeeId == null, employeeId == null ? -1L : employeeId, true, -1L,
                query.statuses(), query.anyDate(), query.from(), query.to(), query.page()));
    }

    @Transactional(readOnly = true)
    public LeaveDetail get(EmployeeUserDetails actor, Long id) {
        requireAdmin(actor);
        return views.detail(requestRepository.findById(id).orElseThrow(LeaveRules::notFound));
    }

    /** 改派待審申請：原因必填；不能改派自己的申請；新審核人必須有效且不是申請人。狀態不變、版本 +1。 */
    @Transactional
    public LeaveDetail reassign(EmployeeUserDetails actor, Long id, ReassignRequest body) {
        requireAdmin(actor);
        String reason = LeaveRules.requireReason(body.reason(), "改派原因");
        lock.acquireFor(actor);
        LeaveRequest r = requestRepository.findById(id).orElseThrow(LeaveRules::notFound);
        if (Objects.equals(r.getEmployeeId(), actor.getEmployeeId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_REASSIGN_FORBIDDEN", "不能改派自己的請假申請，請由另一位管理員操作");
        }
        if (r.getStatus() != LeaveStatus.PENDING) {
            throw LeaveRules.notPending(r.getStatus());
        }
        if (r.getVersion() != body.expectedVersion()) {
            throw LeaveRules.changed();
        }
        if (Objects.equals(body.approverId(), r.getEmployeeId())) {
            throw approverIsApplicant();
        }
        if (Objects.equals(body.approverId(), r.getApproverId())) {
            throw noChange("新的審核人與目前相同");
        }
        Employee target = requireValidApprover(body.approverId());
        Instant now = now();
        if (requestRepository.reassignIfPending(id, body.expectedVersion(), target.getId(), now) == 0) {
            throw LeaveRules.changed();
        }
        auditLog.request(id, HistoryAction.REASSIGNED, LeaveStatus.PENDING, LeaveStatus.PENDING, r.getApproverId(), target.getId(),
                reason, actor.getEmployeeId(), now);
        return views.detail(requestRepository.findById(id).orElseThrow());
    }

    // ================================================================ 指定請假審核人

    @Transactional(readOnly = true)
    public List<ApproverSettingResponse> listSettings(EmployeeUserDetails actor) {
        requireAdmin(actor);
        return settingRepository.findByApproverIdIsNotNullOrderByEmployeeIdAsc().stream()
                .map(s -> settingOf(employeeRepository.findById(s.getEmployeeId()).orElseThrow(), s))
                .toList();
    }

    @Transactional(readOnly = true)
    public ApproverSettingResponse getSetting(EmployeeUserDetails actor, Long employeeId) {
        requireAdmin(actor);
        Employee employee = employeeRepository.findById(employeeId).orElseThrow(LeaveAdminService::employeeNotFound);
        return settingOf(employee, settingRepository.findById(employeeId).orElse(null));
    }

    /**
     * 設定或移除（approverId = null）某位員工的指定請假審核人。原因必填、版本相符；
     * 只影響之後送出的申請，不改既有申請，也<b>不改</b> employee.manager_id（不影響組織的主管關係）。
     */
    @Transactional
    public ApproverSettingResponse updateSetting(EmployeeUserDetails actor, Long employeeId, UpdateApproverSettingRequest body) {
        requireAdmin(actor);
        String reason = LeaveRules.requireReason(body.reason(), "修改原因");
        lock.acquireFor(actor);
        Employee employee = employeeRepository.findById(employeeId).orElseThrow(LeaveAdminService::employeeNotFound);
        LeaveApproverSetting current = settingRepository.findById(employeeId).orElse(null);
        long version = current == null ? 0 : current.getVersion();
        Long before = current == null ? null : current.getApproverId();
        if (version != body.expectedVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "APPROVER_SETTING_CHANGED", "這位員工的指定審核人已被其他管理員修改，請重新載入後再操作");
        }
        Long after = body.approverId();
        if (after != null) {
            if (after.equals(employeeId)) {
                throw approverIsApplicant();
            }
            requireValidApprover(after);
        }
        if (Objects.equals(before, after)) {
            throw noChange("內容與目前相同，沒有需要儲存的變更");
        }
        Instant now = now();
        int written = current == null
                ? settingRepository.insertFirst(employeeId, after, actor.getEmployeeId(), now)
                : settingRepository.updateIfVersion(employeeId, version, after, actor.getEmployeeId(), now);
        if (written == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "APPROVER_SETTING_CHANGED", "這位員工的指定審核人已被其他管理員修改，請重新載入後再操作");
        }
        auditLog.setting(employeeId, before, after, reason, actor.getEmployeeId(), now);
        return settingOf(employee, settingRepository.findById(employeeId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<LeaveHistoryEntry> settingHistory(EmployeeUserDetails actor, Long employeeId) {
        requireAdmin(actor);
        if (!employeeRepository.existsById(employeeId)) {
            throw employeeNotFound();
        }
        return views.history(historyRepository.findTop100BySubjectEmployeeIdOrderByIdDesc(employeeId));
    }

    // ================================================================ 內部

    private ApproverSettingResponse settingOf(Employee employee, LeaveApproverSetting setting) {
        Long designatedId = setting == null ? null : setting.getApproverId();
        Map<Long, Employee> people = views.load(Stream.of(designatedId, employee.getManagerId()));
        return new ApproverSettingResponse(
                new EmployeeBrief(employee.getId(), employee.getEmployeeNo(), employee.getDisplayName(), employee.getRole(), employee.isEnabled()),
                LeaveViews.approver(designatedId == null ? null : people.get(designatedId)),
                LeaveViews.approver(employee.getManagerId() == null ? null : people.get(employee.getManagerId())),
                LeaveRequestService.previewOf(approverResolver.resolve(employee)),
                setting == null ? 0 : setting.getVersion(),
                setting == null ? null : setting.getUpdatedAt());
    }

    private Employee requireValidApprover(Long approverId) {
        Employee approver = employeeRepository.findById(approverId).orElseThrow(LeaveAdminService::employeeNotFound);
        if (!ApproverResolver.canApprove(approver)) {
            throw new ApiException(HttpStatus.CONFLICT, "APPROVER_INVALID", "審核人必須是啟用中的主管或管理員");
        }
        return approver;
    }

    private static void requireAdmin(EmployeeUserDetails actor) {
        if (actor == null || actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("administrator role required");
        }
    }

    private static ApiException approverIsApplicant() {
        return new ApiException(HttpStatus.BAD_REQUEST, "APPROVER_IS_APPLICANT", "審核人不能是申請人本人");
    }

    private static ApiException noChange(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "NO_CHANGE", message);
    }

    private static ApiException employeeNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND", "找不到這位員工");
    }

    private Instant now() {
        return BusinessTime.truncateToDatabasePrecision(clock.instant());
    }
}
