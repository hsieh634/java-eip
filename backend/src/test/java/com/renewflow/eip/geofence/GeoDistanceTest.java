package com.renewflow.eip.geofence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Haversine 與範圍判斷的單元測試（純計算，不需要資料庫）。 */
class GeoDistanceTest {

    /** 沿經線每 1 度的弧長：R × π / 180。 */
    private static final double METERS_PER_DEGREE = GeoDistance.EARTH_RADIUS_METERS * Math.PI / 180;

    @Test
    @DisplayName("同一點距離為 0（不會因浮點誤差變成 NaN）")
    void samePointIsZero() {
        assertThat(GeoDistance.haversineMeters(25.033964, 121.564468, 25.033964, 121.564468)).isZero();
        assertThat(GeoDistance.haversineMeters(0, 0, 0, 0)).isZero();
    }

    @Test
    @DisplayName("已知距離：沿經線 0.001 度 ≈ 111.195 公尺；赤道上經度 1 度 ≈ 111,195 公尺；對蹠點 = πR")
    void knownDistances() {
        assertThat(GeoDistance.haversineMeters(25.0, 121.5, 25.001, 121.5)).isCloseTo(METERS_PER_DEGREE * 0.001, within(1e-6));
        assertThat(GeoDistance.haversineMeters(0, 0, 0, 1)).isCloseTo(METERS_PER_DEGREE, within(1e-6));
        assertThat(GeoDistance.haversineMeters(0, 0, 0, 180)).isCloseTo(Math.PI * GeoDistance.EARTH_RADIUS_METERS, within(1e-3));
        assertThat(GeoDistance.haversineMeters(90, 0, -90, 0)).as("南北極").isCloseTo(Math.PI * GeoDistance.EARTH_RADIUS_METERS, within(1e-3));
        // 與方向無關
        assertThat(GeoDistance.haversineMeters(25.0478, 121.5170, 25.0340, 121.5645))
                .isCloseTo(GeoDistance.haversineMeters(25.0340, 121.5645, 25.0478, 121.5170), within(1e-9));
    }

    @Test
    @DisplayName("跨越經度 ±180：兩點實際相距約 111 公尺，不是繞地球半圈")
    void crossesAntimeridian() {
        assertThat(GeoDistance.haversineMeters(0, 179.9995, 0, -179.9995)).isCloseTo(METERS_PER_DEGREE * 0.001, within(1e-3));
    }

    @Test
    @DisplayName("範圍判斷：距離 = 半徑 → 範圍內；比半徑多一點點（未取整）→ 範圍外")
    void boundaryIsInclusiveAndNotRounded() {
        assertThat(GeoDistance.isWithin(100.0, 100)).isTrue();
        assertThat(GeoDistance.isWithin(99.999, 100)).isTrue();
        assertThat(GeoDistance.isWithin(Math.nextUp(100.0), 100)).isFalse();
        assertThat(GeoDistance.isWithin(100.4, 100)).as("若先四捨五入成 100 就會誤放行").isFalse();
    }

    @Test
    @DisplayName("適用操作：BOTH 兩者都限制；CLOCK_IN／CLOCK_OUT 只限制自己")
    void scopeCoverage() {
        assertThat(GeofenceScope.BOTH.covers(ClockAction.CLOCK_IN)).isTrue();
        assertThat(GeofenceScope.BOTH.covers(ClockAction.CLOCK_OUT)).isTrue();
        assertThat(GeofenceScope.CLOCK_IN.covers(ClockAction.CLOCK_IN)).isTrue();
        assertThat(GeofenceScope.CLOCK_IN.covers(ClockAction.CLOCK_OUT)).isFalse();
        assertThat(GeofenceScope.CLOCK_OUT.covers(ClockAction.CLOCK_IN)).isFalse();
        assertThat(GeofenceScope.CLOCK_OUT.covers(ClockAction.CLOCK_OUT)).isTrue();
    }
}
