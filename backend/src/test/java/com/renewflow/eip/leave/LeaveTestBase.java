package com.renewflow.eip.leave;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import com.renewflow.eip.timesheet.TimesheetTestBase;
import org.junit.jupiter.api.BeforeEach;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T05 測試共用基礎：alice（EMPLOYEE，直屬主管 bob）、bob（MANAGER）、erin（MANAGER，沒有部屬）、
 * carol／dave（ADMIN，沒有主管）。時鐘為台北 2026-10-01 09:00。全部是測試庫的測試資料。
 */
abstract class LeaveTestBase extends TimesheetTestBase {

    protected static final String ME = "/api/leave/me";
    protected static final String APPROVALS = "/api/leave/approvals";
    protected static final String ADMIN_REQUESTS = "/api/admin/leave/requests";
    protected static final String SETTINGS = "/api/admin/leave/approver-settings";
    protected static final String DAY = "2026-10-05";

    protected Employee erin;

    @BeforeEach
    protected void createLeaveFixtures() {
        erin = employeeRepository.save(new Employee("T0005", "erin", "艾琳", passwordEncoder.encode(PASSWORD), Role.MANAGER));
        setManager(alice.getId(), bob.getId());
    }

    protected void setManager(long employeeId, Long managerId) {
        jdbc.update("update employee set manager_id = ? where id = ?", managerId, employeeId);
    }

    protected static String leaveBody(UUID requestId, String type, String date, String start, String end, String reason) {
        return json("requestId", str(requestId.toString()), "leaveType", str(type), "workDate", str(date),
                "startTime", str(start), "endTime", str(end), "reason", reason == null ? "null" : str(reason));
    }

    protected Reply submit(TestBrowser b, String date, String start, String end) {
        return b.post(ME, leaveBody(UUID.randomUUID(), "PERSONAL", date, start, end, "家中有事"));
    }

    protected long submitOk(TestBrowser b, String date, String start, String end) {
        Reply r = submit(b, date, start, end);
        assertThat(r.status()).as("submit %s", r.body()).isEqualTo(201);
        return r.json().get("request").get("id").asLong();
    }

    protected static String versionBody(long version) {
        return json("expectedVersion", String.valueOf(version));
    }

    protected static String versionReason(long version, String reason) {
        return json("expectedVersion", String.valueOf(version), "reason", reason == null ? "null" : str(reason));
    }

    protected Reply approve(TestBrowser b, long id) {
        return b.post(APPROVALS + "/" + id + "/approve", versionBody(version(id)));
    }

    protected Reply reject(TestBrowser b, long id, String reason) {
        return b.post(APPROVALS + "/" + id + "/reject", versionReason(version(id), reason));
    }

    protected Reply withdraw(TestBrowser b, long id) {
        return b.post(ME + "/" + id + "/withdraw", versionReason(version(id), null));
    }

    protected Reply reassign(TestBrowser b, long id, long approverId, String reason) {
        return b.post(ADMIN_REQUESTS + "/" + id + "/reassign", json("approverId", String.valueOf(approverId),
                "expectedVersion", String.valueOf(version(id)), "reason", reason == null ? "null" : str(reason)));
    }

    protected Reply putSetting(TestBrowser admin, long employeeId, Long approverId, String reason) {
        return admin.put(SETTINGS + "/" + employeeId, json("approverId", String.valueOf(approverId),
                "expectedVersion", String.valueOf(settingVersion(employeeId)), "reason", reason == null ? "null" : str(reason)));
    }

    protected long settingVersion(long employeeId) {
        return jdbc.queryForList("select version from leave_approver_setting where employee_id = ?", Long.class, employeeId)
                .stream().findFirst().orElse(0L);
    }

    protected long version(long id) {
        return jdbc.queryForObject("select version from leave_request where id = ?", Long.class, id);
    }

    protected String status(long id) {
        return jdbc.queryForObject("select status from leave_request where id = ?", String.class, id);
    }

    protected Long approverOf(long id) {
        return jdbc.queryForObject("select approver_id from leave_request where id = ?", Long.class, id);
    }

    protected int leaveRows() {
        return jdbc.queryForObject("select count(*) from leave_request", Integer.class);
    }

    protected int leaveHistoryRows() {
        return jdbc.queryForObject("select count(*) from leave_history", Integer.class);
    }

    /** 申請列＋歷程的完整快照（用來證明「被拒絕的操作沒有改動任何資料」）。 */
    protected String snapshot() {
        return jdbc.queryForList("select row_to_json(r)::text from leave_request r order by id", String.class)
                + "|" + jdbc.queryForList("select row_to_json(h)::text from leave_history h order by id", String.class)
                + "|" + jdbc.queryForList("select row_to_json(s)::text from leave_approver_setting s order by employee_id", String.class);
    }
}
