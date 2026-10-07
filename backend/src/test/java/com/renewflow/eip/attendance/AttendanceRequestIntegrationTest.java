package com.renewflow.eip.attendance;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T03.1 員工端：提出／撤回申請、驗證、隔離。編號對應 docs/renewflow/t03-1/specification.md。 */
class AttendanceRequestIntegrationTest extends CorrectionsTestBase {

    // ---------------- C-01 輸入驗證 ----------------

    @Test
    @DisplayName("C-01 原因空白、只有空白、過長、缺類型 → 400 VALIDATION_FAILED，沒有建立申請")
    void invalidBodyIsRejected() {
        TestBrowser aliceB = loginAs("alice");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");

        assertThat(submitReset(aliceB, "").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(submitReset(aliceB, "   ").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(submitReset(aliceB, "x".repeat(501)).code()).isEqualTo("VALIDATION_FAILED");
        Reply noType = aliceB.post(REQUESTS, json("reason", str("測試")));
        assertThat(noType.status()).isEqualTo(400);
        assertThat(noType.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(aliceB.post(REQUESTS, "{ not json").status()).isEqualTo(400);
        assertThat(requestRows()).isZero();
        assertThat(historyRows()).isZero();
    }

    // ---------------- C-02 / C-04 重新打卡申請 ----------------

    @Test
    @DisplayName("C-02 今日沒有紀錄就申請重新打卡 → 409 NO_RECORD_TO_RESET")
    void resetWithoutRecord() {
        Reply reply = submitReset(loginAs("alice"), "誤按");

        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("NO_RECORD_TO_RESET");
        assertThat(requestRows()).isZero();
    }

    @Test
    @DisplayName("C-04 提出重新打卡申請：201 PENDING，帶原時間；出勤資料與今日狀態完全不變")
    void resetRequestDoesNotChangeAttendance() {
        TestBrowser aliceB = loginAs("alice");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        punch(aliceB, CLOCK_OUT, "2026-10-01T05:00:00Z");
        long versionBefore = activeVersion(alice, "2026-10-01");

        Reply created = submitReset(aliceB, "測試功能時誤按上下班，希望重新打卡");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().get("status").asString()).isEqualTo("PENDING");
        assertThat(created.json().get("type").asString()).isEqualTo("RESET_TODAY");
        assertThat(created.json().get("workDate").asString()).isEqualTo("2026-10-01");
        assertThat(created.json().get("original").get("clockInAt").asString()).isEqualTo("2026-10-01T01:00:00Z");
        assertThat(created.json().get("original").get("clockOutAt").asString()).isEqualTo("2026-10-01T05:00:00Z");
        assertThat(created.json().get("requested").isNull()).isTrue();
        assertThat(created.json().get("stale").asBoolean()).isFalse();
        assertThat(created.json().get("employee").get("displayName").asString()).isEqualTo("愛麗絲");
        // 申請不會立即更動打卡紀錄，也不會開放按鈕
        assertThat(aliceB.get(TODAY).json().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(activeVersion(alice, "2026-10-01")).isEqualTo(versionBefore);
        assertThat(aliceB.post(CLOCK_OUT, null).code()).isEqualTo("ALREADY_CLOCKED_OUT");
        assertThat(activeRows(alice)).isEqualTo(1);
        assertThat(voidedRows(alice)).isZero();
    }

    @Test
    @DisplayName("C-03 同日已有待審申請再申請（任一類型）→ 409 DUPLICATE_PENDING_REQUEST，只有一筆待審")
    void duplicatePendingIsRejected() {
        TestBrowser aliceB = loginAs("alice");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        assertThat(submitReset(aliceB, "第一次").status()).isEqualTo(201);

        Reply again = submitReset(aliceB, "第二次");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("DUPLICATE_PENDING_REQUEST");
        Reply correction = submitCorrection(aliceB, "2026-10-01", "08:00:00", null, "改成更正");
        assertThat(correction.code()).isEqualTo("DUPLICATE_PENDING_REQUEST");
        assertThat(requestRows()).isEqualTo(1);
    }

    // ---------------- C-05 / C-06 隔離與撤回 ----------------

    @Test
    @DisplayName("C-05 資料隔離：看不到、撤不了別人的申請（404）；清單只有自己的")
    void requestsAreIsolated() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser bobB = loginAs("bob");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        long aliceRequest = idOf(submitReset(aliceB, "alice 的申請"));
        punch(bobB, CLOCK_IN, "2026-10-01T01:05:00Z");
        idOf(submitReset(bobB, "bob 的申請"));

        Reply cancel = bobB.post(REQUESTS + "/" + aliceRequest + "/cancel", null);
        assertThat(cancel.status()).isEqualTo(404);
        assertThat(cancel.code()).isEqualTo("REQUEST_NOT_FOUND");
        assertThat(requestStatus(aliceRequest)).isEqualTo("PENDING");

        Reply bobList = bobB.get(REQUESTS);
        assertThat(bobList.json().get("requests").size()).isEqualTo(1);
        assertThat(bobList.json().get("requests").get(0).get("employee").get("displayName").asString()).isEqualTo("鮑伯");
        assertThat(aliceB.get(REQUESTS).json().get("requests").size()).isEqualTo(1);
        // 別人的歷程也看不到
        assertThat(bobB.get("/api/attendance/me/history").body()).doesNotContain("alice 的申請");
    }

    @Test
    @DisplayName("C-06 撤回：本人待審 → CANCELLED；再撤回 → 409 REQUEST_NOT_PENDING；不存在 → 404；撤回後可重新提出")
    void cancelFlow() {
        TestBrowser aliceB = loginAs("alice");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        long id = idOf(submitReset(aliceB, "先申請"));

        Reply cancelled = aliceB.post(REQUESTS + "/" + id + "/cancel", null);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.json().get("status").asString()).isEqualTo("CANCELLED");

        Reply again = aliceB.post(REQUESTS + "/" + id + "/cancel", null);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("REQUEST_NOT_PENDING");
        assertThat(aliceB.post(REQUESTS + "/999999/cancel", null).status()).isEqualTo(404);

        assertThat(submitReset(aliceB, "撤回後重新提出").status()).isEqualTo(201);
        assertThat(aliceB.get("/api/attendance/me/history").json().get("entries").size()).isEqualTo(3);
    }

    // ---------------- C-11 更正時間的驗證 ----------------

    @Test
    @DisplayName("C-11 更正／補登驗證：未來日期、未來時間、下班早於上班、缺上班時間、與目前相同 → 400 INVALID_REQUEST")
    void correctionValidation() {
        TestBrowser aliceB = loginAs("alice"); // 現在是台北 2026-10-01 09:00
        seedRecord(alice, "2026-10-01", "2026-10-01T00:30:00Z", "2026-10-01T00:50:00Z");

        assertThat(submitCorrection(aliceB, "2026-10-02", "08:00:00", null, "未來日期").code()).isEqualTo("INVALID_REQUEST");
        assertThat(submitCorrection(aliceB, "2026-10-01", "10:00:00", null, "未來上班時間").code()).isEqualTo("INVALID_REQUEST");
        assertThat(submitCorrection(aliceB, "2026-10-01", "08:00:00", "09:30:00", "未來下班時間").code()).isEqualTo("INVALID_REQUEST");
        assertThat(submitCorrection(aliceB, "2026-10-01", "08:30:00", "08:00:00", "下班早於上班").code()).isEqualTo("INVALID_REQUEST");
        assertThat(submitCorrection(aliceB, "2026-10-01", null, null, "缺上班時間").code()).isEqualTo("INVALID_REQUEST");
        Reply missingDate = aliceB.post(REQUESTS, json("type", str("CORRECT_TIME"), "clockInTime", str("08:00:00"), "reason", str("缺日期")));
        assertThat(missingDate.status()).isEqualTo(400);
        assertThat(missingDate.code()).isEqualTo("INVALID_REQUEST");
        // 與目前相同（08:30:00～08:50:00 = 00:30Z～00:50Z）
        Reply same = submitCorrection(aliceB, "2026-10-01", "08:30:00", "08:50:00", "沒有變更");
        assertThat(same.status()).isEqualTo(400);
        assertThat(same.code()).isEqualTo("INVALID_REQUEST");
        assertThat(requestRows()).isZero();
    }

    @Test
    @DisplayName("C-10 補登：沒有紀錄的過去日期可申請；台北當地時間換算成正確的時間點；原值為 null")
    void missedPunchRequest() {
        TestBrowser aliceB = loginAs("alice");

        Reply created = submitCorrection(aliceB, "2026-09-30", "09:00:00", "18:00:00", "忘記打卡");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().get("type").asString()).isEqualTo("CORRECT_TIME");
        assertThat(created.json().get("original").isNull()).isTrue();
        assertThat(created.json().get("requested").get("clockInAt").asString()).isEqualTo("2026-09-30T01:00:00Z");
        assertThat(created.json().get("requested").get("clockOutAt").asString()).isEqualTo("2026-09-30T10:00:00Z");
        assertThat(created.json().get("current").isNull()).isTrue();
        assertThat(created.json().get("stale").asBoolean()).isFalse();
        assertThat(activeRows(alice)).as("核准前不會有任何出勤").isZero();
    }

    @Test
    @DisplayName("C-10 申請撤銷誤按的下班（希望下班時間為空）：可提出，原值含下班、希望值不含")
    void revokeClockOutRequest() {
        TestBrowser aliceB = loginAs("alice");
        seedRecord(alice, "2026-10-01", "2026-10-01T00:30:00Z", "2026-10-01T00:50:00Z");

        Reply created = submitCorrection(aliceB, "2026-10-01", "08:30:00", null, "誤按下班");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().get("original").get("clockOutAt").asString()).isEqualTo("2026-10-01T00:50:00Z");
        assertThat(created.json().get("requested").get("clockOutAt").isNull()).isTrue();
    }

    // ---------------- 身份與安全 ----------------

    @Test
    @DisplayName("本文夾帶 employeeId／status／reviewerId 等欄位一律忽略：申請屬於 Session 身份、狀態為 PENDING")
    void forgedFieldsAreIgnored() {
        TestBrowser aliceB = loginAs("alice");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        var today = aliceB.get(TODAY).json();
        String forged = "{\"type\":\"RESET_TODAY\",\"reason\":\"偽造\",\"expectedAttendanceId\":" + today.get("recordId").asLong()
                + ",\"expectedVersion\":" + today.get("version").asLong() + ",\"employeeId\":" + bob.getId()
                + ",\"status\":\"APPROVED\",\"reviewerId\":" + carol.getId() + ",\"createdAt\":\"2000-01-01T00:00:00Z\"}";

        Reply created = aliceB.post(REQUESTS, forged);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().get("status").asString()).isEqualTo("PENDING");
        assertThat(created.json().get("employee").get("id").asLong()).isEqualTo(alice.getId());
        assertThat(jdbc.queryForObject("select employee_id from attendance_request", Long.class)).isEqualTo(alice.getId());
        assertThat(jdbc.queryForObject("select reviewer_id from attendance_request", Long.class)).isNull();
    }

    @Test
    @DisplayName("未登入 → 401；已登入缺 CSRF → 403；都沒有建立申請")
    void authenticationAndCsrf() {
        TestBrowser anonymous = new TestBrowser(port);
        assertThat(anonymous.get(REQUESTS).status()).isEqualTo(401);
        assertThat(anonymous.post(REQUESTS, json("type", str("RESET_TODAY"), "reason", str("x"))).status()).isEqualTo(401);

        TestBrowser aliceB = loginAs("alice");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        Reply noCsrf = aliceB.postWithCsrf(REQUESTS, json("type", str("RESET_TODAY"), "reason", str("x")), null);
        assertThat(noCsrf.status()).isEqualTo(403);
        assertThat(noCsrf.code()).isEqualTo("CSRF_REJECTED");
        assertThat(requestRows()).isZero();
    }

    @Test
    @DisplayName("C-22 本人歷程：提出後出現一筆 REQUEST_SUBMITTED，含操作者名稱、原因、原值與伺服器時間")
    void submissionIsRecordedInHistory() {
        TestBrowser aliceB = loginAs("alice");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        clock.set(utc("2026-10-01T02:00:00Z"));
        long id = idOf(submitReset(aliceB, "歷程測試"));

        Reply history = aliceB.get("/api/attendance/me/history");

        assertThat(history.json().get("entries").size()).isEqualTo(1);
        var entry = history.json().get("entries").get(0);
        assertThat(entry.get("operation").asString()).isEqualTo("REQUEST_SUBMITTED");
        assertThat(entry.get("requestId").asLong()).isEqualTo(id);
        assertThat(entry.get("reason").asString()).isEqualTo("歷程測試");
        assertThat(entry.get("actorName").asString()).isEqualTo("愛麗絲");
        assertThat(entry.get("original").get("clockInAt").asString()).isEqualTo("2026-10-01T01:00:00Z");
        assertThat(entry.get("after").isNull()).as("提出申請沒有修改出勤").isTrue();
        assertThat(entry.get("occurredAt").asString()).as("時間來自伺服器 Clock").isEqualTo("2026-10-01T02:00:00Z");
    }
}
