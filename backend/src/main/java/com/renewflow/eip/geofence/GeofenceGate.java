package com.renewflow.eip.geofence;

import com.renewflow.eip.geofence.GeofenceDtos.ClockLocationRequest;
import com.renewflow.eip.geofence.GeofenceDtos.LocationPolicyResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 打卡前的定位關卡。只有後端決定「是否需要定位、是否通過」：前端送來的只有原始定位資料，
 * 不接受距離、是否在範圍內、中心或半徑。
 *
 * <p>判斷順序（任何一步失敗都在寫入出勤之前丟出例外，資料不會被改動）：
 * 取得設定快照（FOR SHARE）→ 是否需要定位 → 欄位齊全 → 有限數值與範圍 → 精度 → 時間窗口 → 距離。
 */
@Service
public class GeofenceGate {

    private final GeofenceSettingRepository settingRepository;

    public GeofenceGate(GeofenceSettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    /**
     * 必須在打卡交易內呼叫（MANDATORY）：設定列的共享鎖會持有到打卡交易提交，
     * 保證「判斷用的設定」與「寫入證據的設定」是同一版本，且設定修改不會插在中間。
     *
     * @param now 伺服器時間（與出勤時間是同一個值）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LocationDecision checkForClock(ClockAction action, ClockLocationRequest location, Instant now) {
        // 讀不到設定（資料庫故障、設定列遺失）→ 例外 → 500。絕不當作「定位已關閉」放行
        GeofenceSnapshot setting = settingRepository.lockForClock()
                .orElseThrow(() -> new IllegalStateException("geofence setting row is missing"))
                .snapshot();

        if (!setting.requiresLocation(action)) {
            return LocationDecision.notRequired(setting);
        }
        if (!setting.hasCenter()) {
            // 資料庫 CHECK 已保證「啟用必有中心」；真的發生代表資料損毀，拒絕而不是放行
            throw new IllegalStateException("geofence is enabled without a center");
        }

        if (location == null || location.latitude() == null || location.longitude() == null
                || location.accuracy() == null || location.capturedAt() == null) {
            throw new GeofenceException(HttpStatus.UNPROCESSABLE_CONTENT, "LOCATION_REQUIRED",
                    "這次打卡需要定位，請允許瀏覽器取得位置後再試");
        }
        double latitude = location.latitude();
        double longitude = location.longitude();
        double accuracy = location.accuracy();
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || !Double.isFinite(accuracy)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 || accuracy < 0) {
            throw new GeofenceException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION", "定位資料不合法");
        }
        if (accuracy > GeofenceRules.MAX_ACCURACY_METERS) {
            throw new GeofenceException(HttpStatus.UNPROCESSABLE_CONTENT, "LOCATION_ACCURACY_TOO_LOW",
                    "定位精度不足（約 %d 公尺，需在 %d 公尺以內），請移到訊號較好的地方再試"
                            .formatted(Math.round(accuracy), Math.round(GeofenceRules.MAX_ACCURACY_METERS)));
        }
        Instant captured = location.capturedAt();
        if (captured.isBefore(now.minus(GeofenceRules.LOCATION_MAX_AGE)) || captured.isAfter(now.plus(GeofenceRules.LOCATION_MAX_AHEAD))) {
            throw new GeofenceException(HttpStatus.UNPROCESSABLE_CONTENT, "LOCATION_TIME_INVALID",
                    "定位時間與伺服器時間相差太多，請重新取得位置；若持續發生，請確認裝置時間是否正確");
        }

        double distance = GeoDistance.haversineMeters(setting.centerLatitude(), setting.centerLongitude(), latitude, longitude);
        if (!GeoDistance.isWithin(distance, setting.radiusMeters())) {
            throw new OutsideGeofenceException(distance, setting.radiusMeters());
        }
        return new LocationDecision(setting, true, latitude, longitude, accuracy, captured, distance);
    }

    /** 員工端顯示用：不加鎖、不含中心座標。實際打卡時仍以交易內的快照為準。 */
    @Transactional(readOnly = true)
    public LocationPolicyResponse policy() {
        GeofenceSnapshot setting = settingRepository.current()
                .orElseThrow(() -> new IllegalStateException("geofence setting row is missing"))
                .snapshot();
        return LocationPolicyResponse.of(setting);
    }
}
