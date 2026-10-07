package com.renewflow.eip.geofence;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 定位打卡與設定的 DTO。
 * Bean Validation 只負責「格式與範圍」（→ 400 VALIDATION_FAILED）；
 * 「有限數值、成對、啟用需要中心、精度、時間窗口、距離」等業務規則在 Service（→ 400／422／403）。
 */
public final class GeofenceDtos {

    private GeofenceDtos() {
    }

    /**
     * 每次邏輯打卡必填 requestId；定位可省略，下班須有指定 ID＋版本。
     * 沒有 distance、inside、中心或半徑欄位——那些一律由後端決定；前端多送的欄位會被忽略。
     */
    public record ClockLocationRequest(
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @DecimalMin("0") Double accuracy,
            Instant capturedAt,
            @NotNull java.util.UUID requestId, Long expectedAttendanceId, Long expectedVersion) {
        public ClockLocationRequest(Double latitude, Double longitude, Double accuracy, Instant capturedAt) {
            this(latitude, longitude, accuracy, capturedAt, null, null, null);
        }
    }

    /** 員工端：本次打卡是否需要定位。不含公司中心座標（最小揭露）。 */
    public record LocationPolicyResponse(
            boolean enabled,
            boolean clockInRequired,
            boolean clockOutRequired,
            int radiusMeters,
            double maxAccuracyMeters,
            long version) {

        static LocationPolicyResponse of(GeofenceSnapshot s) {
            return new LocationPolicyResponse(s.enabled(), s.requiresLocation(ClockAction.CLOCK_IN), s.requiresLocation(ClockAction.CLOCK_OUT),
                    s.radiusMeters(), GeofenceRules.MAX_ACCURACY_METERS, s.version());
        }
    }

    public record ActorSummary(Long id, String employeeNo, String displayName) {
    }

    public record Limits(int radiusMin, int radiusMax, double maxAccuracyMeters) {
        static final Limits CURRENT = new Limits(GeofenceRules.RADIUS_MIN_METERS, GeofenceRules.RADIUS_MAX_METERS, GeofenceRules.MAX_ACCURACY_METERS);
    }

    /** 管理端：目前的設定（含版本與最近修改人）。 */
    public record SettingsResponse(
            boolean enabled,
            Double latitude,
            Double longitude,
            int radiusMeters,
            GeofenceScope scope,
            long version,
            Instant updatedAt,
            ActorSummary updatedBy,
            Limits limits) {
    }

    /** 管理端：修改設定。expectedVersion 是畫面載入時的版本；原因必填。 */
    public record UpdateSettingsRequest(
            @NotNull Boolean enabled,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull @Min(GeofenceRules.RADIUS_MIN_METERS) @Max(GeofenceRules.RADIUS_MAX_METERS) Integer radiusMeters,
            @NotNull GeofenceScope scope,
            @NotNull Long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record SettingValues(boolean enabled, Double latitude, Double longitude, int radiusMeters, GeofenceScope scope) {
        static SettingValues of(GeofenceSnapshot s) {
            return new SettingValues(s.enabled(), s.centerLatitude(), s.centerLongitude(), s.radiusMeters(), s.scope());
        }
    }

    public record HistoryEntryResponse(
            Long id,
            long version,
            SettingValues before,
            SettingValues after,
            String reason,
            ActorSummary actor,
            Instant occurredAt) {
    }

    public record HistoryListResponse(List<HistoryEntryResponse> entries) {
    }
}
