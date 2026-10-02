package com.renewflow.eip.geofence;

import java.time.Instant;

/**
 * 一次打卡的定位判斷結果（已通過）。setting 是本次實際採用的設定快照；
 * 不需要定位時，座標、精度、時間與距離都是 null（不蒐集也不保存）。
 */
public record LocationDecision(
        GeofenceSnapshot setting,
        boolean required,
        Double latitude,
        Double longitude,
        Double accuracyMeters,
        Instant capturedAt,
        Double distanceMeters) {

    static LocationDecision notRequired(GeofenceSnapshot setting) {
        return new LocationDecision(setting, false, null, null, null, null, null);
    }
}
