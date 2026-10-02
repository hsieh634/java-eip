package com.renewflow.eip.attendance;

import com.renewflow.eip.geofence.GeofenceDtos.ClockLocationRequest;
import com.renewflow.eip.geofence.GeofenceDtos.LocationPolicyResponse;
import com.renewflow.eip.geofence.GeofenceGate;
import com.renewflow.eip.identity.EmployeeUserDetails;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * HTTP 邊界。重點：這裡的每個方法都沒有「員工 ID／日期／時間」參數可以讓前端指定——
 * 員工來自 Session（AuthenticationPrincipal），時間來自伺服器。多餘的查詢參數與本文欄位都會被忽略。
 * T03.2：打卡本文只能帶「原始定位資料」（可省略）；是否需要定位、是否在範圍內一律由後端判斷。
 * 未登入／帳號失效已在更早的 Security Filter 處理，不會進到這裡。
 */
@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {

    private final AttendanceService attendanceService;
    private final GeofenceGate geofenceGate;

    public AttendanceController(AttendanceService attendanceService, GeofenceGate geofenceGate) {
        this.attendanceService = attendanceService;
        this.geofenceGate = geofenceGate;
    }

    /** 本文可省略（required = false）：不受定位限制時，T03 的無本文請求照常運作。 */
    @PostMapping("/clock-in")
    public AttendanceRecordResponse clockIn(@AuthenticationPrincipal EmployeeUserDetails principal,
                                            @Valid @RequestBody(required = false) ClockLocationRequest location) {
        return attendanceService.clockIn(principal.getEmployeeId(), location);
    }

    @PostMapping("/clock-out")
    public AttendanceRecordResponse clockOut(@AuthenticationPrincipal EmployeeUserDetails principal,
                                             @Valid @RequestBody(required = false) ClockLocationRequest location) {
        return attendanceService.clockOut(principal.getEmployeeId(), location);
    }

    /** 按下打卡時取得最新的定位要求（不含公司中心座標）。 */
    @GetMapping("/location-policy")
    public LocationPolicyResponse locationPolicy() {
        return geofenceGate.policy();
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
