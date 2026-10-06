package com.renewflow.eip.leave;

import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.leave.LeaveDtos.ApproverSettingList;
import com.renewflow.eip.leave.LeaveDtos.ApproverSettingResponse;
import com.renewflow.eip.leave.LeaveDtos.HistoryList;
import com.renewflow.eip.leave.LeaveDtos.LeaveDetail;
import com.renewflow.eip.leave.LeaveDtos.LeavePage;
import com.renewflow.eip.leave.LeaveDtos.ReassignRequest;
import com.renewflow.eip.leave.LeaveDtos.UpdateApproverSettingRequest;
import com.renewflow.eip.leave.LeaveEnums.StatusFilter;
import com.renewflow.eip.leave.LeaveRules.Query;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 請假管理的 HTTP 邊界（/api/admin/leave/**）：URL 規則要求 ROLE_ADMIN，Service 內再檢查一次。 */
@RestController
public class LeaveAdminController {

    private final LeaveAdminService service;

    public LeaveAdminController(LeaveAdminService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/leave/requests")
    public LeavePage requests(@AuthenticationPrincipal EmployeeUserDetails actor,
                              @RequestParam(required = false) StatusFilter status,
                              @RequestParam(required = false) Long employeeId,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                              @RequestParam(required = false) Integer page,
                              @RequestParam(required = false) Integer size) {
        return service.search(actor, Query.of(status, from, to, page, size), employeeId);
    }

    @GetMapping("/api/admin/leave/requests/{id}")
    public LeaveDetail request(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long id) {
        return service.get(actor, id);
    }

    @PostMapping("/api/admin/leave/requests/{id}/reassign")
    public LeaveDetail reassign(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long id,
                                @Valid @RequestBody ReassignRequest body) {
        return service.reassign(actor, id, body);
    }

    @GetMapping("/api/admin/leave/approver-settings")
    public ApproverSettingList settings(@AuthenticationPrincipal EmployeeUserDetails actor) {
        return new ApproverSettingList(service.listSettings(actor));
    }

    @GetMapping("/api/admin/leave/approver-settings/{employeeId}")
    public ApproverSettingResponse setting(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long employeeId) {
        return service.getSetting(actor, employeeId);
    }

    @PutMapping("/api/admin/leave/approver-settings/{employeeId}")
    public ApproverSettingResponse updateSetting(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long employeeId,
                                                 @Valid @RequestBody UpdateApproverSettingRequest body) {
        return service.updateSetting(actor, employeeId, body);
    }

    @GetMapping("/api/admin/leave/approver-settings/{employeeId}/history")
    public HistoryList settingHistory(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long employeeId) {
        return new HistoryList(service.settingHistory(actor, employeeId));
    }
}
