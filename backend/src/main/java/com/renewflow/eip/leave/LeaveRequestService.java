package com.renewflow.eip.leave;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.EmployeeRepository;
import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.leave.ApproverResolver.Resolution;
import com.renewflow.eip.leave.LeaveDtos.ApproveRequest;
import com.renewflow.eip.leave.LeaveDtos.ApproverPreview;
import com.renewflow.eip.leave.LeaveDtos.LeaveDetail;
import com.renewflow.eip.leave.LeaveDtos.LeavePage;
import com.renewflow.eip.leave.LeaveDtos.RejectRequest;
import com.renewflow.eip.leave.LeaveDtos.SubmitLeaveRequest;
import com.renewflow.eip.leave.LeaveDtos.SubmitResult;
import com.renewflow.eip.leave.LeaveDtos.WithdrawRequest;
import com.renewflow.eip.leave.LeaveEnums.HistoryAction;
import com.renewflow.eip.leave.LeaveEnums.LeaveStatus;
import com.renewflow.eip.leave.LeaveRules.Query;
import com.renewflow.eip.leave.LeaveRules.Submission;
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

/**
 * 請假：本人（送出、查詢、撤回）與審核人（查詢、核准、退回）。
 *
 * <p>資料範圍：本人的查詢一律以 employee_id = 登入者；審核人的查詢一律以 approver_id = 登入者。
 * 不在範圍內與不存在一樣回 404。ADMIN 也只能核准或退回「指派給自己」的申請。
 *
 * <p>每個寫入都在交易開頭 {@link OrganizationLock#acquireFor}：取鎖（與組織寫入、其他請假寫入排隊），
 * 再重新確認操作者仍有效，之後才讀取最新的申請、組織資料與重疊資料。
 */
@Service
public class LeaveRequestService {

    private final LeaveRequestRepository requestRepository;
    private final EmployeeRepository employeeRepository;
    private final ApproverResolver approverResolver;
    private final LeaveAuditLog auditLog;
    private final LeaveViews views;
    private final OrganizationLock lock;
    private final Clock clock;
    private final LeaveRevisionService revisions;

    LeaveRequestService(LeaveRequestRepository requestRepository, EmployeeRepository employeeRepository, ApproverResolver approverResolver,
                        LeaveAuditLog auditLog, LeaveViews views, OrganizationLock lock, Clock clock, LeaveRevisionService revisions) {
        this.requestRepository = requestRepository;
        this.employeeRepository = employeeRepository;
        this.approverResolver = approverResolver;
        this.auditLog = auditLog;
        this.views = views;
        this.lock = lock;
        this.clock = clock;
        this.revisions=revisions;
    }

    // ================================================================ 本人

    @Transactional(readOnly = true)
    public LeavePage listMine(EmployeeUserDetails actor, Query query) {
        return views.page(requestRepository.search(false, actor.getEmployeeId(), true, -1L, query.statuses(),
                query.anyDate(), query.from(), query.to(), query.page()));
    }

    @Transactional(readOnly = true)
    public LeaveDetail getMine(EmployeeUserDetails actor, Long id) {
        return views.detail(requestRepository.findByIdAndEmployeeId(id, actor.getEmployeeId()).orElseThrow(LeaveRules::notFound));
    }

    /** 預計審核人：只是提示，送出時後端在鎖內重新判斷。 */
    @Transactional(readOnly = true)
    public ApproverPreview preview(EmployeeUserDetails actor) {
        Employee me = employeeRepository.findById(actor.getEmployeeId()).orElseThrow();
        return previewOf(approverResolver.resolve(me));
    }

    static ApproverPreview previewOf(Resolution r) {
        return new ApproverPreview(LeaveViews.person(r.approver()), r.ok() ? r.source().name() : "NONE", r.problem());
    }

    /**
     * 送出。順序：正規化（鎖外）→ 取鎖並確認操作者 → 同 requestId？（相同內容回傳原申請；不同內容 409）
     * → 決定審核人 → 重疊檢查 → 新增 → 歷程。
     */
    @Transactional
    public SubmitResult submit(EmployeeUserDetails actor, SubmitLeaveRequest body) {
        Long me = actor.getEmployeeId();
        lock.acquireFor(actor);
        Instant now = now();
        // 先辨識原提交；確認重送不因一年後日期窗口或新審核設定而失敗。
        LeaveRequest existing = requestRepository.findByEmployeeIdAndRequestId(me, body.requestId()).orElse(null);
        Submission s = LeaveRules.normalize(body.leaveType(), body.workDate(), body.startTime(), body.endTime(), body.reason(),
                existing == null ? BusinessTime.workDateOf(now) : body.workDate());
        if (existing != null) {
            // 同一次提交的重送：沿用原申請的狀態與審核人（即使已撤回也不重建），不寫歷程
            if (existing.getOperation()!=com.renewflow.eip.leave.LeaveEnums.Operation.CREATE || !existing.sameContentAs(s)) {
                throw new ApiException(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT",
                        "這個提交識別碼已用於另一份內容不同的申請（#" + existing.getId() + "），沒有修改原申請",
                        Map.of("existingRequestId", existing.getId()));
            }
            return new SubmitResult(true, views.request(existing));
        }

        Resolution resolution = approverResolver.resolve(employeeRepository.findById(me).orElseThrow());
        if (!resolution.ok()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_VALID_APPROVER", resolution.problem());
        }
        List<LeaveRequest> overlapping = requestRepository.findOverlapping(me, s.workDate(), s.startTime(), s.endTime());
        if (!overlapping.isEmpty()) {
            LeaveRequest other = overlapping.getFirst();
            throw new ApiException(HttpStatus.CONFLICT, "LEAVE_OVERLAP",
                    "與你在 %s %s～%s 的%s申請（#%d）時段重疊".formatted(other.getWorkDate(), other.getStartTime(), other.getEndTime(),
                            other.getStatus() == LeaveStatus.APPROVED ? "已核准" : "待審", other.getId()),
                    Map.of("conflictingRequestId", other.getId()));
        }
        Long approverId = resolution.approver().getId();
        Long id = requestRepository.insertIfNew(me, body.requestId(), s.leaveType().name(), s.workDate(), s.startTime(), s.endTime(),
                        s.minutes(), s.reason(), approverId, resolution.source().name(), now)
                // 鎖內不會有同員工、同 requestId 的並行新增；這是唯一限制的最後一道防線
                .orElseThrow(() -> new IllegalStateException("duplicate requestId inside organization lock"));
        auditLog.request(id, HistoryAction.SUBMITTED, null, LeaveStatus.PENDING, null, approverId, null, me, now);
        return new SubmitResult(false, views.request(requestRepository.findById(id).orElseThrow()));
    }

    /** 撤回本人待審申請（原因選填）。 */
    @Transactional
    public LeaveDetail withdraw(EmployeeUserDetails actor, Long id, WithdrawRequest body) {
        String reason = LeaveRules.optionalReason(body.reason());
        lock.acquireFor(actor);
        LeaveRequest r = requestRepository.findByIdAndEmployeeId(id, actor.getEmployeeId()).orElseThrow(LeaveRules::notFound);
        return decide(actor, r, body.expectedVersion(), LeaveStatus.WITHDRAWN, HistoryAction.WITHDRAWN, reason, null);
    }

    // ================================================================ 審核人

    /** 指派給我的申請。角色是 EMPLOYEE → 403（沒有審核功能）。 */
    @Transactional(readOnly = true)
    public LeavePage listApprovals(EmployeeUserDetails actor, Query query) {
        if (actor.getRole() == Role.EMPLOYEE) {
            throw new AccessDeniedException("approver role required");
        }
        return views.approvalPage(requestRepository.search(true, -1L, false, actor.getEmployeeId(), query.statuses(),
                query.anyDate(), query.from(), query.to(), query.page()));
    }

    @Transactional(readOnly = true)
    public LeaveDetail getApproval(EmployeeUserDetails actor, Long id) {
        return views.approvalDetail(findAssignedToMe(actor, id));
    }

    @Transactional
    public LeaveDetail approve(EmployeeUserDetails actor, Long id, ApproveRequest body) {
        lock.acquireFor(actor);
        return decide(actor, findAssignedToMe(actor, id), body.expectedVersion(), LeaveStatus.APPROVED, HistoryAction.APPROVED, null, body.evidenceToken());
    }

    @Transactional
    public LeaveDetail reject(EmployeeUserDetails actor, Long id, RejectRequest body) {
        String reason = LeaveRules.requireReason(body.reason(), "退回原因");
        lock.acquireFor(actor);
        return decide(actor, findAssignedToMe(actor, id), body.expectedVersion(), LeaveStatus.REJECTED, HistoryAction.REJECTED, reason, body.evidenceToken());
    }

    // ================================================================ 內部

    /** 只有「目前指派給我」而且我目前是 MANAGER／ADMIN 才找得到；改派走的、別人的、不存在的都是 404。 */
    private LeaveRequest findAssignedToMe(EmployeeUserDetails actor, Long id) {
        if (actor.getRole() == Role.EMPLOYEE) {
            throw LeaveRules.notFound();
        }
        return requestRepository.findByIdAndApproverId(id, actor.getEmployeeId()).orElseThrow(LeaveRules::notFound);
    }

    /** 結案：狀態必須仍是待審、版本必須相符；條件 UPDATE 再保證一次。 */
    private LeaveDetail decide(EmployeeUserDetails actor, LeaveRequest r, long expectedVersion, LeaveStatus to, HistoryAction action,
                               String reason, String evidenceToken) {
        if (r.getStatus() != LeaveStatus.PENDING) {
            throw LeaveRules.notPending(r.getStatus());
        }
        if (r.getVersion() != expectedVersion) {
            throw LeaveRules.changed();
        }
        revisions.validateBase(r);
        var prepared=to==LeaveStatus.WITHDRAWN ? null : revisions.prepareDecision(r,evidenceToken,to==LeaveStatus.APPROVED);
        Instant now = now();
        if (requestRepository.decideIfPending(r.getId(), expectedVersion, to.name(), actor.getEmployeeId(), reason, now) == 0) {
            throw LeaveRules.changed();
        }
        if(prepared==null) auditLog.request(r.getId(), action, LeaveStatus.PENDING, to, null, null, reason, actor.getEmployeeId(), now);
        else auditLog.decision(r.getId(),action,to,reason,actor.getEmployeeId(),now,revisions.finishDecision(r,prepared,to==LeaveStatus.APPROVED));
        var result=requestRepository.findById(r.getId()).orElseThrow();
        return to==LeaveStatus.WITHDRAWN?views.detail(result):views.approvalDetail(result);
    }

    private Instant now() {
        return BusinessTime.truncateToDatabasePrecision(clock.instant());
    }
}
