package com.renewflow.eip.attendance;

import com.renewflow.eip.identity.EmployeeUserDetails;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * HTTP 邊界。重點：這裡的每個方法都沒有「員工 ID／日期／時間」參數可以讓前端指定——
 * 員工來自 Session（AuthenticationPrincipal），時間來自伺服器。請求本文、多餘的查詢參數都會被忽略。
 * 未登入／帳號失效已在更早的 Security Filter 處理，不會進到這裡。
 */
@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {

    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @PostMapping("/clock-in")
    public AttendanceRecordResponse clockIn(@AuthenticationPrincipal EmployeeUserDetails principal) {
        return attendanceService.clockIn(principal.getEmployeeId());
    }

    @PostMapping("/clock-out")
    public AttendanceRecordResponse clockOut(@AuthenticationPrincipal EmployeeUserDetails principal) {
        return attendanceService.clockOut(principal.getEmployeeId());
    }

    @GetMapping("/me/today")
    public TodayAttendanceResponse today(@AuthenticationPrincipal EmployeeUserDetails principal) {
        return attendanceService.today(principal.getEmployeeId());
    }

    @GetMapping("/me")
    public AttendanceListResponse list(
            @AuthenticationPrincipal EmployeeUserDetails principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendanceService.list(principal.getEmployeeId(), from, to);
    }
}
