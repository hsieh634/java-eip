package com.renewflow.eip.attendance;

import com.renewflow.eip.geofence.GeoDistance;
import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T03.2 測試共用基礎。公司中心是「測試用的虛構座標」（25.0, 121.5），不是任何真實公司的位置。
 * 測試位置一律放在中心的正北方：沿經線的大圓距離剛好是 R × Δφ，所以可以精確控制「距離中心幾公尺」。
 */
abstract class GeofenceTestBase extends CorrectionsTestBase {

    static final String GEOFENCE = "/api/admin/geofence";
    static final String POLICY = "/api/attendance/location-policy";
    static final double CENTER_LAT = 25.0;
    static final double CENTER_LON = 121.5;
    static final double METERS_PER_DEGREE = GeoDistance.EARTH_RADIUS_METERS * Math.PI / 180;

    /** 中心正北方 meters 公尺處的緯度。 */
    static double northOf(double meters) {
        return CENTER_LAT + meters / METERS_PER_DEGREE;
    }

    static String settingsBody(boolean enabled, Double lat, Double lon, Object radius, String scope, Object expectedVersion, String reason) {
        return "{\"enabled\":" + enabled
                + ",\"latitude\":" + (lat == null ? "null" : lat)
                + ",\"longitude\":" + (lon == null ? "null" : lon)
                + ",\"radiusMeters\":" + radius
                + ",\"scope\":" + (scope == null ? "null" : str(scope))
                + ",\"expectedVersion\":" + expectedVersion
                + ",\"reason\":" + (reason == null ? "null" : str(reason)) + "}";
    }

    long settingVersion() {
        return jdbc.queryForObject("select version from geofence_setting where id = 1", Long.class);
    }

    /** 以管理員 carol 啟用定位限制（中心為測試座標），回傳新版本。 */
    long enableGeofence(String scope, int radius) {
        TestBrowser admin = loginAs("carol");
        Reply reply = admin.put(GEOFENCE, settingsBody(true, CENTER_LAT, CENTER_LON, radius, scope, settingVersion(), "測試啟用"));
        assertThat(reply.status()).as("enable geofence %s", reply.body()).isEqualTo(200);
        return reply.json().get("version").asLong();
    }

    /** 打卡本文：中心正北方 meters 公尺、精度 accuracy、取得時間 capturedAt。 */
    static String locationAt(double meters, double accuracy, Instant capturedAt) {
        return "{\"latitude\":" + northOf(meters) + ",\"longitude\":" + CENTER_LON
                + ",\"accuracy\":" + accuracy + ",\"capturedAt\":\"" + capturedAt + "\"}";
    }

    String locationAt(double meters) {
        return locationAt(meters, 10, clock.instant());
    }

    int eventRows() {
        return jdbc.queryForObject("select count(*) from attendance_clock_event", Integer.class);
    }

    List<Map<String, Object>> events() {
        return jdbc.queryForList("select * from attendance_clock_event order by id");
    }

    /** 所有打卡事件的完整內容（用來證明「之後的操作沒有改寫證據」）。 */
    String eventsFingerprint() {
        return jdbc.queryForList("select row_to_json(e)::text from attendance_clock_event e order by id", String.class).toString();
    }
}
