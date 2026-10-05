package com.renewflow.eip.organization;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * 組織歷程寫入失敗時，業務修改必須一起回滾（同一個交易）。
 * 做法：把 OrganizationAuditLog 換成會丟例外的 Spy；其餘（Tomcat、PostgreSQL、交易邊界、組織鎖）都是真的。
 */
class OrganizationAuditRollbackIntegrationTest extends OrganizationTestBase {

    @MockitoSpyBean
    OrganizationAuditLog auditLog;

    private void breakAuditLog() {
        doThrow(new IllegalStateException("audit write failed (injected)")).when(auditLog).employee(any(), any(), any(), any(), any(), any(), any());
        doThrow(new IllegalStateException("audit write failed (injected)")).when(auditLog).department(any(), any(), any(), any(), any(), any(), any());
    }

    private void repairAuditLog() {
        doCallRealMethod().when(auditLog).employee(any(), any(), any(), any(), any(), any(), any());
        doCallRealMethod().when(auditLog).department(any(), any(), any(), any(), any(), any(), any());
    }

    private List<Map<String, Object>> snapshot() {
        return jdbc.queryForList("select id, display_name, role, enabled, department_id, manager_id, version from employee order by id");
    }

    @Test
    @DisplayName("OA-01 修改員工時歷程寫入失敗 → 500；角色、主管、版本都不變；修復後同一表單可以成功")
    void employeeUpdateRollsBack() {
        TestBrowser carolB = loginAs("carol");
        var before = snapshot();
        breakAuditLog();

        Reply failed = form(alice.getId()).role("MANAGER").manager(bob.getId()).submit(carolB);

        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.body()).doesNotContain("audit").doesNotContain("injected");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(orgHistoryRows()).isZero();

        repairAuditLog();
        assertThat(form(alice.getId()).role("MANAGER").manager(bob.getId()).submit(carolB).status()).isEqualTo(200);
        assertThat(orgHistoryRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("OA-02 建立員工／部門時歷程寫入失敗 → 500；沒有留下員工或部門；修復後同一員工編號與代碼可以建立")
    void createRollsBack() {
        TestBrowser carolB = loginAs("carol");
        int employees = jdbc.queryForObject("select count(*) from employee", Integer.class);
        breakAuditLog();

        assertThat(carolB.post(EMPLOYEES, createBody("T0300", "rollback_user", "回滾", "EMPLOYEE", null, null, "test-only-Passw0rd")).status())
                .isEqualTo(500);
        assertThat(carolB.post(DEPARTMENTS, deptBody("ROLLBACK", "回滾")).status()).isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from employee", Integer.class)).isEqualTo(employees);
        assertThat(jdbc.queryForObject("select count(*) from department", Integer.class)).isZero();

        repairAuditLog();
        assertThat(carolB.post(EMPLOYEES, createBody("T0300", "rollback_user", "回滾", "EMPLOYEE", null, null, "test-only-Passw0rd")).status())
                .isEqualTo(201);
        assertThat(carolB.post(DEPARTMENTS, deptBody("ROLLBACK", "回滾")).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("OA-03 停用部門時歷程寫入失敗 → 500；部門仍啟用、版本不變")
    void departmentUpdateRollsBack() {
        long dept = createDept("KEEP", "保留");
        TestBrowser carolB = loginAs("carol");
        breakAuditLog();

        assertThat(setDeptActive(carolB, dept, false).status()).isEqualTo(500);
        assertThat(deptActive(dept)).isTrue();
        assertThat(deptVersion(dept)).isZero();
    }
}
