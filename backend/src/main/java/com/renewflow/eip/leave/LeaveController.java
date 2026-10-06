package com.renewflow.eip.leave;

import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.leave.LeaveDtos.ApproveRequest;
import com.renewflow.eip.leave.LeaveDtos.ApproverPreview;
import com.renewflow.eip.leave.LeaveDtos.LeaveDetail;
import com.renewflow.eip.leave.LeaveDtos.LeavePage;
import com.renewflow.eip.leave.LeaveDtos.RejectRequest;
import com.renewflow.eip.leave.LeaveDtos.SubmitLeaveRequest;
import com.renewflow.eip.leave.LeaveDtos.SubmitResult;
import com.renewflow.eip.leave.LeaveDtos.WithdrawRequest;
import com.renewflow.eip.leave.LeaveEnums.StatusFilter;
import com.renewflow.eip.leave.LeaveRules.Query;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 請假的 HTTP 邊界：本人（/api/leave/me/**）與審核人（/api/leave/approvals/**）。
 * 已登入即可呼叫；申請人與審核人一律取自 Session，資料範圍在 Service 檢查。
 */
@RestController
public class LeaveController {

    private final LeaveRequestService service;

    public LeaveController(LeaveRequestService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------- 本人

    @GetMapping("/api/leave/me")
    public LeavePage mine(@AuthenticationPrincipal EmployeeUserDetails actor,
                          @RequestParam(required = false) StatusFilter status,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam(required = false) Integer page,
                          @RequestParam(required = false) Integer size) {
        return service.listMine(actor, Query.of(status, from, to, page, size));
    }

    @GetMapping("/api/leave/me/approver-preview")
    public ApproverPreview preview(@AuthenticationPrincipal EmployeeUserDetails actor) {
        return service.preview(actor);
    }

    /** 201＝新建立；200＝相同 requestId＋相同內容的重送（回傳原申請，沒有新增）。 */
    @PostMapping("/api/leave/me")
    public ResponseEntity<SubmitResult> submit(@AuthenticationPrincipal EmployeeUserDetails actor,
                                               @Valid @RequestBody SubmitLeaveRequest body) {
        SubmitResult result = service.submit(actor, body);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    @GetMapping("/api/leave/me/{id}")
    public LeaveDetail mineDetail(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long id) {
        return service.getMine(actor, id);
    }

    @PostMapping("/api/leave/me/{id}/withdraw")
    public LeaveDetail withdraw(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long id,
                                @Valid @RequestBody WithdrawRequest body) {
        return service.withdraw(actor, id, body);
    }

    // ---------------------------------------------------------------- 審核人

    @GetMapping("/api/leave/approvals")
    public LeavePage approvals(@AuthenticationPrincipal EmployeeUserDetails actor,
                               @RequestParam(required = false) StatusFilter status,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size) {
        return service.listApprovals(actor, Query.of(status, from, to, page, size));
    }

    @GetMapping("/api/leave/approvals/{id}")
    public LeaveDetail approvalDetail(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long id) {
        return service.getApproval(actor, id);
    }

    @PostMapping("/api/leave/approvals/{id}/approve")
    public LeaveDetail approve(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long id,
                               @Valid @RequestBody ApproveRequest body) {
        return service.approve(actor, id, body);
    }

    @PostMapping("/api/leave/approvals/{id}/reject")
    public LeaveDetail reject(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long id,
                              @Valid @RequestBody RejectRequest body) {
        return service.reject(actor, id, body);
    }
}
