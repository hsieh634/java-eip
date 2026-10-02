package com.renewflow.eip.geofence;

import java.time.Instant;
import java.util.Objects;

/**
 * 某一版本的完整定位設定。打卡判斷只依賴一份快照，所以中心、半徑、開關不會來自不同版本。
 * centerLatitude／centerLongitude 為 null＝尚未設定公司中心（0 是合法座標，不能用真假值判斷）。
 */
public record GeofenceSnapshot(
        long version,
        boolean enabled,
        Double centerLatitude,
        Double centerLongitude,
        int radiusMeters,
        GeofenceScope scope,
        Long updatedBy,
        Instant updatedAt) {

    public boolean hasCenter() {
        return centerLatitude != null && centerLongitude != null;
    }

    /** 本次操作是否需要定位：啟用、且適用操作包含它。 */
    public boolean requiresLocation(ClockAction action) {
        return enabled && scope.covers(action);
    }

    /** 只比較設定內容（不含版本與修改人），用來判斷「沒有任何變更」。 */
    public boolean sameSettingsAs(boolean otherEnabled, Double otherLatitude, Double otherLongitude, int otherRadius, GeofenceScope otherScope) {
        return enabled == otherEnabled
                && Objects.equals(centerLatitude, otherLatitude)
                && Objects.equals(centerLongitude, otherLongitude)
                && radiusMeters == otherRadius
                && scope == otherScope;
    }
}
