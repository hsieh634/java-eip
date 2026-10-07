package com.renewflow.eip.attendance;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import com.renewflow.eip.identity.Employee;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T03 出勤 API：正常流程、規則衝突、身份與資料隔離、時區邊界、日期範圍。編號對應 specification.md。 */
class AttendanceIntegrationTest extends AttendanceTestBase {

    private static final String CLOCK_IN = "/api/attendance/clock-in";
    private static final String CLOCK_OUT = "/api/attendance/clock-out";
    private static final String TODAY = "/api/attendance/me/today";

    @Autowired
    AttendanceService attendanceService;

    private void seed(Employee employee, String workDate, String in, String out) {
        jdbc.update("insert into attendance (employee_id, work_date, clock_in_at, clock_out_at) values (?, ?, ?, ?)",
                employee.getId(), LocalDate.parse(workDate), OffsetDateTime.ofInstant(Instant.parse(in), ZoneOffset.UTC),
                out == null ? null : OffsetDateTime.ofInstant(Instant.parse(out), ZoneOffset.UTC));
    }

    // ---------------- 正常流程 ----------------

    @Test
    @DisplayName("A-01 今日沒有紀錄：200 NOT_STARTED（不是 404）")
    void todayWithoutRecordIsNotStarted() {
        Reply reply = loginAs("alice").get(TODAY);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("status").asString()).isEqualTo("NOT_STARTED");
        assertThat(reply.json().get("workDate").asString()).isEqualTo("2026-10-01");
        assertThat(reply.json().get("timeZone").asString()).isEqualTo("Asia/Taipei");
        assertThat(reply.json().get("clockInAt").isNull()).isTrue();
        assertThat(reply.json().get("clockOutAt").isNull()).isTrue();
        assertThat(reply.json().get("unfinishedBefore").isNull()).isTrue();
    }

    @Test
    @DisplayName("A-02/A-03 上班、下班：時間由伺服器時鐘決定，ISO 8601 輸出，今日狀態隨之改變，歷史可查")
    void clockInThenClockOut() {
        TestBrowser aliceB = loginAs("alice");

        Reply in = aliceB.post(CLOCK_IN, null);
        assertThat(in.status()).isEqualTo(200);
        assertThat(in.json().get("status").asString()).isEqualTo("WORKING");
        assertThat(in.json().get("workDate").asString()).isEqualTo("2026-10-01");
        assertThat(in.json().get("clockInAt").asString()).isEqualTo("2026-10-01T01:00:00Z");
        assertThat(in.json().get("clockOutAt").isNull()).isTrue();
        assertThat(aliceB.get(TODAY).json().get("status").asString()).isEqualTo("WORKING");

        clock.set(Instant.parse("2026-10-01T10:30:00Z")); // 台北 18:30
        Reply out = aliceB.post(CLOCK_OUT, null);
        assertThat(out.status()).isEqualTo(200);
        assertThat(out.json().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(out.json().get("clockInAt").asString()).isEqualTo("2026-10-01T01:00:00Z");
        assertThat(out.json().get("clockOutAt").asString()).isEqualTo("2026-10-01T10:30:00Z");

        Reply today = aliceB.get(TODAY);
        assertThat(today.json().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(today.json().get("clockOutAt").asString()).isEqualTo("2026-10-01T10:30:00Z");

        Reply history = aliceB.get("/api/attendance/me");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.json().get("records").size()).isEqualTo(1);
        assertThat(history.json().get("records").get(0).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(attendanceRowsOf(alice)).isEqualTo(1);
    }

    // ---------------- 規則衝突 ----------------

    @Test
    @DisplayName("A-04 沒上班就下班 → 409 CLOCK_IN_REQUIRED，資料表無新增")
    void clockOutWithoutClockIn() {
        Reply reply = loginAs("alice").post(CLOCK_OUT, null);

        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("CLOCK_IN_REQUIRED");
        assertThat(attendanceRows()).isZero();
    }

    @Test
    @DisplayName("A-05 重複上班 → 409 ALREADY_CLOCKED_IN，原上班時間不變、仍只有一筆")
    void duplicateClockIn() {
        TestBrowser aliceB = loginAs("alice");
        assertThat(aliceB.post(CLOCK_IN, null).status()).isEqualTo(200);

        clock.set(Instant.parse("2026-10-01T02:00:00Z"));
        Reply second = aliceB.post(CLOCK_IN, null);

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo("ALREADY_CLOCKED_IN");
        assertThat(attendanceRows()).isEqualTo(1);
        assertThat(aliceB.get(TODAY).json().get("clockInAt").asString()).isEqualTo("2026-10-01T01:00:00Z");
    }

    @Test
    @DisplayName("A-06 重複下班 → 409 ALREADY_CLOCKED_OUT，第一次下班時間不被覆蓋")
    void duplicateClockOut() {
        TestBrowser aliceB = loginAs("alice");
        aliceB.post(CLOCK_IN, null);
        clock.set(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(aliceB.post(CLOCK_OUT, null).status()).isEqualTo(200);

        clock.set(Instant.parse("2026-10-01T11:00:00Z"));
        Reply second = aliceB.post(CLOCK_OUT, null);

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo("ALREADY_CLOCKED_OUT");
        assertThat(aliceB.get(TODAY).json().get("clockOutAt").asString()).isEqualTo("2026-10-01T10:00:00Z");
    }

    @Test
    @DisplayName("A-07／M01 已下班後再上班 → 200，保留前一組")
    void clockInAfterCompleted() {
        TestBrowser browser = loginAs("alice");
        assertThat(browser.post(CLOCK_IN, null).status()).isEqualTo(200);
        clock.set(Instant.parse("2026-10-01T04:00:00Z"));
        assertThat(browser.post(CLOCK_OUT, null).status()).isEqualTo(200);
        clock.set(Instant.parse("2026-10-01T06:00:00Z"));
        assertThat(browser.post(CLOCK_IN, null).status()).isEqualTo(200);
        assertThat(attendanceRows()).isEqualTo(2);
        assertThat(browser.get(TODAY).json().get("status").asString()).isEqualTo("WORKING");
    }

    // ---------------- 身份與資料隔離 ----------------

    @Test
    @DisplayName("A-08 本文夾帶 employeeId／clockInAt／workDate 一律忽略：寫入的是 Session 身份與伺服器時間")
    void clientSuppliedIdentityAndTimeAreIgnored() {
        TestBrowser aliceB = loginAs("alice");
        String forged = "{\"employeeId\":" + bob.getId() + ",\"clockInAt\":\"2020-01-01T00:00:00Z\",\"workDate\":\"2020-01-01\"}";

        Reply reply = aliceB.post(CLOCK_IN, forged);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("workDate").asString()).isEqualTo("2026-10-01");
        assertThat(reply.json().get("clockInAt").asString()).isEqualTo("2026-10-01T01:00:00Z");
        assertThat(attendanceRowsOf(alice)).isEqualTo(1);
        assertThat(attendanceRowsOf(bob)).isZero();
    }

    @Test
    @DisplayName("A-09 兩位員工資料隔離：互不影響；查詢字串的 employeeId 被忽略")
    void employeesAreIsolated() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser bobBrowser = loginAs("bob");
        aliceB.post(CLOCK_IN, null);
        clock.set(Instant.parse("2026-10-01T02:00:00Z"));
        bobBrowser.post(CLOCK_IN, null);

        // 各自的今日狀態：時間不同，互不影響
        assertThat(aliceB.get(TODAY).json().get("clockInAt").asString()).isEqualTo("2026-10-01T01:00:00Z");
        assertThat(bobBrowser.get(TODAY).json().get("clockInAt").asString()).isEqualTo("2026-10-01T02:00:00Z");

        // 試圖用查詢字串查別人：被忽略，只看到自己的一筆
        Reply spoof = aliceB.get("/api/attendance/me?employeeId=" + bob.getId());
        assertThat(spoof.status()).isEqualTo(200);
        assertThat(spoof.json().get("records").size()).isEqualTo(1);
        assertThat(spoof.json().get("records").get(0).get("clockInAt").asString()).isEqualTo("2026-10-01T01:00:00Z");

        // alice 下班不會影響 bob
        clock.set(Instant.parse("2026-10-01T10:00:00Z"));
        aliceB.post(CLOCK_OUT, null);
        assertThat(bobBrowser.get(TODAY).json().get("status").asString()).isEqualTo("WORKING");
    }

    @Test
    @DisplayName("A-10 未登入 → 401（讀與寫皆然），沒有資料變動")
    void unauthenticated() {
        TestBrowser anonymous = new TestBrowser(port);
        assertThat(anonymous.get(TODAY).status()).isEqualTo(401);
        assertThat(anonymous.get("/api/attendance/me").status()).isEqualTo(401);
        Reply write = anonymous.post(CLOCK_IN, null); // 已有 CSRF token（前面的 GET 取得），所以是被認證擋下
        assertThat(write.status()).isEqualTo(401);
        assertThat(attendanceRows()).isZero();
    }

    @Test
    @DisplayName("A-11 已登入但缺 CSRF 的上班／下班 → 403 CSRF_REJECTED，沒有資料變動")
    void missingCsrf() {
        TestBrowser aliceB = loginAs("alice");

        Reply in = aliceB.postWithCsrf(CLOCK_IN, null, null);
        assertThat(in.status()).isEqualTo(403);
        assertThat(in.code()).isEqualTo("CSRF_REJECTED");
        assertThat(attendanceRows()).isZero();

        aliceB.post(CLOCK_IN, null);
        Reply out = aliceB.postWithCsrf(CLOCK_OUT, null, null);
        assertThat(out.status()).isEqualTo(403);
        assertThat(aliceB.get(TODAY).json().get("status").asString()).isEqualTo("WORKING");
    }

    // ---------------- 時區邊界 ----------------

    @ParameterizedTest(name = "[{index}] UTC {0} → 台北業務日期 {1}")
    @CsvSource({
            "2026-10-01T15:59:59Z, 2026-10-01", // 台北 23:59:59，仍是 10/1
            "2026-10-01T16:00:00Z, 2026-10-02", // 台北 00:00:00，已是 10/2（UTC 日期仍是 10/1）
            "2026-10-01T16:30:00Z, 2026-10-02",
            "2026-10-01T00:00:00Z, 2026-10-01", // 台北 08:00
            "2026-12-31T16:00:00Z, 2027-01-01"  // 跨年
    })
    @DisplayName("A-17 業務日期以 Asia/Taipei 換算，而不是 UTC 日期")
    void workDateUsesTaipeiZone(String utcInstant, String expectedWorkDate) {
        clock.set(Instant.parse(utcInstant));

        Reply in = loginAs("alice").post(CLOCK_IN, null);

        assertThat(in.status()).isEqualTo(200);
        assertThat(in.json().get("workDate").asString()).isEqualTo(expectedWorkDate);
        assertThat(in.json().get("clockInAt").asString()).isEqualTo(utcInstant);
        String stored = jdbc.queryForObject("select work_date::text from attendance where employee_id = ?", String.class, alice.getId());
        assertThat(stored).isEqualTo(expectedWorkDate);
    }

    // ---------------- 昨日未完成、跨日 ----------------

    @Test
    @DisplayName("A-18 昨日未下班：今日查詢為 NOT_STARTED，並帶 unfinishedBefore")
    void yesterdayUnfinishedIsReportedOnToday() {
        seed(alice, "2026-09-30", "2026-09-30T01:00:00Z", null);

        Reply today = loginAs("alice").get(TODAY);

        assertThat(today.json().get("status").asString()).isEqualTo("NOT_STARTED");
        assertThat(today.json().get("unfinishedBefore").asString()).isEqualTo("2026-09-30");
    }

    @Test
    @DisplayName("A-19 昨日未下班、今日沒上班就下班 → 409 CLOCK_IN_REQUIRED，不替昨日補登")
    void clockOutDoesNotCompleteYesterday() {
        seed(alice, "2026-09-30", "2026-09-30T01:00:00Z", null);

        Reply out = loginAs("alice").post(CLOCK_OUT, null);

        assertThat(out.status()).isEqualTo(409);
        assertThat(out.code()).isEqualTo("CLOCK_IN_REQUIRED");
        String yesterdayOut = jdbc.queryForObject(
                "select clock_out_at::text from attendance where employee_id = ? and work_date = date '2026-09-30'",
                String.class, alice.getId());
        assertThat(yesterdayOut).isNull();
    }

    @Test
    @DisplayName("A-20 昨日未下班、今日仍可上班：新增今日紀錄，昨日維持未完成（INCOMPLETE）")
    void todayRecordCanBeCreatedWhileYesterdayIsUnfinished() {
        seed(alice, "2026-09-30", "2026-09-30T01:00:00Z", null);
        TestBrowser aliceB = loginAs("alice");

        assertThat(aliceB.post(CLOCK_IN, null).status()).isEqualTo(200);

        Reply history = aliceB.get("/api/attendance/me?from=2026-09-30&to=2026-10-01");
        assertThat(history.json().get("records").size()).isEqualTo(2);
        assertThat(history.json().get("records").get(0).get("workDate").asString()).isEqualTo("2026-10-01");
        assertThat(history.json().get("records").get(0).get("status").asString()).isEqualTo("WORKING");
        assertThat(history.json().get("records").get(1).get("workDate").asString()).isEqualTo("2026-09-30");
        assertThat(history.json().get("records").get(1).get("status").asString()).isEqualTo("INCOMPLETE");
        // 提醒仍在
        assertThat(aliceB.get(TODAY).json().get("unfinishedBefore").asString()).isEqualTo("2026-09-30");
    }

    @Test
    @DisplayName("A-21 跨日：23:50 上班、隔日 00:10 下班 → 409 CLOCK_IN_REQUIRED（不支援跨日班），前一天紀錄不變")
    void overnightShiftIsNotSupported() {
        TestBrowser aliceB = loginAs("alice");
        clock.set(Instant.parse("2026-10-01T15:50:00Z")); // 台北 10/1 23:50
        assertThat(aliceB.post(CLOCK_IN, null).status()).isEqualTo(200);

        clock.set(Instant.parse("2026-10-01T16:10:00Z")); // 台北 10/2 00:10
        Reply out = aliceB.post(CLOCK_OUT, null);

        assertThat(out.status()).isEqualTo(409);
        assertThat(out.code()).isEqualTo("CLOCK_IN_REQUIRED");
        Reply today = aliceB.get(TODAY);
        assertThat(today.json().get("workDate").asString()).isEqualTo("2026-10-02");
        assertThat(today.json().get("status").asString()).isEqualTo("NOT_STARTED");
        assertThat(today.json().get("unfinishedBefore").asString()).isEqualTo("2026-10-01");
    }

    // ---------------- 非業務衝突不可被偽裝成 409 ----------------

    @Test
    @DisplayName("A-29 伺服器時鐘倒退導致下班早於上班 → 5xx（系統錯誤），不是 409；紀錄不被改動")
    void clockGoingBackwardsIsServerError() {
        TestBrowser aliceB = loginAs("alice");
        clock.set(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(aliceB.post(CLOCK_IN, null).status()).isEqualTo(200);

        clock.set(Instant.parse("2026-10-01T09:00:00Z")); // 同一個台北日期、但比上班時間早
        Reply out = aliceB.post(CLOCK_OUT, null);

        assertThat(out.status()).isEqualTo(500);
        assertThat(out.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(aliceB.get(TODAY).json().get("clockOutAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("A-29 員工不存在時明確回報 EMPLOYEE_NOT_FOUND，不偽裝成重複上班")
    void foreignKeyViolationIsNotReportedAsDuplicate() {
        assertThatThrownBy(() -> attendanceService.clockIn(-1L, null))
                .isInstanceOf(AttendanceNotFoundException.class)
                .isNotInstanceOf(AttendanceRuleException.class);
        assertThat(attendanceRows()).isZero();
    }

    // ---------------- 日期範圍 ----------------

    @Test
    @DisplayName("A-22 日期範圍驗證：格式錯誤、只給一個、起日大於迄日、超過 31 日 → 400")
    void invalidRanges() {
        TestBrowser aliceB = loginAs("alice");

        Reply badFormat = aliceB.get("/api/attendance/me?from=2026-13-40&to=2026-10-01");
        assertThat(badFormat.status()).isEqualTo(400);
        assertThat(badFormat.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(badFormat.body()).doesNotContain("2026-13-40");

        assertThat(aliceB.get("/api/attendance/me?from=abc&to=def").code()).isEqualTo("VALIDATION_FAILED");

        Reply onlyFrom = aliceB.get("/api/attendance/me?from=2026-10-01");
        assertThat(onlyFrom.status()).isEqualTo(400);
        assertThat(onlyFrom.code()).isEqualTo("INVALID_DATE_RANGE");
        assertThat(aliceB.get("/api/attendance/me?to=2026-10-01").code()).isEqualTo("INVALID_DATE_RANGE");

        Reply reversed = aliceB.get("/api/attendance/me?from=2026-10-10&to=2026-10-09");
        assertThat(reversed.status()).isEqualTo(400);
        assertThat(reversed.code()).isEqualTo("INVALID_DATE_RANGE");

        // 10/1 ~ 11/1 共 32 個日曆日
        Reply tooLong = aliceB.get("/api/attendance/me?from=2026-10-01&to=2026-11-01");
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.code()).isEqualTo("INVALID_DATE_RANGE");
    }

    @Test
    @DisplayName("A-23 恰 31 日可查；預設本月；含邊界；新到舊；只含本人")
    void validRangesAndOrdering() {
        clock.set(Instant.parse("2026-10-15T01:00:00Z"));
        seed(alice, "2026-09-30", "2026-09-30T01:00:00Z", "2026-09-30T10:00:00Z");
        seed(alice, "2026-10-01", "2026-10-01T01:00:00Z", "2026-10-01T10:00:00Z");
        seed(alice, "2026-10-15", "2026-10-15T01:00:00Z", null);
        seed(alice, "2026-10-31", "2026-10-31T01:00:00Z", "2026-10-31T10:00:00Z");
        seed(alice, "2026-11-01", "2026-11-01T01:00:00Z", "2026-11-01T10:00:00Z");
        seed(bob, "2026-10-10", "2026-10-10T01:00:00Z", "2026-10-10T10:00:00Z"); // 別人的資料
        TestBrowser aliceB = loginAs("alice");

        // 預設本月：10/1 ~ 10/31，新到舊，不含 9/30 與 11/1
        Reply month = aliceB.get("/api/attendance/me");
        assertThat(month.status()).isEqualTo(200);
        assertThat(month.json().get("from").asString()).isEqualTo("2026-10-01");
        assertThat(month.json().get("to").asString()).isEqualTo("2026-10-31");
        assertThat(month.json().get("records").size()).isEqualTo(3);
        assertThat(month.json().get("records").get(0).get("workDate").asString()).isEqualTo("2026-10-31");
        assertThat(month.json().get("records").get(1).get("workDate").asString()).isEqualTo("2026-10-15");
        assertThat(month.json().get("records").get(1).get("status").asString()).isEqualTo("WORKING"); // 今日、未下班
        assertThat(month.json().get("records").get(2).get("workDate").asString()).isEqualTo("2026-10-01");

        // 自訂範圍含起訖日；恰 31 日（10/1 ~ 10/31）合法
        Reply bounded = aliceB.get("/api/attendance/me?from=2026-10-15&to=2026-10-31");
        assertThat(bounded.json().get("records").size()).isEqualTo(2);
        assertThat(aliceB.get("/api/attendance/me?from=2026-10-01&to=2026-10-31").status()).isEqualTo(200);

        // 無資料的範圍：200 與空陣列
        Reply empty = aliceB.get("/api/attendance/me?from=2026-08-01&to=2026-08-31");
        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.json().get("records").size()).isZero();
    }

    @Test
    @DisplayName("過去日期未下班在歷史中標為 INCOMPLETE，今日未下班為 WORKING")
    void incompleteStatusInHistory() {
        seed(alice, "2026-09-29", "2026-09-29T01:00:00Z", null);

        Reply history = loginAs("alice").get("/api/attendance/me?from=2026-09-29&to=2026-09-30");

        assertThat(history.json().get("records").get(0).get("status").asString()).isEqualTo("INCOMPLETE");
    }
}
