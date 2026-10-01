package com.renewflow.eip.attendance;

import com.renewflow.eip.attendance.AttendanceDtos.AttendanceRequestResponse;
import com.renewflow.eip.attendance.AttendanceDtos.CreateRequest;
import com.renewflow.eip.attendance.AttendanceDtos.HistoryListResponse;
import com.renewflow.eip.attendance.AttendanceDtos.RequestListResponse;
import com.renewflow.eip.identity.EmployeeUserDetails;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 員工端：申請與歷程。員工 ID 一律取自 Session（AuthenticationPrincipal）；
 * 請求本文沒有任何員工、操作者或時間戳欄位可以指定。
 */
@RestController
@RequestMapping("/api/attendance")
public class AttendanceRequestController {

    private final AttendanceRequestService requestService;

    public AttendanceRequestController(AttendanceRequestService requestService) {
        this.requestService = requestService;
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public AttendanceRequestResponse submit(@AuthenticationPrincipal EmployeeUserDetails principal,
                                            @Valid @RequestBody CreateRequest body) {
        return requestService.submit(principal.getEmployeeId(), body);
    }

    @GetMapping("/requests")
    public RequestListResponse mine(@AuthenticationPrincipal EmployeeUserDetails principal) {
        return new RequestListResponse(requestService.listMine(principal.getEmployeeId()));
    }

    @PostMapping("/requests/{id}/cancel")
    public AttendanceRequestResponse cancel(@AuthenticationPrincipal EmployeeUserDetails principal, @PathVariable Long id) {
        return requestService.cancel(principal.getEmployeeId(), id);
    }

    @GetMapping("/me/history")
    public HistoryListResponse history(@AuthenticationPrincipal EmployeeUserDetails principal) {
        return new HistoryListResponse(requestService.historyOf(principal.getEmployeeId()));
    }
}
