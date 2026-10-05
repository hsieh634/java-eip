package com.renewflow.eip.organization;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import com.renewflow.eip.timesheet.TimesheetTestBase;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T05 前置測試共用基礎：沿用 alice（EMPLOYEE）、bob（MANAGER）、carol／dave（ADMIN）。
 * 部門、員工與組織歷程都只存在測試庫，每個測試前清空。
 */
abstract class OrganizationTestBase extends TimesheetTestBase {

    protected static final String DEPARTMENTS = "/api/admin/departments";
    protected static final String EMPLOYEES = "/api/admin/employees";

    /** 建立額外的員工（直接寫入，測試資料）。 */
    protected Employee extra(String employeeNo, String username, Role role) {
        return employeeRepository.save(new Employee(employeeNo, username, username + " 測試", passwordEncoder.encode(PASSWORD), role));
    }

    protected static String deptBody(String code, String name) {
        return json("code", code == null ? "null" : str(code), "name", name == null ? "null" : str(name));
    }

    protected static String deptUpdate(String name, boolean active, long expectedVersion, String reason) {
        return json("name", str(name), "active", String.valueOf(active), "expectedVersion", String.valueOf(expectedVersion),
                "reason", reason == null ? "null" : str(reason));
    }

    /** 以 carol 建立部門並回傳 id。 */
    protected long createDept(String code, String name) {
        Reply reply = loginAs("carol").post(DEPARTMENTS, deptBody(code, name));
        assertThat(reply.status()).as("create department %s", reply.body()).isEqualTo(201);
        return reply.json().get("id").asLong();
    }

    protected long deptVersion(long id) {
        return jdbc.queryForObject("select version from department where id = ?", Long.class, id);
    }

    protected boolean deptActive(long id) {
        return jdbc.queryForObject("select active from department where id = ?", Boolean.class, id);
    }

    /** 以 carol 停用／啟用部門（讀取目前版本）。 */
    protected Reply setDeptActive(TestBrowser admin, long id, boolean active) {
        String name = jdbc.queryForObject("select name from department where id = ?", String.class, id);
        return admin.put(DEPARTMENTS + "/" + id, deptUpdate(name, active, deptVersion(id), "測試切換"));
    }

    protected long empVersion(long id) {
        return jdbc.queryForObject("select version from employee where id = ?", Long.class, id);
    }

    protected Long managerOf(long id) {
        return jdbc.queryForObject("select manager_id from employee where id = ?", Long.class, id);
    }

    protected Long departmentOf(long id) {
        return jdbc.queryForObject("select department_id from employee where id = ?", Long.class, id);
    }

    protected int orgHistoryRows() {
        return jdbc.queryForObject("select count(*) from organization_history", Integer.class);
    }

    /** 員工修改表單：以資料庫目前的值為起點（就像畫面剛載入），再覆寫要改的欄位。 */
    protected Form form(long id) {
        Map<String, Object> row = jdbc.queryForMap("select display_name, role, enabled, department_id, manager_id, version from employee where id = ?", id);
        return new Form(id, (String) row.get("display_name"), (String) row.get("role"), (Boolean) row.get("enabled"),
                (Long) row.get("department_id"), (Long) row.get("manager_id"), (Long) row.get("version"), "測試調整");
    }

    protected record Form(long id, String displayName, String role, boolean enabled, Long departmentId, Long managerId,
                          long expectedVersion, String reason) {
        Form displayName(String v) { return new Form(id, v, role, enabled, departmentId, managerId, expectedVersion, reason); }
        Form role(String v) { return new Form(id, displayName, v, enabled, departmentId, managerId, expectedVersion, reason); }
        Form enabled(boolean v) { return new Form(id, displayName, role, v, departmentId, managerId, expectedVersion, reason); }
        Form department(Long v) { return new Form(id, displayName, role, enabled, v, managerId, expectedVersion, reason); }
        Form manager(Long v) { return new Form(id, displayName, role, enabled, departmentId, v, expectedVersion, reason); }
        Form version(long v) { return new Form(id, displayName, role, enabled, departmentId, managerId, v, reason); }
        Form reason(String v) { return new Form(id, displayName, role, enabled, departmentId, managerId, expectedVersion, v); }

        String body() {
            return json("displayName", str(displayName), "role", str(role), "enabled", String.valueOf(enabled),
                    "departmentId", String.valueOf(departmentId), "managerId", String.valueOf(managerId),
                    "expectedVersion", String.valueOf(expectedVersion), "reason", reason == null ? "null" : str(reason));
        }

        Reply submit(TestBrowser admin) {
            return admin.put(EMPLOYEES + "/" + id, body());
        }
    }

    protected static String createBody(String employeeNo, String username, String displayName, String role,
                                       Long departmentId, Long managerId, String initialPassword) {
        return json("employeeNo", str(employeeNo), "username", str(username), "displayName", str(displayName), "role", str(role),
                "departmentId", String.valueOf(departmentId), "managerId", String.valueOf(managerId),
                "initialPassword", initialPassword == null ? "null" : str(initialPassword), "reason", "null");
    }

    /** 以 carol 把某人設為某主管的部屬（讀取目前狀態後提交）。 */
    protected void assignManager(long employeeId, Long managerId) {
        Reply reply = form(employeeId).manager(managerId).submit(loginAs("carol"));
        assertThat(reply.status()).as("assign manager %s", reply.body()).isEqualTo(200);
    }
}
