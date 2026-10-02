package com.renewflow.eip.geofence;

import com.renewflow.eip.geofence.GeofenceDtos.HistoryListResponse;
import com.renewflow.eip.geofence.GeofenceDtos.SettingsResponse;
import com.renewflow.eip.geofence.GeofenceDtos.UpdateSettingsRequest;
import com.renewflow.eip.identity.EmployeeUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端定位設定（/api/admin/geofence）。URL 規則要求 ROLE_ADMIN，Service 內再檢查一次；操作者一律取自 Session。 */
@RestController
@RequestMapping("/api/admin/geofence")
public class GeofenceAdminController {

    private final GeofenceSettingsService settingsService;

    public GeofenceAdminController(GeofenceSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public SettingsResponse get(@AuthenticationPrincipal EmployeeUserDetails actor) {
        return settingsService.get(actor);
    }

    @PutMapping
    public SettingsResponse update(@AuthenticationPrincipal EmployeeUserDetails actor, @Valid @RequestBody UpdateSettingsRequest body) {
        return settingsService.update(actor, body);
    }

    @GetMapping("/history")
    public HistoryListResponse history(@AuthenticationPrincipal EmployeeUserDetails actor) {
        return new HistoryListResponse(settingsService.history(actor));
    }
}
