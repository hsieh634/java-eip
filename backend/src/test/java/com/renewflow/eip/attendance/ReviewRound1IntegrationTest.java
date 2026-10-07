package com.renewflow.eip.attendance;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T03.1 第 1 輪複審：R1（直接更正比對 ID＋版本）、申請快照核對、撤銷下班 vs 整組作廢。
 * 全部是真的 HTTP＋PostgreSQL（測試庫）＋可控時鐘。編號對應 docs/renewflow/t03-1/review-round-1.md。
 */
class ReviewRound1IntegrationTest extends CorrectionsTestBase {

    private static final String DAY = "2026-10-01";

    private String directBody(long employeeId, Long attendanceId, Long expectedVersion, String in, String out, String reason) {
        return json("employeeId", String.valueOf(employeeId), "workDate", str(DAY),
                "attendanceId", attendanceId == null ? "null" : String.valueOf(attendanceId),
                "expectedVersion", expectedVersion == null ? "null" : String.valueOf(expectedVersion),
                "clockInTime", str(in), "clockOutTime", out == null ? "null" : str(out), "reason", str(reason));
    }

    private Reply directVoid(TestBrowser admin, long employeeId, long attendanceId, long version) {
        return admin.post(ADMIN + "/records/void", json("employeeId", String.valueOf(employeeId), "attendanceId", String.valueOf(attendanceId),
                "expectedVersion", String.valueOf(version), "reason", str("作廢")));
    }

    /** 撤銷下班的快捷申請：上班時間完整精度送出（含小數秒），下班為空，並附上快照。 */
    private Reply submitRevoke(TestBrowser browser, String preciseClockIn) {
        String body = json("type", str("CORRECT_TIME"), "workDate", str(DAY), "clockInTime", str(preciseClockIn),
                "clockOutTime", "null", "reason", str("誤按下班，實際仍在工作"));
        return browser.post(REQUESTS, body.substring(0, body.length() - 1) + snapshotOf(browser, DAY) + "}");
    }

    // ===================== R1 =====================

    @Test
    @DisplayName("F-01 R1：A 被作廢、員工重打產生 B（版本同為 0），用 A 的舊表單直接更正 → 409 RECORD_CHANGED；B 與歷程都不變")
    void staleDirectCorrectionFormCannotHitReplacementRecord() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        TestBrowser daveB = loginAs("dave");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        long recordA = activeId(alice, DAY);
        long versionA = activeVersion(alice, DAY);
        assertThat(versionA).isZero();
        // carol 打開 A 的更正表單（記住 A 的 ID 與版本 0）；在她送出之前……
        clock.set(utc("2026-10-01T02:00:00Z"));
        assertThat(directVoid(daveB, alice.getId(), recordA, versionA).status()).isEqualTo(200); // dave 作廢 A
        punch(aliceB, CLOCK_IN, "2026-10-01T02:10:00Z"); // 員工重新上班 → B
        long recordB = activeId(alice, DAY);
        assertThat(recordB).isNotEqualTo(recordA);
        assertThat(activeVersion(alice, DAY)).as("B 的版本剛好也是 0").isEqualTo(versionA);
        int historyBefore = historyRows();

        clock.set(utc("2026-10-01T03:00:00Z"));
        Reply stale = carolB.post(ADMIN + "/records/correct", directBody(alice.getId(), recordA, versionA, "08:30:00", null, "舊表單"));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("RECORD_CHANGED");
        assertThat(activeId(alice, DAY)).isEqualTo(recordB);
        assertThat(activeColumn(alice, DAY, "clock_in_at")).as("B 沒被改").startsWith("2026-10-01 02:10:00");
        assertThat(activeVersion(alice, DAY)).isZero();
        assertThat(activeColumn(alice, DAY, "corrected_at")).isNull();
        assertThat(jdbc.queryForObject("select version from attendance where id = ?", Long.class, recordA)).as("A 只因作廢 +1").isEqualTo(1L);
        assertThat(historyRows()).as("被拒絕的操作不寫歷程").isEqualTo(historyBefore);
        // 即使改用 A 作廢後的版本（1），A 已作廢仍被拒絕
        assertThat(carolB.post(ADMIN + "/records/correct", directBody(alice.getId(), recordA, 1L, "08:30:00", null, "已作廢")).code())
                .isEqualTo("RECORD_CHANGED");
    }

    @Test
    @DisplayName("F-02 R1：只帶 attendanceId 或只帶 expectedVersion → 400 INVALID_REQUEST；資料不變")
    void incompleteIdVersionPairIsRejected() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        clock.set(utc("2026-10-01T02:00:00Z"));
        long id = activeId(alice, DAY);

        Reply onlyId = carolB.post(ADMIN + "/records/correct", directBody(alice.getId(), id, null, "08:30:00", null, "只有 ID"));
        Reply onlyVersion = carolB.post(ADMIN + "/records/correct", directBody(alice.getId(), null, 0L, "08:30:00", null, "只有版本"));

        assertThat(onlyId.status()).isEqualTo(400);
        assertThat(onlyId.code()).isEqualTo("INVALID_REQUEST");
        assertThat(onlyVersion.code()).isEqualTo("INVALID_REQUEST");
        assertThat(activeVersion(alice, DAY)).isZero();
        assertThat(historyRows()).isZero();
    }

    @Test
    @DisplayName("F-03 R1：attendanceId 屬於別的員工或別的日期 → 409 RECORD_CHANGED，兩筆都不被修改")
    void attendanceIdMustBelongToEmployeeAndDate() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser bobB = loginAs("bob");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        punch(bobB, CLOCK_IN, "2026-10-01T01:05:00Z");
        seedRecord(alice, "2026-09-30", "2026-09-30T01:00:00Z", "2026-09-30T10:00:00Z");
        clock.set(utc("2026-10-01T03:00:00Z"));

        Reply othersRecord = carolB.post(ADMIN + "/records/correct", directBody(alice.getId(), activeId(bob, DAY), 0L, "08:30:00", null, "別人的紀錄"));
        Reply otherDate = carolB.post(ADMIN + "/records/correct", directBody(alice.getId(), activeId(alice, "2026-09-30"), 0L, "08:30:00", null, "別天的紀錄"));

        assertThat(othersRecord.code()).isEqualTo("RECORD_CHANGED");
        assertThat(otherDate.code()).isEqualTo("RECORD_CHANGED");
        assertThat(activeColumn(bob, DAY, "clock_in_at")).startsWith("2026-10-01 01:05:00");
        assertThat(activeColumn(alice, "2026-09-30", "clock_in_at")).startsWith("2026-09-30 01:00:00");
        assertThat(historyRows()).isZero();
    }

    // ===================== 申請快照 =====================

    @Test
    @DisplayName("F-12 表單讀取後紀錄被替換：用舊快照申請整組作廢 → 409 RECORD_CHANGED，沒有建立申請；以新快照則可提出")
    void staleSnapshotCannotBeUsedToSubmit() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        var seen = aliceB.get(TODAY).json(); // 員工打開表單時看到的紀錄 A
        long seenId = seen.get("recordId").asLong();
        long seenVersion = seen.get("version").asLong();
        clock.set(utc("2026-10-01T02:00:00Z"));
        assertThat(directVoid(carolB, alice.getId(), seenId, seenVersion).status()).isEqualTo(200);
        punch(aliceB, CLOCK_IN, "2026-10-01T02:10:00Z"); // 新紀錄 B，版本同為 0

        Reply stale = aliceB.post(REQUESTS, "{\"type\":\"RESET_TODAY\",\"reason\":\"舊畫面\",\"expectedAttendanceId\":" + seenId + ",\"expectedVersion\":" + seenVersion + "}");

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("RECORD_CHANGED");
        assertThat(requestRows()).isZero();
        assertThat(submitReset(aliceB, "依新畫面提出").status()).as("重新取得快照後可以提出").isEqualTo(201);
    }

    @Test
    @DisplayName("F-12 快照規則：補登時看到「沒有紀錄」但其實已有 → 409；快照不完整 → 400；整組作廢沒附快照 → 400")
    void snapshotRules() {
        TestBrowser aliceB = loginAs("alice");
        seedRecord(alice, "2026-09-30", "2026-09-30T01:00:00Z", "2026-09-30T10:00:00Z");

        Reply sawNothing = aliceB.post(REQUESTS, json("type", str("CORRECT_TIME"), "workDate", str("2026-09-30"),
                "clockInTime", str("08:00:00"), "clockOutTime", "null", "reason", str("我以為沒紀錄")));
        assertThat(sawNothing.status()).isEqualTo(409);
        assertThat(sawNothing.code()).isEqualTo("ATTENDANCE_OVERLAP");

        Reply incomplete = aliceB.post(REQUESTS, json("type", str("CORRECT_TIME"), "workDate", str("2026-09-30"),
                "clockInTime", str("08:00:00"), "reason", str("只有 ID"), "expectedAttendanceId", String.valueOf(activeId(alice, "2026-09-30"))));
        assertThat(incomplete.status()).isEqualTo(400);
        assertThat(incomplete.code()).isEqualTo("INVALID_REQUEST");

        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        Reply resetWithoutSnapshot = aliceB.post(REQUESTS, json("type", str("RESET_TODAY"), "reason", str("沒附快照")));
        assertThat(resetWithoutSnapshot.status()).isEqualTo(400);
        assertThat(requestRows()).isZero();
    }

    // ===================== 撤銷下班 vs 整組作廢 =====================

    @Test
    @DisplayName("F-08 撤銷下班核准：同一筆紀錄、上班時間（含小數秒）完全不變、下班為 null、版本 +1、WORKING；之後可再下班")
    void revokeClockOutKeepsRecordAndClockIn() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00.123456Z");
        punch(aliceB, CLOCK_OUT, "2026-10-01T01:05:00Z"); // 誤按下班
        long recordId = activeId(alice, DAY);
        long versionBefore = activeVersion(alice, DAY);
        clock.set(utc("2026-10-01T02:00:00Z"));

        Reply created = submitRevoke(aliceB, "09:00:00.123456");
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().get("kind").asString()).isEqualTo("REVOKE_CLOCK_OUT");
        assertThat(aliceB.get(TODAY).json().get("status").asString()).as("核准前不改").isEqualTo("COMPLETED");

        assertThat(approve(carolB, created.json().get("id").asLong()).status()).isEqualTo(200);

        assertThat(activeId(alice, DAY)).as("同一筆紀錄").isEqualTo(recordId);
        assertThat(jdbc.queryForObject("select (clock_in_at at time zone 'UTC')::text from attendance where id = ?", String.class, recordId))
                .as("上班時間完全不變（含微秒）").isEqualTo("2026-10-01 01:00:00.123456");
        assertThat(activeColumn(alice, DAY, "clock_out_at")).isNull();
        assertThat(activeVersion(alice, DAY)).isEqualTo(versionBefore + 1);
        assertThat(voidedRows(alice)).as("不作廢").isZero();
        assertThat(jdbc.queryForObject("select count(*) from attendance where employee_id = ?", Integer.class, alice.getId())).as("不新增").isEqualTo(1);
        assertThat(aliceB.get(TODAY).json().get("status").asString()).isEqualTo("WORKING");

        punch(aliceB, CLOCK_OUT, "2026-10-01T09:00:00Z");
        assertThat(aliceB.get(TODAY).json().get("status").asString()).isEqualTo("COMPLETED");
        var entry = aliceB.get("/api/attendance/me/history").json().get("entries").get(0);
        assertThat(entry.get("operation").asString()).isEqualTo("REQUEST_APPROVED");
        assertThat(entry.get("requestKind").asString()).isEqualTo("REVOKE_CLOCK_OUT");
        assertThat(entry.get("after").get("voided").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("F-09 沒有下班時間時「撤銷下班」不成立：希望值與目前相同 → 400 INVALID_REQUEST")
    void revokeWithoutClockOutIsRejected() {
        TestBrowser aliceB = loginAs("alice");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        clock.set(utc("2026-10-01T02:00:00Z"));

        Reply reply = submitRevoke(aliceB, "09:00:00");

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.code()).isEqualTo("INVALID_REQUEST");
        assertThat(requestRows()).isZero();
    }

    @Test
    @DisplayName("F-10 撤銷下班申請在待審、撤回、退回時，正式出勤都不變")
    void pendingCancelledRejectedRevokeLeavesAttendanceUntouched() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        punch(aliceB, CLOCK_OUT, "2026-10-01T01:05:00Z");
        long version = activeVersion(alice, DAY);
        clock.set(utc("2026-10-01T02:00:00Z"));

        long first = idOf(submitRevoke(aliceB, "09:00:00"));
        assertThat(activeColumn(alice, DAY, "clock_out_at")).as("待審").startsWith("2026-10-01 01:05:00");
        assertThat(aliceB.post(REQUESTS + "/" + first + "/cancel", null).status()).isEqualTo(200);
        assertThat(activeColumn(alice, DAY, "clock_out_at")).as("撤回").startsWith("2026-10-01 01:05:00");
        long second = idOf(submitRevoke(aliceB, "09:00:00"));
        assertThat(reject(carolB, second, "請確認實際時間").status()).isEqualTo(200);
        assertThat(activeColumn(alice, DAY, "clock_out_at")).as("退回").startsWith("2026-10-01 01:05:00");
        assertThat(activeVersion(alice, DAY)).isEqualTo(version);
        assertThat(aliceB.get(TODAY).json().get("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("F-11 撤銷下班申請過時：申請後被更正、或被作廢後重新打卡 → 核准 409 REQUEST_STALE，較新的紀錄保留")
    void staleRevokeCannotTouchNewerData() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        TestBrowser daveB = loginAs("dave");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        punch(aliceB, CLOCK_OUT, "2026-10-01T01:05:00Z");
        clock.set(utc("2026-10-01T02:00:00Z"));
        long revoke = idOf(submitRevoke(aliceB, "09:00:00"));

        // 情境一：申請之後，另一位管理員直接更正了時間
        long id = activeId(alice, DAY);
        assertThat(daveB.post(ADMIN + "/records/correct", directBody(alice.getId(), id, activeVersion(alice, DAY), "08:50:00", "09:05:00", "當面確認")).status()).isEqualTo(200);
        Reply stale = approve(carolB, revoke);
        assertThat(stale.code()).isEqualTo("REQUEST_STALE");
        assertThat(activeColumn(alice, DAY, "clock_out_at")).as("較新的下班時間保留").startsWith("2026-10-01 01:05:00");
        assertThat(activeColumn(alice, DAY, "clock_in_at")).startsWith("2026-10-01 00:50:00");

        // 情境二：撤回後重新申請，接著紀錄被作廢、員工重新打卡
        assertThat(aliceB.post(REQUESTS + "/" + revoke + "/cancel", null).status()).isEqualTo(200);
        long revoke2 = idOf(submitRevoke(aliceB, "08:50:00"));
        assertThat(directVoid(daveB, alice.getId(), activeId(alice, DAY), activeVersion(alice, DAY)).status()).isEqualTo(200);
        punch(aliceB, CLOCK_IN, "2026-10-01T03:00:00Z");
        punch(aliceB, CLOCK_OUT, "2026-10-01T03:30:00Z");
        long newRecord = activeId(alice, DAY);

        assertThat(approve(carolB, revoke2).code()).isEqualTo("REQUEST_STALE");
        assertThat(activeId(alice, DAY)).isEqualTo(newRecord);
        assertThat(activeColumn(alice, DAY, "clock_out_at")).as("新紀錄的下班時間沒被撤銷").startsWith("2026-10-01 03:30:00");
    }

    @Test
    @DisplayName("F-13／F-14 整組作廢 vs 撤銷下班：前者作廢舊紀錄（只有上班也能提出）、後者同一筆不作廢；kind 與歷程可辨識")
    void resetAndRevokeAreDistinguishable() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser bobB = loginAs("bob");
        TestBrowser carolB = loginAs("carol");
        // alice：只有上班就申請整組作廢（F-14）
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        clock.set(utc("2026-10-01T02:00:00Z"));
        Reply reset = submitReset(aliceB, "整組重打");
        assertThat(reset.json().get("kind").asString()).isEqualTo("RESET_TODAY");
        assertThat(approve(carolB, reset.json().get("id").asLong()).status()).isEqualTo(200);
        // bob：撤銷下班
        punch(bobB, CLOCK_IN, "2026-10-01T01:00:00Z");
        punch(bobB, CLOCK_OUT, "2026-10-01T01:05:00Z");
        clock.set(utc("2026-10-01T02:00:00Z"));
        Reply revoke = submitRevoke(bobB, "09:00:00");
        assertThat(revoke.json().get("kind").asString()).isEqualTo("REVOKE_CLOCK_OUT");
        assertThat(approve(carolB, revoke.json().get("id").asLong()).status()).isEqualTo(200);

        assertThat(aliceB.get(TODAY).json().get("status").asString()).isEqualTo("NOT_STARTED");
        assertThat(voidedRows(alice)).isEqualTo(1);
        assertThat(activeRows(alice)).isZero();
        assertThat(bobB.get(TODAY).json().get("status").asString()).isEqualTo("WORKING");
        assertThat(voidedRows(bob)).isZero();
        assertThat(activeRows(bob)).isEqualTo(1);

        var adminList = carolB.get(ADMIN + "/requests?status=ALL").json().get("requests");
        assertThat(adminList.toString()).contains("\"kind\":\"RESET_TODAY\"").contains("\"kind\":\"REVOKE_CLOCK_OUT\"");
        assertThat(aliceB.get("/api/attendance/me/history").json().get("entries").get(0).get("after").get("voided").asBoolean()).isTrue();
        assertThat(bobB.get("/api/attendance/me/history").json().get("entries").get(0).get("after").get("voided").asBoolean()).isFalse();
    }
}
