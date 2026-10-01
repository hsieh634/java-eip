package com.renewflow.eip.attendance;

import com.renewflow.eip.attendance.AttendanceDtos.AdminRecordsResponse;
import com.renewflow.eip.attendance.AttendanceDtos.ApproveRequest;
import com.renewflow.eip.attendance.AttendanceDtos.AttendanceRequestResponse;
import com.renewflow.eip.attendance.AttendanceDtos.DirectCorrectionRequest;
import com.renewflow.eip.attendance.AttendanceDtos.DirectVoidRequest;
import com.renewflow.eip.attendance.AttendanceDtos.EmployeeSummary;
import com.renewflow.eip.attendance.AttendanceDtos.HistoryListResponse;
import com.renewflow.eip.attendance.AttendanceDtos.RejectRequest;
import com.renewflow.eip.attendance.AttendanceDtos.RequestListResponse;
import com.renewflow.eip.identity.EmployeeUserDetails;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 管理端（/api/admin/attendance/**）。URL 規則要求 ROLE_ADMIN，Service 內再檢查一次；
 * 這裡是唯一可以指定「受管理員工」（employeeId）的地方。操作者一律取自 Session。
 */
@RestController
@RequestMapping("/api/admin/attendance")
public class AttendanceAdminController {

    private final AttendanceAdminService adminService;

    public AttendanceAdminController(AttendanceAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/requests")
    public RequestListResponse requests(@AuthenticationPrincipal EmployeeUserDetails actor,
                                        @RequestParam(required = false) String status) {
        return new RequestListResponse(adminService.listRequests(actor, status));
    }

    @GetMapping("/requests/{id}")
    public AttendanceRequestResponse request(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long id) {
        return adminService.getRequest(actor, id);
    }

    @PostMapping("/requests/{id}/approve")
    public AttendanceRequestResponse approve(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long id,
                                             @Valid @RequestBody(required = false) ApproveRequest body) {
        return adminService.approve(actor, id, body == null ? null : body.comment());
    }

    @PostMapping("/requests/{id}/reject")
    public AttendanceRequestResponse reject(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long id,
                                            @Valid @RequestBody RejectRequest body) {
        return adminService.reject(actor, id, body.reason());
    }

    @GetMapping("/employees")
    public List<EmployeeSummary> employees(@AuthenticationPrincipal EmployeeUserDetails actor) {
        return adminService.employees(actor);
    }

    @GetMapping("/records")
    public AdminRecordsResponse records(
            @AuthenticationPrincipal EmployeeUserDetails actor,
            @RequestParam Long employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return adminService.records(actor, employeeId, from, to);
    }

    @PostMapping("/records/correct")
    public AttendanceRecordResponse correct(@AuthenticationPrincipal EmployeeUserDetails actor,
                                            @Valid @RequestBody DirectCorrectionRequest body) {
        return adminService.correctDirectly(actor, body);
    }

    @PostMapping("/records/void")
    public AttendanceRecordResponse voidToday(@AuthenticationPrincipal EmployeeUserDetails actor,
                                              @Valid @RequestBody DirectVoidRequest body) {
        return adminService.voidToday(actor, body);
    }

    @GetMapping("/history")
    public HistoryListResponse history(
            @AuthenticationPrincipal EmployeeUserDetails actor,
            @RequestParam Long employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return new HistoryListResponse(adminService.history(actor, employeeId, from, to));
    }
}
