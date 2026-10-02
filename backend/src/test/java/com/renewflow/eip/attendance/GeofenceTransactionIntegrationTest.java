package com.renewflow.eip.attendance;

import com.renewflow.eip.geofence.GeofenceSettingAuditLog;
import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * T03.2 交易與並行：
 * <ul>
 *   <li>出勤＋證據、設定＋歷程同成同敗（以替身注入失敗）。</li>
 *   <li>設定讀取失敗不會被當成「定位已關閉」放行。</li>
 *   <li>設定修改與打卡的先後：以真實的資料列鎖讓兩者「卡在一起」，觀察誰等誰、用的是哪一版設定。</li>
 * </ul>
 */
class GeofenceTransactionIntegrationTest extends GeofenceTestBase {

    @MockitoSpyBean
    ClockEventLog clockEventLog;
    @MockitoSpyBean
    GeofenceSettingAuditLog settingAuditLog;
    @Autowired
    DataSource dataSource;

    @AfterEach
    void repair() {
        doCallRealMethod().when(clockEventLog).record(any(), any(), any(), any(), any());
        doCallRealMethod().when(settingAuditLog).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("T-01 證據寫入失敗 → 500；出勤沒有新增（打卡不會「成功卻沒有證據」）；修復後可正常打卡")
    void clockRollsBackWhenEvidenceFails() {
        enableGeofence("BOTH", 100);
        TestBrowser aliceB = loginAs("alice");
        doThrow(new IllegalStateException("evidence write failed (injected)")).when(clockEventLog).record(any(), any(), any(), any(), any());

        Reply failed = aliceB.post(CLOCK_IN, locationAt(10));

        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(failed.body()).doesNotContain("injected");
        assertThat(attendanceRows()).isZero();
        assertThat(eventRows()).isZero();

        doCallRealMethod().when(clockEventLog).record(any(), any(), any(), any(), any());
        assertThat(aliceB.post(CLOCK_IN, locationAt(10)).status()).isEqualTo(200);
        assertThat(eventRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("T-02 下班的證據寫入失敗 → 500；下班時間與版本都回滾")
    void clockOutRollsBackWhenEvidenceFails() {
        TestBrowser aliceB = loginAs("alice");
        assertThat(aliceB.post(CLOCK_IN, null).status()).isEqualTo(200);
        long version = activeVersion(alice, "2026-10-01");
        clock.set(utc("2026-10-01T09:00:00Z"));
        doThrow(new IllegalStateException("evidence write failed (injected)")).when(clockEventLog).record(any(), any(), any(), any(), any());

        assertThat(aliceB.post(CLOCK_OUT, null).status()).isEqualTo(500);

        assertThat(activeColumn(alice, "2026-10-01", "clock_out_at")).isNull();
        assertThat(activeVersion(alice, "2026-10-01")).isEqualTo(version);
        assertThat(eventRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("T-03 設定歷程寫入失敗 → 500；設定（開關、中心、版本）一起回滾")
    void settingRollsBackWhenHistoryFails() {
        TestBrowser carolB = loginAs("carol");
        doThrow(new IllegalStateException("history write failed (injected)")).when(settingAuditLog).record(any(), any(), any(), any(), any());

        Reply failed = carolB.put(GEOFENCE, settingsBody(true, CENTER_LAT, CENTER_LON, 100, "BOTH", 0, "會失敗"));

        assertThat(failed.status()).isEqualTo(500);
        assertThat(settingVersion()).isZero();
        assertThat(jdbc.queryForObject("select enabled from geofence_setting", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select center_latitude from geofence_setting", Double.class)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from geofence_setting_history", Integer.class)).isZero();
    }

    @Test
    @DisplayName("T-04 設定讀取失敗（設定列遺失、資料表無法存取）→ 500，絕不當作「定位已關閉」放行；不寫任何資料")
    void settingReadFailureDoesNotAllowClocking() {
        TestBrowser aliceB = loginAs("alice");
        jdbc.update("delete from geofence_setting");
        Reply missingRow = aliceB.post(CLOCK_IN, null);
        assertThat(missingRow.status()).isEqualTo(500);
        assertThat(attendanceRows()).isZero();

        jdbc.update("insert into geofence_setting (id) values (1)");
        jdbc.execute("alter table geofence_setting rename to geofence_setting_unavailable");
        try {
            Reply unavailable = aliceB.post(CLOCK_IN, null);
            assertThat(unavailable.status()).isEqualTo(500);
            assertThat(unavailable.code()).isEqualTo("INTERNAL_ERROR");
        } finally {
            jdbc.execute("alter table geofence_setting_unavailable rename to geofence_setting");
        }
        assertThat(attendanceRows()).isZero();
        assertThat(eventRows()).isZero();
        assertThat(aliceB.post(CLOCK_IN, null).status()).as("恢復後正常").isEqualTo(200);
    }

    @Test
    @DisplayName("T-05 先後規則（修改先）：設定修改尚未提交時，打卡會等待；提交後打卡使用新版本（半徑縮成 10 → 50 公尺外被拒）")
    void clockWaitsForInFlightSettingChange() throws Exception {
        enableGeofence("BOTH", 100);
        long oldVersion = settingVersion();
        TestBrowser aliceB = loginAs("alice");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection admin = dataSource.getConnection()) {
            admin.setAutoCommit(false);
            try (Statement st = admin.createStatement()) {
                // 模擬「修改中的設定交易」：持有設定列的寫入鎖
                st.executeUpdate("update geofence_setting set radius_meters = 10, version = version + 1 where id = 1");
            }
            Future<Reply> clockIn = pool.submit(() -> aliceB.post(CLOCK_IN, locationAt(50)));
            Thread.sleep(800);
            assertThat(clockIn.isDone()).as("打卡在等設定修改提交（FOR SHARE 等待寫入鎖）").isFalse();

            admin.commit();
            Reply reply = clockIn.get(10, TimeUnit.SECONDS);
            assertThat(reply.status()).as(reply.body()).isEqualTo(403);
            assertThat(reply.json().get("radiusMeters").asInt()).as("用的是新版本的半徑").isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
        assertThat(settingVersion()).isEqualTo(oldVersion + 1);
        assertThat(attendanceRows()).isZero();
    }

    @Test
    @DisplayName("T-06 先後規則（打卡先）：打卡交易進行中（已取得設定快照），設定修改會等它提交；打卡證據記錄的是舊版本，修改之後才生效")
    void settingChangeWaitsForInFlightClock() throws Exception {
        long oldVersion = enableGeofence("BOTH", 100);
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        CountDownLatch insideClock = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            insideClock.countDown();
            assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(clockEventLog).record(any(), any(), any(), any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Reply> clockIn = pool.submit(() -> aliceB.post(CLOCK_IN, locationAt(50)));
            assertThat(insideClock.await(10, TimeUnit.SECONDS)).as("打卡已通過定位判斷、正在寫證據").isTrue();
            Future<Reply> update = pool.submit(() -> carolB.put(GEOFENCE, settingsBody(true, CENTER_LAT, CENTER_LON, 10, "BOTH", oldVersion, "縮小半徑")));
            Thread.sleep(800);
            assertThat(update.isDone()).as("設定修改在等打卡交易結束（FOR UPDATE 等待共享鎖）").isFalse();

            release.countDown();
            assertThat(clockIn.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
            Reply updated = update.get(10, TimeUnit.SECONDS);
            assertThat(updated.status()).as(updated.body()).isEqualTo(200);
            assertThat(updated.json().get("version").asLong()).isEqualTo(oldVersion + 1);
        } finally {
            pool.shutdownNow();
        }
        var event = events().get(0);
        assertThat(event.get("setting_version")).as("證據記錄打卡當時的版本").isEqualTo(oldVersion);
        assertThat(event.get("setting_radius_meters")).isEqualTo(100);

        // 修改之後的打卡（下班）使用新半徑 10：50 公尺外被拒
        doCallRealMethod().when(clockEventLog).record(any(), any(), any(), any(), any());
        clock.set(utc("2026-10-01T09:00:00Z"));
        Reply out = aliceB.post(CLOCK_OUT, locationAt(50));
        assertThat(out.code()).isEqualTo("OUTSIDE_GEOFENCE");
        assertThat(out.json().get("radiusMeters").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("T-07 設定修改與打卡大量並行（多輪）：每一筆證據的「版本、半徑、中心」都屬於同一版設定（不混用），且距離 ≤ 該版半徑")
    void concurrentChangesNeverMixVersions() throws Exception {
        enableGeofence("BOTH", 1000);
        List<TestBrowser> employees = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String username = "emp" + i;
            employeeRepository.save(new com.renewflow.eip.identity.Employee("P" + i, username, "員工" + i,
                    passwordEncoder.encode(PASSWORD), com.renewflow.eip.identity.Role.EMPLOYEE));
            employees.add(loginAs(username));
        }
        TestBrowser carolB = loginAs("carol");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 1; round <= 6; round++) {
                clock.setAutoAdvance(utc("2026-10-0" + round + "T01:00:00Z"), Duration.ofMillis(5));
                int radius = round % 2 == 0 ? 1000 : 150; // 員工都在 200 公尺處：半徑 1000 通過、150 被拒
                long expected = settingVersion();
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Reply>> futures = new ArrayList<>();
                futures.add(pool.submit(() -> {
                    go.await();
                    return carolB.put(GEOFENCE, settingsBody(true, CENTER_LAT, CENTER_LON, radius, "BOTH", expected, "並行修改 " + radius));
                }));
                for (TestBrowser e : employees) {
                    futures.add(pool.submit(() -> {
                        go.await();
                        return e.post(CLOCK_IN, locationAt(200, 5, clock.instant()));
                    }));
                }
                go.countDown();
                for (Future<Reply> f : futures) {
                    int status = f.get(30, TimeUnit.SECONDS).status();
                    assertThat(status).isIn(200, 403);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        // 每一筆證據的設定快照，都必須等於「該版本」在設定歷程中的修改後內容
        List<Map<String, Object>> mismatches = jdbc.queryForList("""
                select e.id from attendance_clock_event e
                  join geofence_setting_history h on h.setting_version = e.setting_version
                 where e.setting_radius_meters <> h.after_radius_meters
                    or e.setting_center_latitude <> h.after_center_latitude
                    or e.setting_enabled <> h.after_enabled
                    or e.distance_meters > e.setting_radius_meters
                """);
        assertThat(mismatches).isEmpty();
        assertThat(jdbc.queryForObject("""
                select count(*) from attendance_clock_event e
                 where not exists (select 1 from geofence_setting_history h where h.setting_version = e.setting_version)
                """, Integer.class)).as("每筆證據的版本都真實存在").isZero();
        assertThat(eventRows()).isEqualTo(attendanceRows());
    }
}
