package com.renewflow.eip.timesheet;

import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.timesheet.TimesheetDtos.CreateEntryRequest;
import com.renewflow.eip.timesheet.TimesheetDtos.EntryResponse;
import com.renewflow.eip.timesheet.TimesheetDtos.TimesheetListResponse;
import com.renewflow.eip.timesheet.TimesheetDtos.UpdateEntryRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 本人工時（/api/timesheets/me）。路徑裡沒有員工 ID，本文也不接受 employeeId：員工一律取自 Session。
 * 所有角色（EMPLOYEE、MANAGER、ADMIN）都只能操作自己的工時；MANAGER 不因角色而看得到別人的工時。
 */
@RestController
@RequestMapping("/api/timesheets/me")
public class TimesheetController {

    private final TimesheetService timesheetService;

    public TimesheetController(TimesheetService timesheetService) {
        this.timesheetService = timesheetService;
    }

    @GetMapping
    public TimesheetListResponse list(@AuthenticationPrincipal EmployeeUserDetails principal,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return timesheetService.list(principal.getEmployeeId(), from, to);
    }

    @GetMapping("/{id}")
    public EntryResponse get(@AuthenticationPrincipal EmployeeUserDetails principal, @PathVariable Long id) {
        return timesheetService.get(principal.getEmployeeId(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EntryResponse create(@AuthenticationPrincipal EmployeeUserDetails principal, @Valid @RequestBody CreateEntryRequest body) {
        return timesheetService.create(principal.getEmployeeId(), body);
    }

    @PutMapping("/{id}")
    public EntryResponse update(@AuthenticationPrincipal EmployeeUserDetails principal, @PathVariable Long id,
                                @Valid @RequestBody UpdateEntryRequest body) {
        return timesheetService.update(principal.getEmployeeId(), id, body);
    }
}
