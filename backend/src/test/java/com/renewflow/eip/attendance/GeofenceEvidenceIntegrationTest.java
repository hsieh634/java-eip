package com.renewflow.eip.attendance;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T03.2 原始打卡證據 vs 可更正的出勤，以及「申請不受定位限制」。
 * 重點：撤銷、作廢、更正、補登都不改寫既有事件，也不替補登製造「當時定位成功」的事件。
 */
class GeofenceEvidenceIntegrationTest extends GeofenceTestBase {

    private static final String DAY = "2026-10-01";

    private int eventsOfAttendance(long attendanceId) {
        return jdbc.queryForObject("select count(*) from attendance_clock_event where attendance_id = ?", Integer.class, attendanceId);
    }

    @Test
    @DisplayName("E-01 直接更正、撤銷下班（核准）、整組作廢（核准）都不改寫既有事件；撤銷後再下班與重打會產生新事件（同一筆出勤可有兩筆下班事件）")
    void correctionsNeverRewriteEvidence() {
        enableGeofence("BOTH", 100);
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        clock.set(utc("2026-10-01T01:00:00Z"));
        assertThat(aliceB.post(CLOCK_IN, locationAt(20)).status()).isEqualTo(200);
        clock.set(utc("2026-10-01T01:05:00Z"));
        assertThat(aliceB.post(CLOCK_OUT, locationAt(25)).status()).isEqualTo(200); // 誤按下班
        long recordA = activeId(alice, DAY);
        String evidence = eventsFingerprint();

        // 管理員直接更正上班時間 → 事件不變
        clock.set(utc("2026-10-01T02:00:00Z"));
        Reply corrected = carolB.post(ADMIN + "/records/correct", json("employeeId", String.valueOf(alice.getId()), "workDate", str(DAY),
                "attendanceId", String.valueOf(recordA), "expectedVersion", String.valueOf(activeVersion(alice, DAY)),
                "clockInTime", str("08:55:00"), "clockOutTime", str("09:05:00"), "reason", str("當面確認")));
        assertThat(corrected.status()).as(corrected.body()).isEqualTo(200);
        assertThat(eventsFingerprint()).as("直接更正不改證據").isEqualTo(evidence);

        // 在公司範圍外提出撤銷下班（申請本身不需要定位）→ 核准 → 事件不變
        String body = json("type", str("CORRECT_TIME"), "workDate", str(DAY), "clockInTime", str("08:55:00"),
                "clockOutTime", "null", "reason", str("誤按下班"));
        Reply revoke = aliceB.post(REQUESTS, body.substring(0, body.length() - 1) + snapshotOf(aliceB, DAY) + "}");
        assertThat(revoke.status()).as(revoke.body()).isEqualTo(201);
        assertThat(approve(carolB, revoke.json().get("id").asLong()).status()).isEqualTo(200);
        assertThat(eventsFingerprint()).as("核准撤銷下班不改證據").isEqualTo(evidence);

        // 核准後的下一次實際打卡遵守當下設定：沒有位置 → 422；範圍內 → 成功，同一筆出勤出現第二筆下班事件
        clock.set(utc("2026-10-01T09:00:00Z"));
        assertThat(aliceB.post(CLOCK_OUT, null).code()).isEqualTo("LOCATION_REQUIRED");
        assertThat(aliceB.post(CLOCK_OUT, locationAt(30)).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from attendance_clock_event where attendance_id = ? and action = 'CLOCK_OUT'",
                Integer.class, recordA)).isEqualTo(2);

        // 整組作廢（核准）→ 舊事件仍指向作廢的紀錄、內容不變；重新上班產生新紀錄的新事件
        String beforeReset = eventsFingerprint();
        clock.set(utc("2026-10-01T09:30:00Z"));
        long reset = idOf(submitReset(aliceB, "整組重打"));
        assertThat(approve(carolB, reset).status()).isEqualTo(200);
        assertThat(eventsFingerprint()).as("整組作廢不改證據").isEqualTo(beforeReset);
        assertThat(eventsOfAttendance(recordA)).isEqualTo(3);
        clock.set(utc("2026-10-01T09:40:00Z"));
        assertThat(aliceB.post(CLOCK_IN, locationAt(10)).status()).isEqualTo(200);
        long recordB = activeId(alice, DAY);
        assertThat(recordB).isNotEqualTo(recordA);
        assertThat(eventsOfAttendance(recordB)).isEqualTo(1);
    }

    @Test
    @DisplayName("E-02 補登（申請核准或管理員直接新增）不會產生任何打卡事件——沒有「當時定位成功」的假證據")
    void backfillCreatesNoEvidence() {
        enableGeofence("BOTH", 100);
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");

        long viaRequest = idOf(submitCorrection(aliceB, "2026-09-30", "09:00:00", "18:00:00", "忘記打卡"));
        assertThat(approve(carolB, viaRequest).status()).isEqualTo(200);
        Reply direct = carolB.post(ADMIN + "/records/correct", json("employeeId", String.valueOf(alice.getId()), "workDate", str("2026-09-29"),
                "attendanceId", "null", "expectedVersion", "null", "clockInTime", str("09:00:00"), "clockOutTime", str("18:00:00"),
                "reason", str("管理員補登")));
        assertThat(direct.status()).as(direct.body()).isEqualTo(200);

        assertThat(activeRows(alice)).isEqualTo(2);
        assertThat(eventRows()).isZero();
    }

    @Test
    @DisplayName("E-03 在公司範圍外（不帶位置）仍可提出三種申請：整組作廢、撤銷下班、更正／補登；仍需原因與審核，不會自動放行")
    void requestsAreNotGeofenced() {
        enableGeofence("BOTH", 100);
        TestBrowser aliceB = loginAs("alice");
        TestBrowser bobB = loginAs("bob");
        assertThat(aliceB.post(CLOCK_IN, locationAt(10)).status()).isEqualTo(200);
        assertThat(bobB.post(CLOCK_IN, locationAt(10)).status()).isEqualTo(200);
        clock.set(utc("2026-10-01T01:05:00Z"));
        assertThat(bobB.post(CLOCK_OUT, locationAt(10)).status()).isEqualTo(200);
        clock.set(utc("2026-10-01T02:00:00Z"));

        Reply reset = submitReset(aliceB, "範圍外提出整組作廢");
        String revokeBody = json("type", str("CORRECT_TIME"), "workDate", str(DAY), "clockInTime",
                str(jdbc.queryForObject("select to_char(clock_in_at at time zone 'Asia/Taipei', 'HH24:MI:SS.US') from attendance where employee_id = ? and voided_at is null",
                        String.class, bob.getId())), "clockOutTime", "null", "reason", str("範圍外提出撤銷下班"));
        Reply revoke = bobB.post(REQUESTS, revokeBody.substring(0, revokeBody.length() - 1) + snapshotOf(bobB, DAY) + "}");
        Reply backfill = submitCorrection(aliceB, "2026-09-30", "09:00:00", "18:00:00", "範圍外提出補登");
        Reply noReason = aliceB.post(REQUESTS, "{\"type\":\"CORRECT_TIME\",\"workDate\":\"2026-09-28\",\"clockInTime\":\"09:00:00\",\"reason\":\" \"}");

        for (Reply r : List.of(reset, revoke, backfill)) {
            assertThat(r.status()).as(r.body()).isEqualTo(201);
            assertThat(r.json().get("status").asString()).as("仍需審核").isEqualTo("PENDING");
        }
        assertThat(revoke.json().get("kind").asString()).isEqualTo("REVOKE_CLOCK_OUT");
        assertThat(noReason.status()).as("仍需原因").isEqualTo(400);
        assertThat(activeRows(alice)).as("申請不改正式出勤").isEqualTo(1);
    }
}
