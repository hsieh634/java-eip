package com.renewflow.eip.geofence;

import com.renewflow.eip.geofence.GeofenceDtos.ActorSummary;
import com.renewflow.eip.geofence.GeofenceDtos.HistoryEntryResponse;
import com.renewflow.eip.geofence.GeofenceDtos.Limits;
import com.renewflow.eip.geofence.GeofenceDtos.SettingValues;
import com.renewflow.eip.geofence.GeofenceDtos.SettingsResponse;
import com.renewflow.eip.geofence.GeofenceDtos.UpdateSettingsRequest;
import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.EmployeeRepository;
import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.shared.time.BusinessTime;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理端的定位設定：讀取、修改（版本比對＋原因＋歷程）、歷程查詢。
 *
 * <p>每個公開方法先確認呼叫者是 ADMIN（縱深防禦：/api/admin/** 的 URL 規則已擋一次，ActiveAccountFilter 確認角色是最新）。
 * admin 與 developer 都是 ADMIN，權限完全相同；這裡沒有任何「定位繞過」的特例。
 */
@Service
public class GeofenceSettingsService {

    private final GeofenceSettingRepository settingRepository;
    private final GeofenceSettingHistoryRepository historyRepository;
    private final GeofenceSettingAuditLog auditLog;
    private final EmployeeRepository employeeRepository;
    private final Clock clock;

    GeofenceSettingsService(GeofenceSettingRepository settingRepository,
                            GeofenceSettingHistoryRepository historyRepository,
                            GeofenceSettingAuditLog auditLog,
                            EmployeeRepository employeeRepository,
                            Clock clock) {
        this.settingRepository = settingRepository;
        this.historyRepository = historyRepository;
        this.auditLog = auditLog;
        this.employeeRepository = employeeRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SettingsResponse get(EmployeeUserDetails actor) {
        requireAdmin(actor);
        return response(settingRepository.current().orElseThrow(GeofenceSettingsService::missing).snapshot());
    }

    /**
     * 修改設定。順序：驗證輸入 → 鎖住設定列（等待進行中的打卡）→ 比對版本 → 條件式 UPDATE → 寫歷程。
     * 全部在同一個交易：歷程寫入失敗時，設定也回滾。
     */
    @Transactional
    public SettingsResponse update(EmployeeUserDetails actor, UpdateSettingsRequest body) {
        requireAdmin(actor);
        String reason = body.reason().trim();
        if (reason.isEmpty()) {
            throw invalid("請填寫修改原因");
        }
        Double latitude = body.latitude();
        Double longitude = body.longitude();
        // 0 是合法座標：一律用 null 判斷「沒有填」，不用真假值
        if ((latitude == null) != (longitude == null)) {
            throw invalid("緯度與經度必須同時填寫或同時留空");
        }
        if ((latitude != null && !Double.isFinite(latitude)) || (longitude != null && !Double.isFinite(longitude))) {
            throw invalid("座標必須是有限的數值");
        }
        boolean enabled = body.enabled();
        if (enabled && latitude == null) {
            throw invalid("啟用定位限制前，請先設定公司中心的緯度與經度");
        }
        int radius = body.radiusMeters();
        GeofenceScope scope = body.scope();

        GeofenceSnapshot before = settingRepository.lockForUpdate().orElseThrow(GeofenceSettingsService::missing).snapshot();
        if (before.version() != body.expectedVersion()) {
            throw changed();
        }
        if (before.sameSettingsAs(enabled, latitude, longitude, radius, scope)) {
            throw invalid("設定與目前相同，沒有需要儲存的變更");
        }
        Instant now = BusinessTime.truncateToDatabasePrecision(clock.instant());
        // 已持有 FOR UPDATE 鎖，版本條件理論上一定成立；仍保留作為最後一道防線
        if (settingRepository.updateIfVersion(body.expectedVersion(), enabled, latitude, longitude, radius, scope.name(),
                actor.getEmployeeId(), now) == 0) {
            throw changed();
        }
        GeofenceSnapshot after = settingRepository.current().orElseThrow(GeofenceSettingsService::missing).snapshot();
        auditLog.record(before, after, reason, actor.getEmployeeId(), now);
        return response(after);
    }

    @Transactional(readOnly = true)
    public List<HistoryEntryResponse> history(EmployeeUserDetails actor) {
        requireAdmin(actor);
        List<GeofenceSettingHistory> rows = historyRepository.findTop100ByOrderByIdDesc();
        Map<Long, Employee> actors = employeeRepository.findAllById(rows.stream().map(GeofenceSettingHistory::getActorId).distinct().toList())
                .stream().collect(Collectors.toMap(Employee::getId, Function.identity()));
        return rows.stream().map(h -> new HistoryEntryResponse(h.getId(), h.getSettingVersion(),
                SettingValues.of(h.before()), SettingValues.of(h.after()), h.getReason(),
                summary(actors.get(h.getActorId())), h.getOccurredAt())).toList();
    }

    private SettingsResponse response(GeofenceSnapshot s) {
        ActorSummary updatedBy = s.updatedBy() == null ? null : summary(employeeRepository.findById(s.updatedBy()).orElse(null));
        return new SettingsResponse(s.enabled(), s.centerLatitude(), s.centerLongitude(), s.radiusMeters(), s.scope(),
                s.version(), s.updatedAt(), updatedBy, Limits.CURRENT);
    }

    private static ActorSummary summary(Employee e) {
        return e == null ? null : new ActorSummary(e.getId(), e.getEmployeeNo(), e.getDisplayName());
    }

    private static void requireAdmin(EmployeeUserDetails actor) {
        if (actor == null || actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("administrator role required");
        }
    }

    private static GeofenceException invalid(String message) {
        return new GeofenceException(HttpStatus.BAD_REQUEST, "INVALID_SETTING", message);
    }

    private static GeofenceException changed() {
        return new GeofenceException(HttpStatus.CONFLICT, "SETTING_CHANGED", "定位設定已被其他管理員更新，請重新載入後再修改");
    }

    private static IllegalStateException missing() {
        return new IllegalStateException("geofence setting row is missing");
    }
}
