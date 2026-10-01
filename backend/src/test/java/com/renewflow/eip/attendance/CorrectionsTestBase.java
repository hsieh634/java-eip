package com.renewflow.eip.attendance;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.BeforeEach;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T03.1 測試共用基礎：在 alice（員工）、bob（主管）之外，再建立兩位管理員 carol、dave（ADMIN），
 * 提供提出申請、審核與資料查詢的小工具。時間一律由可控制的 Clock 決定。
 */
abstract class CorrectionsTestBase extends AttendanceTestBase {

    static final String CLOCK_IN = "/api/attendance/clock-in";
    static final String CLOCK_OUT = "/api/attendance/clock-out";
    static final String TODAY = "/api/attendance/me/today";
    static final String REQUESTS = "/api/attendance/requests";
    static final String ADMIN = "/api/admin/attendance";

    Employee carol;
    Employee dave;

    @BeforeEach
    void createAdmins() {
        carol = employeeRepository.save(new Employee("T0003", "carol", "卡蘿", passwordEncoder.encode(PASSWORD), Role.ADMIN));
        dave = employeeRepository.save(new Employee("T0004", "dave", "戴夫", passwordEncoder.encode(PASSWORD), Role.ADMIN));
    }

    static Instant utc(String instant) {
        return Instant.parse(instant);
    }

    // ---------- 出勤資料 ----------

    /** 直接寫入一筆有效紀錄（不經 API），用來準備「過去日期」的情境。 */
    void seedRecord(Employee employee, String workDate, String in, String out) {
        jdbc.update("insert into attendance (employee_id, work_date, clock_in_at, clock_out_at) values (?, ?, ?, ?)",
                employee.getId(), LocalDate.parse(workDate), OffsetDateTime.ofInstant(Instant.parse(in), ZoneOffset.UTC),
                out == null ? null : OffsetDateTime.ofInstant(Instant.parse(out), ZoneOffset.UTC));
    }

    int activeRows(Employee employee) {
        return jdbc.queryForObject("select count(*) from attendance where employee_id = ? and voided_at is null", Integer.class, employee.getId());
    }

    int voidedRows(Employee employee) {
        return jdbc.queryForObject("select count(*) from attendance where employee_id = ? and voided_at is not null", Integer.class, employee.getId());
    }

    int historyRows() {
        return jdbc.queryForObject("select count(*) from attendance_history", Integer.class);
    }

    int requestRows() {
        return jdbc.queryForObject("select count(*) from attendance_request", Integer.class);
    }

    String requestStatus(long requestId) {
        return jdbc.queryForObject("select status from attendance_request where id = ?", String.class, requestId);
    }

    /** 某日有效紀錄的欄位（轉成字串方便斷言；NULL 回傳 null）。 */
    String activeColumn(Employee employee, String workDate, String column) {
        // timestamptz 轉字串會依連線時區顯示（JVM 是台北），統一換成 UTC 再比較
        String expression = column.endsWith("_at") ? "(" + column + " at time zone 'UTC')" : column;
        var rows = jdbc.queryForList("select " + expression + "::text as v from attendance where employee_id = ? and work_date = ?::date and voided_at is null",
                String.class, employee.getId(), workDate);
        return rows.isEmpty() ? "<none>" : rows.get(0);
    }

    long activeVersion(Employee employee, String workDate) {
        return jdbc.queryForObject("select version from attendance where employee_id = ? and work_date = ?::date and voided_at is null",
                Long.class, employee.getId(), workDate);
    }

    long activeId(Employee employee, String workDate) {
        return jdbc.queryForObject("select id from attendance where employee_id = ? and work_date = ?::date and voided_at is null",
                Long.class, employee.getId(), workDate);
    }

    // ---------- 申請 ----------

    static String json(String... pairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(pairs[i]).append("\":").append(pairs[i + 1]);
        }
        return sb.append('}').toString();
    }

    static String str(String value) {
        return "\"" + value + "\"";
    }

    Reply submitReset(TestBrowser browser, String reason) {
        return browser.post(REQUESTS, json("type", str("RESET_TODAY"), "reason", str(reason)));
    }

    Reply submitCorrection(TestBrowser browser, String workDate, String in, String out, String reason) {
        return browser.post(REQUESTS, json("type", str("CORRECT_TIME"), "workDate", str(workDate),
                "clockInTime", in == null ? "null" : str(in), "clockOutTime", out == null ? "null" : str(out), "reason", str(reason)));
    }

    long idOf(Reply created) {
        assertThat(created.status()).as("建立申請 %s", created.body()).isEqualTo(201);
        return created.json().get("id").asLong();
    }

    Reply approve(TestBrowser admin, long requestId) {
        return admin.post(ADMIN + "/requests/" + requestId + "/approve", "{}");
    }

    Reply reject(TestBrowser admin, long requestId, String reason) {
        return admin.post(ADMIN + "/requests/" + requestId + "/reject", json("reason", str(reason)));
    }

    /** 在指定時間以員工身份打卡（先把時鐘調到該時間）。 */
    void punch(TestBrowser browser, String path, String instant) {
        clock.set(utc(instant));
        assertThat(browser.post(path, null).status()).as("punch %s at %s", path, instant).isEqualTo(200);
    }
}
