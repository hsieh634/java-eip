package com.renewflow.eip.attendance;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T03.2 管理員定位設定：權限、驗證、版本、歷程。真的 HTTP＋PostgreSQL 測試庫。 */
class GeofenceSettingsIntegrationTest extends GeofenceTestBase {

    private int historyCount() {
        return jdbc.queryForObject("select count(*) from geofence_setting_history", Integer.class);
    }

    private void assertUnchanged(long version) {
        assertThat(settingVersion()).as("設定版本不變").isEqualTo(version);
        assertThat(historyCount()).as("沒有新的設定歷程").isZero();
        assertThat(jdbc.queryForObject("select enabled from geofence_setting where id = 1", Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("G-01 初始狀態：關閉、公司中心未設定、半徑 100、上下班都限制、版本 0、沒有修改人；回傳限制值")
    void defaultState() {
        var body = loginAs("carol").get(GEOFENCE).json();

        assertThat(body.get("enabled").asBoolean()).isFalse();
        assertThat(body.get("latitude").isNull()).isTrue();
        assertThat(body.get("longitude").isNull()).isTrue();
        assertThat(body.get("radiusMeters").asInt()).isEqualTo(100);
        assertThat(body.get("scope").asString()).isEqualTo("BOTH");
        assertThat(body.get("version").asLong()).isZero();
        assertThat(body.get("updatedBy").isNull()).isTrue();
        assertThat(body.get("limits").get("radiusMin").asInt()).isEqualTo(10);
        assertThat(body.get("limits").get("radiusMax").asInt()).isEqualTo(5000);
        assertThat(body.get("limits").get("maxAccuracyMeters").asDouble()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("G-02 權限：EMPLOYEE／MANAGER 讀寫與歷程都 403；未登入 401；ADMIN 缺 CSRF 403；設定都不變")
    void onlyAdminsCanReadOrWrite() {
        String body = settingsBody(true, CENTER_LAT, CENTER_LON, 100, "BOTH", 0, "越權");
        for (String user : List.of("alice", "bob")) {
            TestBrowser browser = loginAs(user);
            assertThat(browser.get(GEOFENCE).status()).as(user + " GET").isEqualTo(403);
            assertThat(browser.get(GEOFENCE + "/history").status()).as(user + " history").isEqualTo(403);
            assertThat(browser.put(GEOFENCE, body).status()).as(user + " PUT").isEqualTo(403);
        }
        TestBrowser anonymous = new TestBrowser(port);
        anonymous.get("/api/auth/me");
        assertThat(anonymous.get(GEOFENCE).status()).isEqualTo(401);
        assertThat(anonymous.put(GEOFENCE, body).status()).isIn(401, 403);

        TestBrowser carolB = loginAs("carol");
        assertThat(carolB.putWithCsrf(GEOFENCE, body, null).status()).as("缺 CSRF").isEqualTo(403);
        assertThat(carolB.putWithCsrf(GEOFENCE, body, "forged").status()).as("偽造 CSRF").isEqualTo(403);
        assertUnchanged(0);
    }

    @Test
    @DisplayName("G-03 帳號有效性：ADMIN 被停用或被降級後，舊 Session 讀寫設定都 401")
    void disabledOrDemotedAdminIsRejected() {
        TestBrowser carolB = loginAs("carol");
        TestBrowser daveB = loginAs("dave");
        jdbc.update("update employee set enabled = false where id = ?", carol.getId());
        jdbc.update("update employee set role = 'EMPLOYEE' where id = ?", dave.getId());

        assertThat(carolB.put(GEOFENCE, settingsBody(true, CENTER_LAT, CENTER_LON, 100, "BOTH", 0, "停用後")).status()).isEqualTo(401);
        assertThat(daveB.get(GEOFENCE).status()).isEqualTo(401);
        assertThat(daveB.put(GEOFENCE, settingsBody(true, CENTER_LAT, CENTER_LON, 100, "BOTH", 0, "降級後")).status()).isEqualTo(401);
        assertUnchanged(0);
    }

    @Test
    @DisplayName("G-04 ADMIN 修改：版本 +1、記錄修改人與時間、寫入歷程（前後值、原因）；另一位 ADMIN 權限相同")
    void adminUpdatesAndHistoryIsRecorded() {
        TestBrowser carolB = loginAs("carol");
        Reply first = carolB.put(GEOFENCE, settingsBody(true, CENTER_LAT, CENTER_LON, 150, "CLOCK_IN", 0, "公司位置確認後啟用"));

        assertThat(first.status()).isEqualTo(200);
        var s = first.json();
        assertThat(s.get("enabled").asBoolean()).isTrue();
        assertThat(s.get("latitude").asDouble()).isEqualTo(CENTER_LAT);
        assertThat(s.get("radiusMeters").asInt()).isEqualTo(150);
        assertThat(s.get("scope").asString()).isEqualTo("CLOCK_IN");
        assertThat(s.get("version").asLong()).isEqualTo(1);
        assertThat(s.get("updatedBy").get("displayName").asString()).isEqualTo("卡蘿");
        assertThat(s.get("updatedAt").asString()).isEqualTo("2026-10-01T01:00:00Z");

        Reply second = loginAs("dave").put(GEOFENCE, settingsBody(false, CENTER_LAT, CENTER_LON, 150, "CLOCK_IN", 1, "暫停"));
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json().get("version").asLong()).isEqualTo(2);

        var entries = carolB.get(GEOFENCE + "/history").json().get("entries");
        assertThat(entries).hasSize(2);
        var latest = entries.get(0);
        assertThat(latest.get("version").asLong()).isEqualTo(2);
        assertThat(latest.get("reason").asString()).isEqualTo("暫停");
        assertThat(latest.get("actor").get("displayName").asString()).isEqualTo("戴夫");
        assertThat(latest.get("before").get("enabled").asBoolean()).isTrue();
        assertThat(latest.get("after").get("enabled").asBoolean()).isFalse();
        var oldest = entries.get(1);
        assertThat(oldest.get("before").get("latitude").isNull()).as("修改前尚未設定中心").isTrue();
        assertThat(oldest.get("after").get("radiusMeters").asInt()).isEqualTo(150);
    }

    @Test
    @DisplayName("G-05 驗證：座標不成對、超出範圍、非有限數值、半徑範圍、適用操作、原因與版本必填、未設中心就啟用、沒有變更 → 400；皆不改資料")
    void validation() {
        TestBrowser carolB = loginAs("carol");
        record Case(String name, String body, String code) {
        }
        List<Case> cases = List.of(
                new Case("只有緯度", settingsBody(false, CENTER_LAT, null, 100, "BOTH", 0, "x"), "INVALID_SETTING"),
                new Case("只有經度", settingsBody(false, null, CENTER_LON, 100, "BOTH", 0, "x"), "INVALID_SETTING"),
                new Case("緯度 90.0001", settingsBody(false, 90.0001, 0.0, 100, "BOTH", 0, "x"), "VALIDATION_FAILED"),
                new Case("緯度 -91", settingsBody(false, -91.0, 0.0, 100, "BOTH", 0, "x"), "VALIDATION_FAILED"),
                new Case("經度 180.5", settingsBody(false, 0.0, 180.5, 100, "BOTH", 0, "x"), "VALIDATION_FAILED"),
                new Case("經度 -181", settingsBody(false, 0.0, -181.0, 100, "BOTH", 0, "x"), "VALIDATION_FAILED"),
                new Case("半徑 9", settingsBody(false, null, null, 9, "BOTH", 0, "x"), "VALIDATION_FAILED"),
                new Case("半徑 5001", settingsBody(false, null, null, 5001, "BOTH", 0, "x"), "VALIDATION_FAILED"),
                new Case("半徑缺少", settingsBody(false, null, null, "null", "BOTH", 0, "x"), "VALIDATION_FAILED"),
                new Case("適用操作缺少", settingsBody(false, null, null, 100, null, 0, "x"), "VALIDATION_FAILED"),
                new Case("原因空白", settingsBody(false, null, null, 200, "BOTH", 0, "   "), "VALIDATION_FAILED"),
                new Case("版本缺少", settingsBody(false, null, null, 200, "BOTH", "null", "x"), "VALIDATION_FAILED"),
                new Case("未設中心就啟用", settingsBody(true, null, null, 100, "BOTH", 0, "x"), "INVALID_SETTING"),
                new Case("沒有任何變更", settingsBody(false, null, null, 100, "BOTH", 0, "x"), "INVALID_SETTING"));
        for (Case c : cases) {
            Reply reply = carolB.put(GEOFENCE, c.body());
            assertThat(reply.status()).as(c.name() + " " + reply.body()).isEqualTo(400);
            assertThat(reply.code()).as(c.name()).isEqualTo(c.code());
        }
        // 適用操作不是列舉值、半徑不是整數 → JSON 無法對應
        assertThat(carolB.put(GEOFENCE, settingsBody(false, null, null, 100, "NOPE", 0, "x")).status()).isEqualTo(400);
        assertThat(carolB.put(GEOFENCE, settingsBody(false, null, null, "100.5", "BOTH", 0, "x")).status()).isEqualTo(400);
        assertUnchanged(0);
    }

    @Test
    @DisplayName("G-05b 非有限數值（字串 \"NaN\"、\"Infinity\"、1e400）一律 400，不會被當成有效座標；資料庫也不接受")
    void nonFiniteCoordinatesAreRejected() {
        TestBrowser carolB = loginAs("carol");
        for (String bad : List.of("\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "1e400")) {
            String body = "{\"enabled\":true,\"latitude\":" + bad + ",\"longitude\":121.5,\"radiusMeters\":100,\"scope\":\"BOTH\",\"expectedVersion\":0,\"reason\":\"x\"}";
            Reply reply = carolB.put(GEOFENCE, body);
            assertThat(reply.status()).as(bad + " → " + reply.body()).isEqualTo(400);
            assertThat(reply.code()).as("Bean Validation 的範圍檢查對 NaN／Infinity 不成立").isEqualTo("VALIDATION_FAILED");
        }
        assertUnchanged(0);
        assertThatThrownBy(() -> jdbc.update("update geofence_setting set center_latitude = 'NaN', center_longitude = 0 where id = 1"))
                .as("資料庫 CHECK 也擋 NaN").isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("G-06 座標 0 是合法值：中心 (0, 0) 可以儲存並啟用（不會被當成「沒填」）")
    void zeroCoordinatesAreValid() {
        Reply reply = loginAs("carol").put(GEOFENCE, settingsBody(true, 0.0, 0.0, 100, "BOTH", 0, "赤道與本初子午線交點（測試）"));

        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        assertThat(reply.json().get("enabled").asBoolean()).isTrue();
        assertThat(reply.json().get("latitude").asDouble()).isZero();
        assertThat(reply.json().get("longitude").asDouble()).isZero();
    }

    @Test
    @DisplayName("G-07 兩位管理員同時編輯：較晚提交的舊版本 → 409 SETTING_CHANGED，不覆蓋較新的設定，也不寫歷程")
    void staleSubmissionIsRejected() {
        TestBrowser carolB = loginAs("carol");
        TestBrowser daveB = loginAs("dave");
        long seenByBoth = carolB.get(GEOFENCE).json().get("version").asLong();
        assertThat(daveB.get(GEOFENCE).json().get("version").asLong()).isEqualTo(seenByBoth);

        assertThat(carolB.put(GEOFENCE, settingsBody(true, CENTER_LAT, CENTER_LON, 200, "BOTH", seenByBoth, "卡蘿先存")).status()).isEqualTo(200);
        Reply stale = daveB.put(GEOFENCE, settingsBody(false, 0.0, 0.0, 300, "CLOCK_OUT", seenByBoth, "戴夫用舊畫面"));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("SETTING_CHANGED");
        var now = carolB.get(GEOFENCE).json();
        assertThat(now.get("radiusMeters").asInt()).isEqualTo(200);
        assertThat(now.get("enabled").asBoolean()).isTrue();
        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("G-08 6 個並行修改（同一個 expectedVersion）→ 恰 1 個 200、其餘 409；版本只 +1、歷程只有一筆")
    void concurrentUpdatesAcceptOnlyOne() throws Exception {
        List<TestBrowser> admins = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            admins.add(loginAs("carol"));
            admins.add(loginAs("dave"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(admins.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Reply>> futures = new ArrayList<>();
            int n = 0;
            for (TestBrowser admin : admins) {
                int radius = 100 + 10 * ++n;
                Callable<Reply> task = () -> {
                    go.await();
                    return admin.put(GEOFENCE, settingsBody(false, null, null, radius, "BOTH", 0, "並行"));
                };
                futures.add(pool.submit(task));
            }
            go.countDown();
            List<Reply> replies = new ArrayList<>();
            for (Future<Reply> f : futures) {
                replies.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(replies.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
            assertThat(replies.stream().filter(r -> r.status() == 409 && r.code().equals("SETTING_CHANGED")).count()).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        assertThat(settingVersion()).isEqualTo(1);
        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("G-09 設定歷程只能新增：資料庫拒絕 UPDATE／DELETE；設定列只能有一列（id = 1）")
    void historyIsAppendOnlyAndSettingIsSingleton() {
        loginAs("carol").put(GEOFENCE, settingsBody(true, CENTER_LAT, CENTER_LON, 100, "BOTH", 0, "建立一筆歷程"));

        assertThatThrownBy(() -> jdbc.update("update geofence_setting_history set reason = 'tampered'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from geofence_setting_history"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("insert into geofence_setting (id) values (2)")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update geofence_setting set enabled = true, center_latitude = null, center_longitude = null where id = 1"))
                .as("資料庫也擋「啟用卻沒有中心」").isInstanceOf(DataAccessException.class);
        assertThat(historyCount()).isEqualTo(1);
    }
}
