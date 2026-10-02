package com.renewflow.eip.attendance;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * T03.2 定位打卡：真的 HTTP＋PostgreSQL 測試庫＋可控時鐘。
 * 每個「拒絕」都同時檢查：出勤列數、版本、打卡事件都沒有變（定位拒絕發生在任何寫入之前）。
 */
class GeofenceClockIntegrationTest extends GeofenceTestBase {

    private static final String DAY = "2026-10-01";

    private void assertNothingWritten(int attendanceBefore, int eventsBefore) {
        assertThat(attendanceRows()).as("出勤列數不變").isEqualTo(attendanceBefore);
        assertThat(eventRows()).as("沒有新的打卡事件").isEqualTo(eventsBefore);
    }

    // ===================== 關閉或不受限制 =====================

    @Test
    @DisplayName("P-01 定位關閉：沿用無本文的打卡；成功打卡仍留下事件（不要求定位、記錄當時設定版本、不存座標）")
    void disabledKeepsOldFlowAndRecordsEvent() {
        TestBrowser aliceB = loginAs("alice");
        assertThat(aliceB.post(CLOCK_IN, null).status()).isEqualTo(200);
        clock.set(utc("2026-10-01T09:00:00Z"));
        assertThat(aliceB.post(CLOCK_OUT, "").status()).isEqualTo(200);

        List<Map<String, Object>> events = events();
        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.get("location_required")).isEqualTo(false);
            assertThat(e.get("setting_version")).isEqualTo(0L);
            assertThat(e.get("setting_enabled")).isEqualTo(false);
            assertThat(e.get("latitude")).isNull();
            assertThat(e.get("distance_meters")).isNull();
            assertThat(e.get("attendance_id")).isEqualTo(activeId(alice, DAY));
        });
        assertThat(events.get(0).get("action")).isEqualTo("CLOCK_IN");
        assertThat(events.get(1).get("action")).isEqualTo("CLOCK_OUT");
    }

    @Test
    @DisplayName("P-02 定位關閉時即使送了位置也不保存員工座標（不受限制時不蒐集）")
    void locationIsNotStoredWhenNotRequired() {
        assertThat(loginAs("alice").post(CLOCK_IN, locationAt(3000)).status()).isEqualTo(200);

        var event = events().get(0);
        assertThat(event.get("location_required")).isEqualTo(false);
        assertThat(event.get("latitude")).isNull();
        assertThat(event.get("accuracy_meters")).isNull();
    }

    // ===================== 範圍判斷 =====================

    @Test
    @DisplayName("P-03 範圍內（99.5 公尺）→ 成功；證據含座標、精度、取得時間、後端距離與設定快照（版本、中心、半徑）")
    void insideIsAcceptedWithEvidence() {
        long version = enableGeofence("BOTH", 100);
        Instant captured = clock.instant().minusSeconds(5);

        Reply reply = loginAs("alice").post(CLOCK_IN, locationAt(99.5, 12.5, captured));

        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        var event = events().get(0);
        assertThat(event.get("location_required")).isEqualTo(true);
        assertThat((Double) event.get("distance_meters")).isCloseTo(99.5, within(1e-6));
        assertThat(event.get("accuracy_meters")).isEqualTo(12.5);
        assertThat(event.get("latitude")).isEqualTo(northOf(99.5));
        assertThat(event.get("setting_version")).isEqualTo(version);
        assertThat(event.get("setting_radius_meters")).isEqualTo(100);
        assertThat(event.get("setting_center_latitude")).isEqualTo(CENTER_LAT);
        assertThat(jdbc.queryForObject("select (captured_at at time zone 'UTC')::text from attendance_clock_event", String.class))
                .isEqualTo("2026-10-01 00:59:55");
        assertThat(jdbc.queryForObject("select (accepted_at at time zone 'UTC')::text from attendance_clock_event", String.class))
                .as("接受時間來自伺服器 Clock").isEqualTo("2026-10-01 01:00:00");
    }

    @Test
    @DisplayName("P-04 邊界：中心點（0 公尺）可以；100.4 公尺（四捨五入會變成 100）→ 403 OUTSIDE_GEOFENCE，回傳未取整的距離與半徑；不寫任何資料")
    void boundaryAndOutside() {
        enableGeofence("BOTH", 100);
        TestBrowser aliceB = loginAs("alice");

        Reply outside = aliceB.post(CLOCK_IN, locationAt(100.4));
        assertThat(outside.status()).isEqualTo(403);
        assertThat(outside.code()).isEqualTo("OUTSIDE_GEOFENCE");
        assertThat(outside.json().get("distanceMeters").asDouble()).isCloseTo(100.4, within(1e-6));
        assertThat(outside.json().get("radiusMeters").asInt()).isEqualTo(100);
        assertNothingWritten(0, 0);

        Reply far = aliceB.post(CLOCK_IN, locationAt(5000));
        assertThat(far.code()).isEqualTo("OUTSIDE_GEOFENCE");
        assertThat(far.json().get("message").asString()).contains("5000").contains("100");
        assertNothingWritten(0, 0);

        assertThat(aliceB.post(CLOCK_IN, locationAt(0)).status()).isEqualTo(200);
        assertThat((Double) events().get(0).get("distance_meters")).isZero();
    }

    @Test
    @DisplayName("P-05 前端偽造 distance／inside／中心／半徑都不影響判斷：實際在 5 公里外 → 403")
    void forgedFieldsAreIgnored() {
        enableGeofence("BOTH", 100);
        String forged = "{\"latitude\":" + northOf(5000) + ",\"longitude\":" + CENTER_LON + ",\"accuracy\":5,\"capturedAt\":\"" + clock.instant() + "\""
                + ",\"distance\":0,\"distanceMeters\":0,\"inside\":true,\"passed\":true,\"radiusMeters\":100000"
                + ",\"centerLatitude\":" + northOf(5000) + ",\"centerLongitude\":" + CENTER_LON + "}";

        Reply reply = loginAs("alice").post(CLOCK_IN, forged);

        assertThat(reply.status()).isEqualTo(403);
        assertThat(reply.code()).isEqualTo("OUTSIDE_GEOFENCE");
        assertThat(reply.json().get("distanceMeters").asDouble()).isCloseTo(5000, within(1e-6));
        assertNothingWritten(0, 0);
    }

    // ===================== 定位品質 =====================

    @Test
    @DisplayName("P-06 缺位置（無本文、缺任一欄位）→ 422 LOCATION_REQUIRED；不寫任何資料")
    void missingLocation() {
        enableGeofence("BOTH", 100);
        TestBrowser aliceB = loginAs("alice");
        String now = clock.instant().toString();
        List<String> bodies = List.of("", "{}",
                "{\"longitude\":121.5,\"accuracy\":5,\"capturedAt\":\"" + now + "\"}",
                "{\"latitude\":25.0,\"accuracy\":5,\"capturedAt\":\"" + now + "\"}",
                "{\"latitude\":25.0,\"longitude\":121.5,\"capturedAt\":\"" + now + "\"}",
                "{\"latitude\":25.0,\"longitude\":121.5,\"accuracy\":5}");
        for (String body : bodies) {
            Reply reply = aliceB.post(CLOCK_IN, body);
            assertThat(reply.status()).as(body).isEqualTo(422);
            assertThat(reply.code()).as(body).isEqualTo("LOCATION_REQUIRED");
        }
        assertNothingWritten(0, 0);
    }

    @Test
    @DisplayName("P-07 精度：accuracy = 50 可以；50.01 → 422 LOCATION_ACCURACY_TOO_LOW（不會把 accuracy 加進半徑放寬）")
    void accuracyLimit() {
        enableGeofence("BOTH", 100);
        TestBrowser aliceB = loginAs("alice");

        Reply poor = aliceB.post(CLOCK_IN, locationAt(10, 50.01, clock.instant()));
        assertThat(poor.status()).isEqualTo(422);
        assertThat(poor.code()).isEqualTo("LOCATION_ACCURACY_TOO_LOW");
        // 距離 120、精度 30：若把精度加進半徑（130）就會誤放行
        assertThat(aliceB.post(CLOCK_IN, locationAt(120, 30, clock.instant())).code()).isEqualTo("OUTSIDE_GEOFENCE");
        assertNothingWritten(0, 0);

        assertThat(aliceB.post(CLOCK_IN, locationAt(10, 50, clock.instant())).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("P-08 定位時間窗口：60 秒前與 10 秒後可以；61 秒前、11 秒後 → 422 LOCATION_TIME_INVALID")
    void capturedAtWindow() {
        enableGeofence("BOTH", 100);
        TestBrowser aliceB = loginAs("alice");
        Instant now = clock.instant();

        assertThat(aliceB.post(CLOCK_IN, locationAt(10, 5, now.minusSeconds(61))).code()).isEqualTo("LOCATION_TIME_INVALID");
        assertThat(aliceB.post(CLOCK_IN, locationAt(10, 5, now.plusSeconds(11))).code()).isEqualTo("LOCATION_TIME_INVALID");
        assertNothingWritten(0, 0);

        assertThat(aliceB.post(CLOCK_IN, locationAt(10, 5, now.minusSeconds(60))).status()).isEqualTo(200);
        clock.set(utc("2026-10-01T09:00:00Z"));
        assertThat(aliceB.post(CLOCK_OUT, locationAt(10, 5, clock.instant().plusSeconds(10))).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("P-09 不合法的定位數值：超出範圍、負精度、NaN、Infinity、非數字 → 400；不寫任何資料")
    void invalidLocationValues() {
        enableGeofence("BOTH", 100);
        TestBrowser aliceB = loginAs("alice");
        String now = clock.instant().toString();
        List<String> bodies = List.of(
                "{\"latitude\":90.5,\"longitude\":121.5,\"accuracy\":5,\"capturedAt\":\"" + now + "\"}",
                "{\"latitude\":25.0,\"longitude\":-180.5,\"accuracy\":5,\"capturedAt\":\"" + now + "\"}",
                "{\"latitude\":25.0,\"longitude\":121.5,\"accuracy\":-1,\"capturedAt\":\"" + now + "\"}",
                "{\"latitude\":\"NaN\",\"longitude\":121.5,\"accuracy\":5,\"capturedAt\":\"" + now + "\"}",
                "{\"latitude\":25.0,\"longitude\":121.5,\"accuracy\":\"Infinity\",\"capturedAt\":\"" + now + "\"}",
                "{\"latitude\":1e400,\"longitude\":121.5,\"accuracy\":5,\"capturedAt\":\"" + now + "\"}",
                "{\"latitude\":\"north\",\"longitude\":121.5,\"accuracy\":5,\"capturedAt\":\"" + now + "\"}",
                "{\"latitude\":25.0,\"longitude\":121.5,\"accuracy\":5,\"capturedAt\":\"yesterday\"}");
        for (String body : bodies) {
            assertThat(aliceB.post(CLOCK_IN, body).status()).as(body).isEqualTo(400);
        }
        // 有上下限的欄位由 Bean Validation 擋下（NaN／Infinity 不在範圍內）；accuracy 只有下限，Infinity 由 Service 的有限數值檢查擋下
        assertThat(aliceB.post(CLOCK_IN, bodies.get(3)).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(aliceB.post(CLOCK_IN, bodies.get(4)).code()).isEqualTo("INVALID_LOCATION");
        assertNothingWritten(0, 0);
    }

    // ===================== 適用操作 =====================

    @Test
    @DisplayName("P-10 只限制上班：上班需要定位；下班不需要（無本文成功，事件標示不要求定位、不存座標）")
    void scopeClockInOnly() {
        enableGeofence("CLOCK_IN", 100);
        TestBrowser aliceB = loginAs("alice");

        assertThat(aliceB.post(CLOCK_IN, null).code()).isEqualTo("LOCATION_REQUIRED");
        assertThat(aliceB.post(CLOCK_IN, locationAt(10)).status()).isEqualTo(200);
        clock.set(utc("2026-10-01T09:00:00Z"));
        assertThat(aliceB.post(CLOCK_OUT, null).status()).isEqualTo(200);

        var events = events();
        assertThat(events.get(0).get("location_required")).isEqualTo(true);
        assertThat(events.get(1).get("location_required")).isEqualTo(false);
        assertThat(events.get(1).get("latitude")).isNull();
    }

    @Test
    @DisplayName("P-11 只限制下班：上班無本文成功；下班沒有位置 → 422，在範圍外 → 403，在範圍內 → 成功")
    void scopeClockOutOnly() {
        enableGeofence("CLOCK_OUT", 100);
        TestBrowser aliceB = loginAs("alice");

        assertThat(aliceB.post(CLOCK_IN, null).status()).isEqualTo(200);
        clock.set(utc("2026-10-01T09:00:00Z"));
        long version = activeVersion(alice, DAY);
        assertThat(aliceB.post(CLOCK_OUT, null).code()).isEqualTo("LOCATION_REQUIRED");
        assertThat(aliceB.post(CLOCK_OUT, locationAt(150)).code()).isEqualTo("OUTSIDE_GEOFENCE");
        assertThat(activeVersion(alice, DAY)).as("下班被拒絕時版本不變").isEqualTo(version);
        assertThat(activeColumn(alice, DAY, "clock_out_at")).isNull();
        assertThat(eventRows()).isEqualTo(1);

        assertThat(aliceB.post(CLOCK_OUT, locationAt(50)).status()).isEqualTo(200);
        assertThat(events().get(1).get("location_required")).isEqualTo(true);
    }

    @Test
    @DisplayName("P-12 上下班都限制；管理員自己打卡也一樣受限制（developer／admin 沒有繞過權限）")
    void adminsAreRestrictedToo() {
        enableGeofence("BOTH", 100);
        TestBrowser carolB = loginAs("carol");

        assertThat(carolB.post(CLOCK_IN, null).code()).isEqualTo("LOCATION_REQUIRED");
        assertThat(carolB.post(CLOCK_IN, locationAt(500)).code()).isEqualTo("OUTSIDE_GEOFENCE");
        assertThat(attendanceRowsOf(carol)).isZero();
        assertThat(carolB.post(CLOCK_IN, locationAt(30)).status()).isEqualTo(200);
        clock.set(utc("2026-10-01T09:00:00Z"));
        assertThat(carolB.post(CLOCK_OUT, null).code()).isEqualTo("LOCATION_REQUIRED");
    }

    @Test
    @DisplayName("P-13 重複打卡仍沿用 T03 規則：已上班再上班（帶合法位置）→ 409 ALREADY_CLOCKED_IN，不新增事件")
    void duplicateClockInStillConflicts() {
        enableGeofence("BOTH", 100);
        TestBrowser aliceB = loginAs("alice");
        assertThat(aliceB.post(CLOCK_IN, locationAt(10)).status()).isEqualTo(200);

        Reply again = aliceB.post(CLOCK_IN, locationAt(10));

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("ALREADY_CLOCKED_IN");
        assertThat(eventRows()).isEqualTo(1);
    }

    // ===================== 員工端可見資訊 =====================

    @Test
    @DisplayName("P-14 員工端的定位要求：今日回應與 location-policy 都告知是否需要、半徑與精度上限；不含公司中心座標")
    void policyDoesNotExposeCenter() {
        TestBrowser aliceB = loginAs("alice");
        var before = aliceB.get(POLICY).json();
        assertThat(before.get("clockInRequired").asBoolean()).isFalse();

        enableGeofence("CLOCK_OUT", 250);
        Reply policy = aliceB.get(POLICY);
        var p = policy.json();
        assertThat(p.get("enabled").asBoolean()).isTrue();
        assertThat(p.get("clockInRequired").asBoolean()).isFalse();
        assertThat(p.get("clockOutRequired").asBoolean()).isTrue();
        assertThat(p.get("radiusMeters").asInt()).isEqualTo(250);
        assertThat(p.get("maxAccuracyMeters").asDouble()).isEqualTo(50.0);
        assertThat(policy.body()).doesNotContain("latitude").doesNotContain("longitude").doesNotContain(String.valueOf(CENTER_LON));
        var today = aliceB.get(TODAY);
        assertThat(today.json().get("locationPolicy").get("clockOutRequired").asBoolean()).isTrue();
        assertThat(today.body()).doesNotContain(String.valueOf(CENTER_LON));
    }

    @Test
    @DisplayName("P-15 打卡事件只能新增：資料庫拒絕 UPDATE／DELETE；「要求定位卻沒有座標」的事件也寫不進去")
    void eventsAreAppendOnly() {
        enableGeofence("BOTH", 100);
        assertThat(loginAs("alice").post(CLOCK_IN, locationAt(10)).status()).isEqualTo(200);

        assertThatThrownBy(() -> jdbc.update("update attendance_clock_event set latitude = 0"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from attendance_clock_event"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("""
                insert into attendance_clock_event (employee_id, attendance_id, action, accepted_at, location_required,
                    setting_version, setting_enabled, setting_scope, setting_center_latitude, setting_center_longitude, setting_radius_meters)
                values (?, ?, 'CLOCK_OUT', now(), true, 1, true, 'BOTH', 25, 121.5, 100)
                """, alice.getId(), activeId(alice, DAY))).isInstanceOf(DataAccessException.class);
        assertThat(eventRows()).isEqualTo(1);
    }
}
