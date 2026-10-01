package com.renewflow.eip.attendance;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T03.1 管理端：授權矩陣、核准／退回的狀態轉移、直接更正／作廢、歷程。編號對應 specification.md。 */
class AttendanceAdminIntegrationTest extends CorrectionsTestBase {

    // ---------------- C-18 授權矩陣 ----------------

    private void assertAllAdminEndpoints(TestBrowser browser, int expectedStatus) {
        String body = json("reason", str("x"), "employeeId", String.valueOf(alice.getId()), "workDate", str("2026-10-01"),
                "clockInTime", str("08:00:00"), "attendanceId", "1", "expectedVersion", "0");
        assertThat(browser.get(ADMIN + "/requests").status()).as("GET requests").isEqualTo(expectedStatus);
        assertThat(browser.get(ADMIN + "/requests/1").status()).as("GET request").isEqualTo(expectedStatus);
        assertThat(browser.post(ADMIN + "/requests/1/approve", "{}").status()).as("approve").isEqualTo(expectedStatus);
        assertThat(browser.post(ADMIN + "/requests/1/reject", body).status()).as("reject").isEqualTo(expectedStatus);
        assertThat(browser.get(ADMIN + "/employees").status()).as("employees").isEqualTo(expectedStatus);
        assertThat(browser.get(ADMIN + "/records?employeeId=" + alice.getId()).status()).as("records").isEqualTo(expectedStatus);
        assertThat(browser.post(ADMIN + "/records/correct", body).status()).as("correct").isEqualTo(expectedStatus);
        assertThat(browser.post(ADMIN + "/records/void", body).status()).as("void").isEqualTo(expectedStatus);
        assertThat(browser.get(ADMIN + "/history?employeeId=" + alice.getId()).status()).as("history").isEqualTo(expectedStatus);
    }

    @Test
    @DisplayName("C-18 員工與主管直接呼叫每一個管理 API → 403；匿名 → 401；資料不變")
    void adminApiIsBackendProtected() {
        TestBrowser aliceB = loginAs("alice");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        long id = idOf(submitReset(aliceB, "測試"));

        assertAllAdminEndpoints(aliceB, 403);
        assertAllAdminEndpoints(loginAs("bob"), 403); // MANAGER 不自動取得管理權限
        TestBrowser anonymous = new TestBrowser(port);
        anonymous.get("/api/auth/me"); // 取得 CSRF token，才能驗證「被認證擋下」而不是被 CSRF 擋下
        assertAllAdminEndpoints(anonymous, 401);

        assertThat(requestStatus(id)).isEqualTo("PENDING");
        assertThat(activeRows(alice)).isEqualTo(1);
        assertThat(voidedRows(alice)).isZero();
        // 403 是角色不足，而不是 CSRF
        Reply forbidden = aliceB.get(ADMIN + "/requests");
        assertThat(forbidden.code()).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("C-19 管理員被降級後，舊 Session 呼叫管理 API → 401（需重新登入）；重新登入後 → 403")
    void demotedAdminLosesAccess() {
        TestBrowser carolB = loginAs("carol");
        assertThat(carolB.get(ADMIN + "/requests").status()).isEqualTo(200);

        jdbc.update("update employee set role = 'EMPLOYEE' where id = ?", carol.getId());

        assertThat(carolB.get(ADMIN + "/requests").status()).isEqualTo(401);
        carolB.login("carol", PASSWORD);
        assertThat(carolB.get(ADMIN + "/requests").status()).isEqualTo(403);
    }

    // ---------------- C-07 / C-08 / C-09 重新打卡 ----------------

    @Test
    @DisplayName("C-07/C-08 核准重新打卡：原紀錄作廢且保留原時間；今日回到尚未上班；可正常重新上班與下班；只有一筆有效紀錄")
    void approveResetThenClockAgain() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        punch(aliceB, CLOCK_OUT, "2026-10-01T05:00:00Z");
        clock.set(utc("2026-10-01T06:00:00Z"));
        long id = idOf(submitReset(aliceB, "測試功能時誤按上下班，希望重新打卡"));
        long originalRecordId = activeId(alice, "2026-10-01");

        clock.set(utc("2026-10-01T06:10:00Z"));
        Reply approved = approve(carolB, id);

        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.json().get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.json().get("reviewerName").asString()).isEqualTo("卡蘿");
        // 原紀錄：作廢、保留原時間、記錄作廢者與版本
        var voided = jdbc.queryForMap("select (clock_in_at at time zone 'UTC')::text as i, (clock_out_at at time zone 'UTC')::text as o, voided_by, version, (voided_at at time zone 'UTC')::text as v from attendance where id = ?", originalRecordId);
        assertThat(voided.get("i")).asString().startsWith("2026-10-01 01:00:00");
        assertThat(voided.get("o")).asString().startsWith("2026-10-01 05:00:00");
        assertThat(voided.get("voided_by")).isEqualTo(carol.getId());
        assertThat(voided.get("version")).isEqualTo(2L); // 上班(0) → 下班(1) → 作廢(2)：每次寫入都 +1
        assertThat(voided.get("v")).asString().startsWith("2026-10-01 06:10:00");
        assertThat(activeRows(alice)).isZero();
        // 今日回到尚未上班（作廢的紀錄不是「工作中」）
        Reply today = aliceB.get(TODAY);
        assertThat(today.json().get("status").asString()).isEqualTo("NOT_STARTED");
        assertThat(today.json().get("unfinishedBefore").isNull()).isTrue();
        assertThat(aliceB.post(CLOCK_OUT, null).code()).as("沒有有效紀錄可下班").isEqualTo("CLOCK_IN_REQUIRED");

        // 重新打卡：一組新的有效紀錄
        punch(aliceB, CLOCK_IN, "2026-10-01T06:30:00Z");
        punch(aliceB, CLOCK_OUT, "2026-10-01T07:00:00Z");
        assertThat(activeRows(alice)).isEqualTo(1);
        assertThat(voidedRows(alice)).isEqualTo(1);
        assertThat(aliceB.post(CLOCK_IN, null).code()).as("新的紀錄仍受每日一組限制").isEqualTo("ALREADY_CLOCKED_IN");

        // 列表同時含兩筆（有效＋作廢），同日期以 id 區分
        Reply list = aliceB.get("/api/attendance/me?from=2026-10-01&to=2026-10-01");
        assertThat(list.json().get("records").size()).isEqualTo(2);
        assertThat(list.json().get("records").get(0).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(list.json().get("records").get(1).get("status").asString()).isEqualTo("VOIDED");
        assertThat(list.json().get("records").get(1).get("clockInAt").asString()).isEqualTo("2026-10-01T01:00:00Z");
        assertThat(list.json().get("records").get(0).get("id").asLong()).isNotEqualTo(list.json().get("records").get(1).get("id").asLong());
    }

    @Test
    @DisplayName("C-09 作廢紀錄不觸發「未完成」提醒：昨日上班未下班、被作廢後，隔天不再提醒")
    void voidedRecordDoesNotTriggerUnfinishedReminder() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        long id = idOf(submitReset(aliceB, "誤按上班"));
        assertThat(approve(carolB, id).status()).isEqualTo(200);

        clock.set(utc("2026-10-02T01:00:00Z")); // 隔天
        Reply today = aliceB.get(TODAY);

        assertThat(today.json().get("status").asString()).isEqualTo("NOT_STARTED");
        assertThat(today.json().get("unfinishedBefore").isNull()).as("作廢的舊紀錄不算未完成").isTrue();
    }

    @Test
    @DisplayName("C-12 跨過台北日期後核准重新打卡 → 409 RESET_DATE_PASSED；申請仍待審、紀錄不變、顯示為過時")
    void resetCannotBeApprovedAfterDateChange() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        long id = idOf(submitReset(aliceB, "今天的誤按"));

        clock.set(utc("2026-10-01T16:30:00Z")); // 台北已是 10/2 00:30（UTC 仍是 10/1）
        Reply approved = approve(carolB, id);

        assertThat(approved.status()).isEqualTo(409);
        assertThat(approved.code()).isEqualTo("RESET_DATE_PASSED");
        assertThat(approved.json().get("message").asString()).contains("更正");
        assertThat(requestStatus(id)).isEqualTo("PENDING");
        assertThat(activeRows(alice)).isEqualTo(1);
        assertThat(voidedRows(alice)).isZero();
        assertThat(carolB.get(ADMIN + "/requests/" + id).json().get("stale").asBoolean()).isTrue();
    }

    // ---------------- C-10 更正／補登／撤銷下班 ----------------

    @Test
    @DisplayName("C-10 核准補登（原本沒有紀錄）：建立 MANUAL 的有效紀錄，時間符合申請")
    void approveMissedPunch() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        long id = idOf(submitCorrection(aliceB, "2026-09-30", "09:00:00", "18:00:00", "忘記打卡"));

        assertThat(approve(carolB, id).status()).isEqualTo(200);

        assertThat(activeColumn(alice, "2026-09-30", "source")).isEqualTo("MANUAL");
        assertThat(activeColumn(alice, "2026-09-30", "clock_in_at")).startsWith("2026-09-30 01:00:00");
        assertThat(activeColumn(alice, "2026-09-30", "clock_out_at")).startsWith("2026-09-30 10:00:00");
        assertThat(activeColumn(alice, "2026-09-30", "corrected_at")).isNotNull().isNotEqualTo("<none>");
        Reply list = aliceB.get("/api/attendance/me?from=2026-09-30&to=2026-09-30");
        assertThat(list.json().get("records").get(0).get("source").asString()).isEqualTo("MANUAL");
        assertThat(list.json().get("records").get(0).get("corrected").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("C-10 核准更正既有時間：套用申請值、版本 +1、標示已更正；歷程記錄前後值")
    void approveTimeCorrection() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        punch(aliceB, CLOCK_OUT, "2026-10-01T05:00:00Z");
        clock.set(utc("2026-10-01T10:00:00Z"));
        long version = activeVersion(alice, "2026-10-01");
        long id = idOf(submitCorrection(aliceB, "2026-10-01", "08:30:00", "17:30:00", "實際 8:30 就到了"));

        clock.set(utc("2026-10-01T10:05:00Z"));
        assertThat(approve(carolB, id).status()).isEqualTo(200);

        assertThat(activeColumn(alice, "2026-10-01", "clock_in_at")).startsWith("2026-10-01 00:30:00");
        assertThat(activeColumn(alice, "2026-10-01", "clock_out_at")).startsWith("2026-10-01 09:30:00");
        assertThat(activeVersion(alice, "2026-10-01")).isEqualTo(version + 1);
        assertThat(activeColumn(alice, "2026-10-01", "source")).isEqualTo("CLOCK");

        // C-22 歷程：操作者、前後值、原因、時間、審核結果
        var entries = loginAs("alice").get("/api/attendance/me/history").json().get("entries");
        var approvedEntry = entries.get(0);
        assertThat(approvedEntry.get("operation").asString()).isEqualTo("REQUEST_APPROVED");
        assertThat(approvedEntry.get("actorName").asString()).isEqualTo("卡蘿");
        assertThat(approvedEntry.get("result").asString()).isEqualTo("APPROVED");
        assertThat(approvedEntry.get("original").get("clockInAt").asString()).isEqualTo("2026-10-01T01:00:00Z");
        assertThat(approvedEntry.get("requested").get("clockInAt").asString()).isEqualTo("2026-10-01T00:30:00Z");
        assertThat(approvedEntry.get("after").get("clockInAt").asString()).isEqualTo("2026-10-01T00:30:00Z");
        assertThat(approvedEntry.get("after").get("clockOutAt").asString()).isEqualTo("2026-10-01T09:30:00Z");
        assertThat(approvedEntry.get("after").get("voided").asBoolean()).isFalse();
        assertThat(approvedEntry.get("occurredAt").asString()).isEqualTo("2026-10-01T10:05:00Z");
        assertThat(entries.get(1).get("operation").asString()).isEqualTo("REQUEST_SUBMITTED");
    }

    @Test
    @DisplayName("C-10 核准撤銷誤按的下班：今日恢復 WORKING，之後可再次下班")
    void approveClockOutRevocation() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        punch(aliceB, CLOCK_OUT, "2026-10-01T01:05:00Z");
        clock.set(utc("2026-10-01T02:00:00Z"));
        long id = idOf(submitCorrection(aliceB, "2026-10-01", "09:00:00", null, "誤按下班"));
        assertThat(approve(carolB, id).status()).isEqualTo(200);

        assertThat(aliceB.get(TODAY).json().get("status").asString()).isEqualTo("WORKING");

        punch(aliceB, CLOCK_OUT, "2026-10-01T09:00:00Z");
        assertThat(aliceB.get(TODAY).json().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(activeRows(alice)).isEqualTo(1);
    }

    // ---------------- C-16 退回 ----------------

    @Test
    @DisplayName("C-16 退回：原因必填（空白 400）；退回後出勤不變、員工看得到退回原因；可重新提出")
    void rejectFlow() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        long id = idOf(submitReset(aliceB, "想重打"));

        Reply blank = carolB.post(ADMIN + "/requests/" + id + "/reject", json("reason", str("  ")));
        assertThat(blank.status()).isEqualTo(400);
        assertThat(requestStatus(id)).isEqualTo("PENDING");

        Reply rejected = reject(carolB, id, "請改用更正申請並附上說明");
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.json().get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.json().get("reviewComment").asString()).isEqualTo("請改用更正申請並附上說明");
        assertThat(activeRows(alice)).isEqualTo(1);
        assertThat(voidedRows(alice)).isZero();

        var mine = aliceB.get(REQUESTS).json().get("requests").get(0);
        assertThat(mine.get("status").asString()).isEqualTo("REJECTED");
        assertThat(mine.get("reviewComment").asString()).contains("更正申請");
        assertThat(submitReset(aliceB, "再次提出").status()).as("退回後待審名額已釋出").isEqualTo(201);
        assertThat(aliceB.get("/api/attendance/me/history").body()).contains("REQUEST_REJECTED");
    }

    // ---------------- C-14 非法狀態轉移 ----------------

    @Test
    @DisplayName("C-14 重複核准、核准後退回、撤回後核准、不存在 → 409 REQUEST_NOT_PENDING／404")
    void illegalTransitions() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        TestBrowser daveB = loginAs("dave");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        long approvedId = idOf(submitReset(aliceB, "A"));
        assertThat(approve(carolB, approvedId).status()).isEqualTo(200);

        assertThat(approve(daveB, approvedId).code()).isEqualTo("REQUEST_NOT_PENDING");
        assertThat(reject(daveB, approvedId, "晚了").code()).isEqualTo("REQUEST_NOT_PENDING");
        assertThat(aliceB.post(REQUESTS + "/" + approvedId + "/cancel", null).code()).isEqualTo("REQUEST_NOT_PENDING");

        punch(aliceB, CLOCK_IN, "2026-10-01T02:00:00Z");
        long cancelledId = idOf(submitReset(aliceB, "B"));
        assertThat(aliceB.post(REQUESTS + "/" + cancelledId + "/cancel", null).status()).isEqualTo(200);
        assertThat(approve(carolB, cancelledId).code()).isEqualTo("REQUEST_NOT_PENDING");
        assertThat(activeRows(alice)).as("撤回後的申請不得被套用").isEqualTo(1);

        assertThat(approve(carolB, 999999).status()).isEqualTo(404);
        assertThat(approve(carolB, 999999).code()).isEqualTo("REQUEST_NOT_FOUND");
    }

    // ---------------- C-13 過時 ----------------

    @Test
    @DisplayName("C-13 過時：申請後員工又打卡 → 核准 409 REQUEST_STALE，不覆蓋較新的資料；撤回後重新提出即可核准")
    void staleRequestDoesNotOverwriteNewerData() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        clock.set(utc("2026-10-01T02:00:00Z"));
        long id = idOf(submitCorrection(aliceB, "2026-10-01", "08:30:00", null, "想把上班時間改早"));

        punch(aliceB, CLOCK_OUT, "2026-10-01T05:00:00Z"); // 申請之後，資料變動（版本 +1）
        assertThat(carolB.get(ADMIN + "/requests/" + id).json().get("stale").asBoolean()).isTrue();

        clock.set(utc("2026-10-01T06:00:00Z"));
        Reply stale = approve(carolB, id);

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("REQUEST_STALE");
        assertThat(requestStatus(id)).as("失敗時申請維持待審").isEqualTo("PENDING");
        assertThat(activeColumn(alice, "2026-10-01", "clock_in_at")).startsWith("2026-10-01 01:00:00");
        assertThat(activeColumn(alice, "2026-10-01", "clock_out_at")).as("較新的下班時間沒有被覆蓋").startsWith("2026-10-01 05:00:00");

        assertThat(aliceB.post(REQUESTS + "/" + id + "/cancel", null).status()).isEqualTo(200);
        long fresh = idOf(submitCorrection(aliceB, "2026-10-01", "08:30:00", "13:00:00", "重新提出"));
        assertThat(approve(carolB, fresh).status()).isEqualTo(200);
        assertThat(activeColumn(alice, "2026-10-01", "clock_in_at")).startsWith("2026-10-01 00:30:00");
    }

    @Test
    @DisplayName("C-13 過時（補登）：申請後該日已經有紀錄 → 核准 409 REQUEST_STALE，既有紀錄不被取代")
    void missedPunchBecomesStaleWhenRecordAppears() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        long id = idOf(submitCorrection(aliceB, "2026-09-30", "09:00:00", "18:00:00", "忘記打卡"));
        seedRecord(alice, "2026-09-30", "2026-09-30T01:30:00Z", "2026-09-30T10:30:00Z"); // 這段期間已被補上

        Reply stale = approve(carolB, id);

        assertThat(stale.code()).isEqualTo("REQUEST_STALE");
        assertThat(activeRows(alice)).isEqualTo(1);
        assertThat(activeColumn(alice, "2026-09-30", "clock_in_at")).startsWith("2026-09-30 01:30:00");
        assertThat(requestStatus(id)).isEqualTo("PENDING");
    }

    // ---------------- C-17 職責分離 ----------------

    @Test
    @DisplayName("C-17 管理員不能核准／退回自己的申請（403 SELF_REVIEW_FORBIDDEN）；其他管理員可以")
    void adminCannotReviewOwnRequest() {
        TestBrowser carolB = loginAs("carol");
        TestBrowser daveB = loginAs("dave");
        punch(carolB, CLOCK_IN, "2026-10-01T01:00:00Z");
        long id = idOf(submitReset(carolB, "我自己的誤按"));

        Reply selfApprove = approve(carolB, id);
        assertThat(selfApprove.status()).isEqualTo(403);
        assertThat(selfApprove.code()).isEqualTo("SELF_REVIEW_FORBIDDEN");
        assertThat(reject(carolB, id, "自己退自己").code()).isEqualTo("SELF_REVIEW_FORBIDDEN");
        assertThat(requestStatus(id)).isEqualTo("PENDING");

        assertThat(approve(daveB, id).status()).isEqualTo(200);
        assertThat(activeRows(carol)).isZero();
    }

    // ---------------- C-20 / C-21 直接更正與作廢 ----------------

    private String correctionBody(long employeeId, String workDate, Long expectedVersion, String in, String out, String reason) {
        return json("employeeId", String.valueOf(employeeId), "workDate", str(workDate),
                "expectedVersion", expectedVersion == null ? "null" : String.valueOf(expectedVersion),
                "clockInTime", str(in), "clockOutTime", out == null ? "null" : str(out), "reason", str(reason));
    }

    @Test
    @DisplayName("C-20 直接更正：成功套用並寫入歷程（操作者、前後值、原因）；版本 +1")
    void directCorrectionSucceedsWithHistory() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        punch(aliceB, CLOCK_OUT, "2026-10-01T05:00:00Z");
        clock.set(utc("2026-10-01T06:00:00Z"));
        long version = activeVersion(alice, "2026-10-01");

        Reply reply = carolB.post(ADMIN + "/records/correct", correctionBody(alice.getId(), "2026-10-01", version, "08:30:00", "13:00:00", "主管當面確認實際時間"));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("version").asLong()).isEqualTo(version + 1);
        assertThat(reply.json().get("corrected").asBoolean()).isTrue();
        assertThat(activeColumn(alice, "2026-10-01", "clock_in_at")).startsWith("2026-10-01 00:30:00");
        var entry = aliceB.get("/api/attendance/me/history").json().get("entries").get(0);
        assertThat(entry.get("operation").asString()).isEqualTo("DIRECT_CORRECTION");
        assertThat(entry.get("actorName").asString()).isEqualTo("卡蘿");
        assertThat(entry.get("reason").asString()).isEqualTo("主管當面確認實際時間");
        assertThat(entry.get("original").get("clockInAt").asString()).isEqualTo("2026-10-01T01:00:00Z");
        assertThat(entry.get("after").get("clockInAt").asString()).isEqualTo("2026-10-01T00:30:00Z");
        assertThat(entry.get("occurredAt").asString()).isEqualTo("2026-10-01T06:00:00Z");
    }

    @Test
    @DisplayName("C-20 直接更正的防線：原因必填、對象不能是自己、員工不存在、版本不符、時間不合理、建立時該日已有紀錄")
    void directCorrectionGuards() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        clock.set(utc("2026-10-01T06:00:00Z"));
        long version = activeVersion(alice, "2026-10-01");
        String path = ADMIN + "/records/correct";

        assertThat(carolB.post(path, correctionBody(alice.getId(), "2026-10-01", version, "08:30:00", null, "  ")).status()).isEqualTo(400);
        Reply self = carolB.post(path, correctionBody(carol.getId(), "2026-10-01", null, "08:30:00", null, "改自己"));
        assertThat(self.status()).isEqualTo(403);
        assertThat(self.code()).isEqualTo("SELF_CORRECTION_FORBIDDEN");
        assertThat(carolB.post(path, correctionBody(999999, "2026-10-01", null, "08:30:00", null, "不存在")).code()).isEqualTo("EMPLOYEE_NOT_FOUND");
        Reply wrongVersion = carolB.post(path, correctionBody(alice.getId(), "2026-10-01", version + 5, "08:30:00", null, "版本不符"));
        assertThat(wrongVersion.status()).isEqualTo(409);
        assertThat(wrongVersion.code()).isEqualTo("RECORD_CHANGED");
        assertThat(carolB.post(path, correctionBody(alice.getId(), "2026-10-01", version, "20:00:00", null, "未來時間")).code()).isEqualTo("INVALID_REQUEST");
        assertThat(carolB.post(path, correctionBody(alice.getId(), "2026-10-01", null, "08:30:00", null, "該日已有紀錄卻要建立")).code()).isEqualTo("RECORD_CHANGED");

        assertThat(activeColumn(alice, "2026-10-01", "clock_in_at")).as("以上全部被擋，紀錄沒有被改").startsWith("2026-10-01 01:00:00");
        assertThat(historyRows()).as("被擋下的操作不寫歷程").isZero();
    }

    @Test
    @DisplayName("C-20 直接建立（補登）：沒有紀錄的日期，不帶 expectedVersion → 建立 MANUAL 紀錄並寫歷程")
    void directCreation() {
        TestBrowser carolB = loginAs("carol");

        Reply reply = carolB.post(ADMIN + "/records/correct", correctionBody(alice.getId(), "2026-09-30", null, "09:00:00", "18:00:00", "忘記打卡，當面確認"));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("source").asString()).isEqualTo("MANUAL");
        assertThat(activeRows(alice)).isEqualTo(1);
        assertThat(historyRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("C-21 直接作廢今日：原時間保留、員工可重新打卡、寫歷程；非今日 409、版本不符 409、別人的紀錄 404、對象是自己 403、原因空白 400")
    void directVoid() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        punch(aliceB, CLOCK_OUT, "2026-10-01T05:00:00Z");
        clock.set(utc("2026-10-01T06:00:00Z"));
        long id = activeId(alice, "2026-10-01");
        long version = activeVersion(alice, "2026-10-01");
        String path = ADMIN + "/records/void";

        assertThat(carolB.post(path, json("employeeId", String.valueOf(alice.getId()), "attendanceId", String.valueOf(id), "expectedVersion", String.valueOf(version), "reason", str(" "))).status()).isEqualTo(400);
        assertThat(carolB.post(path, json("employeeId", String.valueOf(alice.getId()), "attendanceId", String.valueOf(id), "expectedVersion", String.valueOf(version + 3), "reason", str("版本不符"))).code()).isEqualTo("RECORD_CHANGED");
        assertThat(carolB.post(path, json("employeeId", String.valueOf(bob.getId()), "attendanceId", String.valueOf(id), "expectedVersion", String.valueOf(version), "reason", str("不是他的紀錄"))).code()).isEqualTo("RECORD_NOT_FOUND");
        assertThat(carolB.post(path, json("employeeId", String.valueOf(carol.getId()), "attendanceId", String.valueOf(id), "expectedVersion", String.valueOf(version), "reason", str("自己"))).code()).isEqualTo("SELF_CORRECTION_FORBIDDEN");
        assertThat(activeRows(alice)).isEqualTo(1);

        Reply ok = carolB.post(path, json("employeeId", String.valueOf(alice.getId()), "attendanceId", String.valueOf(id), "expectedVersion", String.valueOf(version), "reason", str("測試誤按，管理員協助重打")));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.json().get("status").asString()).isEqualTo("VOIDED");
        assertThat(ok.json().get("clockInAt").asString()).as("原時間保留").isEqualTo("2026-10-01T01:00:00Z");
        assertThat(aliceB.get(TODAY).json().get("status").asString()).isEqualTo("NOT_STARTED");
        punch(aliceB, CLOCK_IN, "2026-10-01T06:30:00Z");
        assertThat(activeRows(alice)).isEqualTo(1);
        assertThat(aliceB.get("/api/attendance/me/history").json().get("entries").get(0).get("operation").asString()).isEqualTo("DIRECT_VOID");

        // 過去日期不能作廢
        seedRecord(alice, "2026-09-30", "2026-09-30T01:00:00Z", null);
        long yesterdayId = activeId(alice, "2026-09-30");
        Reply past = carolB.post(path, json("employeeId", String.valueOf(alice.getId()), "attendanceId", String.valueOf(yesterdayId), "expectedVersion", "0", "reason", str("過去")));
        assertThat(past.status()).isEqualTo(409);
        assertThat(past.code()).isEqualTo("VOID_DATE_PASSED");
    }

    // ---------------- 管理端查詢 ----------------

    @Test
    @DisplayName("管理端查詢：員工清單、指定員工的紀錄（含作廢與版本）、歷程；未知員工 404；範圍超過 31 天 400")
    void adminQueries() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        long id = idOf(submitReset(aliceB, "查詢測試"));
        assertThat(approve(carolB, id).status()).isEqualTo(200);

        Reply employees = carolB.get(ADMIN + "/employees");
        assertThat(employees.status()).isEqualTo(200);
        assertThat(employees.body()).contains("愛麗絲").contains("卡蘿").doesNotContain("password");

        Reply records = carolB.get(ADMIN + "/records?employeeId=" + alice.getId() + "&from=2026-10-01&to=2026-10-31");
        assertThat(records.status()).isEqualTo(200);
        assertThat(records.json().get("employee").get("displayName").asString()).isEqualTo("愛麗絲");
        assertThat(records.json().get("records").get(0).get("status").asString()).isEqualTo("VOIDED");
        assertThat(records.json().get("records").get(0).get("version").asLong()).isEqualTo(1L);

        Reply history = carolB.get(ADMIN + "/history?employeeId=" + alice.getId() + "&from=2026-10-01&to=2026-10-31");
        assertThat(history.json().get("entries").size()).isEqualTo(2);

        assertThat(carolB.get(ADMIN + "/records?employeeId=999999").code()).isEqualTo("EMPLOYEE_NOT_FOUND");
        assertThat(carolB.get(ADMIN + "/records?employeeId=" + alice.getId() + "&from=2026-09-01&to=2026-10-31").code()).isEqualTo("INVALID_DATE_RANGE");

        Reply pending = carolB.get(ADMIN + "/requests");
        assertThat(pending.json().get("requests").size()).as("已核准的不在待審清單").isZero();
        assertThat(carolB.get(ADMIN + "/requests?status=ALL").json().get("requests").size()).isEqualTo(1);
        assertThat(carolB.get(ADMIN + "/requests?status=BOGUS").code()).isEqualTo("INVALID_REQUEST");
    }
}
