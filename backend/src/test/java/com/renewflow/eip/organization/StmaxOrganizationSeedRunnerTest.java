package com.renewflow.eip.organization;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.EmployeeRepository;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.organization.StmaxOrganizationSeedRunner.Outcome;
import com.renewflow.eip.organization.StmaxOrganizationSeedRunner.Result;
import com.renewflow.eip.support.TestData;
import com.renewflow.eip.support.TestFlywayConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STMAX 示範組織初始化：只在測試庫驗證。六個帳號在這裡直接建立（測試資料，雜湊是佔位字串、不是真密碼）。
 * 直接建構 runner 並傳入開關，驗證：關閉、非 dev 不存在、首次套用、重跑冪等、保留不同的既有值、
 * 管理員調整過的人不動、部門代碼衝突與帳號不符時整批停止。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestFlywayConfig.class)
class StmaxOrganizationSeedRunnerTest {

    @Autowired
    EmployeeRepository employeeRepository;
    @Autowired
    EmployeeAdminRepository employeeAdminRepository;
    @Autowired
    DepartmentRepository departmentRepository;
    @Autowired
    OrganizationHistoryRepository historyRepository;
    @Autowired
    OrganizationAuditLog auditLog;
    @Autowired
    OrganizationLock lock;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    Clock clock;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ApplicationContext context;

    private static final List<Object[]> ACCOUNTS = List.of(
            new Object[]{"STX-001", "stmax_max", "Max", Role.ADMIN},
            new Object[]{"STX-002", "stmax_iris", "Iris 林怡安", Role.MANAGER},
            new Object[]{"STX-003", "stmax_leo", "Leo 陳柏宇", Role.EMPLOYEE},
            new Object[]{"STX-004", "stmax_mia", "Mia 吳佳穎", Role.EMPLOYEE},
            new Object[]{"STX-005", "stmax_ryan", "Ryan 黃冠霖", Role.EMPLOYEE},
            new Object[]{"STX-006", "stmax_zoe", "Zoe 許雅婷", Role.EMPLOYEE});

    @BeforeEach
    void setUp() {
        TestData.reset(jdbc);
        Employee other = employeeRepository.save(new Employee("D0001", "admin", "開發管理員", "{noop}placeholder-hash", Role.ADMIN));
        jdbc.update("insert into attendance (employee_id, work_date, clock_in_at) values (?, ?, now())", other.getId(), LocalDate.parse("2026-10-01"));
        for (Object[] a : ACCOUNTS) {
            employeeRepository.save(new Employee((String) a[0], (String) a[1], (String) a[2], "{noop}placeholder-hash", (Role) a[3]));
        }
    }

    private StmaxOrganizationSeedRunner runner(boolean enabled) {
        return new StmaxOrganizationSeedRunner(employeeAdminRepository, departmentRepository, historyRepository, auditLog, lock,
                transactions, clock, enabled);
    }

    /** 直接寫入一個「別人建立的」部門（沒有示範初始化的歷程）。 */
    private long insertDept(String code, String name) {
        return jdbc.queryForObject("insert into department (code, name, created_at, updated_at) values (?, ?, now(), now()) returning id",
                Long.class, code, name);
    }

    private long id(String username) {
        return employeeRepository.findByUsername(username).orElseThrow().getId();
    }

    private String deptCodeOf(String username) {
        List<String> codes = jdbc.queryForList("select d.code from employee e join department d on d.id = e.department_id where e.username = ?",
                String.class, username);
        return codes.isEmpty() ? null : codes.get(0);
    }

    private String managerOf(String username) {
        List<String> names = jdbc.queryForList("select m.username from employee e join employee m on m.id = e.manager_id where e.username = ?",
                String.class, username);
        return names.isEmpty() ? null : names.get(0);
    }

    /** 帳號本身（舊欄位投影）：ID、員工編號、帳號、名稱、密碼雜湊、角色、啟用、建立時間。 */
    private List<Map<String, Object>> accountColumns() {
        return jdbc.queryForList("select id, employee_no, username, display_name, password_hash, role, enabled, created_at from employee order by id");
    }

    private String orgState() {
        return jdbc.queryForList("select row_to_json(t)::text from (select id, department_id, manager_id, version, updated_at from employee order by id) t",
                String.class) + "|" + jdbc.queryForList("select row_to_json(d)::text from department d order by id", String.class)
                + "|" + jdbc.queryForObject("select count(*) from organization_history", Integer.class);
    }

    @Test
    @DisplayName("SO-01 非 dev profile：元件不存在；開關關閉（預設）→ 什麼都不寫")
    void absentOutsideDevAndOffByDefault() {
        assertThat(context.getBeanNamesForType(StmaxOrganizationSeedRunner.class)).isEmpty();
        String before = orgState();
        assertThat(runner(false).seed().outcome()).isEqualTo(Outcome.DISABLED);
        assertThat(orgState()).isEqualTo(before);
    }

    @Test
    @DisplayName("SO-02 首次套用：三個部門、六人的部門與主管符合示範表；帳號本身完全不變；歷程來源 SYSTEM_DEMO_SEED、沒有操作者")
    void firstRunArrangesOrganization() {
        var accountsBefore = accountColumns();
        String attendanceBefore = jdbc.queryForList("select row_to_json(a)::text from attendance a", String.class).toString();

        Result result = runner(true).seed();

        assertThat(result.outcome()).isEqualTo(Outcome.DONE);
        assertThat(result.createdDepartments()).containsExactly("MANAGEMENT", "PROJECT", "ADMINISTRATION");
        assertThat(result.updated()).hasSize(6);
        assertThat(result.conflicts()).isEmpty();
        assertThat(jdbc.queryForList("select code || ' ' || name from department order by code", String.class))
                .containsExactly("ADMINISTRATION 行政營運", "MANAGEMENT 經營管理", "PROJECT 專案服務");
        assertThat(deptCodeOf("stmax_max")).isEqualTo("MANAGEMENT");
        assertThat(managerOf("stmax_max")).isNull();
        assertThat(deptCodeOf("stmax_iris")).isEqualTo("PROJECT");
        assertThat(managerOf("stmax_iris")).isEqualTo("stmax_max");
        for (String u : List.of("stmax_leo", "stmax_mia", "stmax_ryan")) {
            assertThat(deptCodeOf(u)).as(u).isEqualTo("PROJECT");
            assertThat(managerOf(u)).as(u).isEqualTo("stmax_iris");
        }
        assertThat(deptCodeOf("stmax_zoe")).isEqualTo("ADMINISTRATION");
        assertThat(managerOf("stmax_zoe")).isEqualTo("stmax_max");
        assertThat(deptCodeOf("admin")).as("非示範帳號不動").isNull();

        assertThat(accountColumns()).as("帳號、密碼雜湊、角色、啟用狀態不變").isEqualTo(accountsBefore);
        assertThat(jdbc.queryForList("select row_to_json(a)::text from attendance a", String.class).toString()).isEqualTo(attendanceBefore);
        assertThat(jdbc.queryForObject("select count(*) from organization_history", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("select count(*) from organization_history where source <> 'SYSTEM_DEMO_SEED' or actor_id is not null", Integer.class))
                .as("不假冒 Max").isZero();
        assertThat(jdbc.queryForObject("select string_agg(changes, '|') from organization_history", String.class))
                .doesNotContain("placeholder").doesNotContainIgnoringCase("password");
    }

    @Test
    @DisplayName("SO-03 重跑冪等：第二次沒有任何寫入——版本、updated_at、部門、歷程筆數都不變")
    void rerunIsNoop() {
        runner(true).seed();
        String before = orgState();

        Result again = runner(true).seed();

        assertThat(again.outcome()).isEqualTo(Outcome.DONE);
        assertThat(again.createdDepartments()).isEmpty();
        assertThat(again.updated()).isEmpty();
        assertThat(again.unchanged()).hasSize(6);
        assertThat(again.conflicts()).isEmpty();
        assertThat(orgState()).isEqualTo(before);
    }

    @Test
    @DisplayName("SO-04 已有不同的值：保留並回報（不覆寫）；空白的欄位照常補上")
    void keepsDifferentExistingValues() {
        long other = insertDept("SUPPORT", "客服");
        jdbc.update("update employee set department_id = ? where username = 'stmax_leo'", other);
        jdbc.update("update employee set manager_id = ? where username = 'stmax_mia'", id("stmax_max"));

        Result result = runner(true).seed();

        assertThat(result.outcome()).isEqualTo(Outcome.DONE);
        assertThat(result.conflicts()).hasSize(2).anyMatch(c -> c.contains("stmax_leo")).anyMatch(c -> c.contains("stmax_mia"));
        assertThat(deptCodeOf("stmax_leo")).as("保留").isEqualTo("SUPPORT");
        assertThat(managerOf("stmax_leo")).as("空白的主管照常補上").isEqualTo("stmax_iris");
        assertThat(managerOf("stmax_mia")).as("保留").isEqualTo("stmax_max");
        assertThat(deptCodeOf("stmax_mia")).isEqualTo("PROJECT");
    }

    @Test
    @DisplayName("SO-05 重啟不改回使用者的調整：管理員移除了 Mia 的主管、把部門改名 → 重跑後維持管理員的版本")
    void neverRevertsAdminChanges() {
        runner(true).seed();
        long mia = id("stmax_mia");
        long maxId = id("stmax_max");
        // 模擬管理員經由 API 的調整（同時留下 ADMIN 來源的歷程）
        transactions.executeWithoutResult(s -> {
            lock.acquire();
            Employee e = employeeAdminRepository.findById(mia).orElseThrow();
            employeeAdminRepository.updateIfVersion(mia, e.getVersion(), e.getDisplayName(), e.getRole().name(), e.isEnabled(),
                    e.getDepartmentId(), null, clock.instant());
            auditLog.employee(mia, "EMPLOYEE_UPDATED", "改為直屬 Max 管理前暫時移除", List.of(), maxId,
                    OrganizationHistory.Source.ADMIN, clock.instant());
        });
        jdbc.update("update department set name = '專案服務部', version = version + 1 where code = 'PROJECT'");
        String before = orgState();

        Result again = runner(true).seed();

        assertThat(again.outcome()).as("部門由示範建立，改名不算衝突").isEqualTo(Outcome.DONE);
        assertThat(managerOf("stmax_mia")).as("沒有補回").isNull();
        assertThat(again.conflicts()).singleElement().asString().contains("stmax_mia");
        assertThat(orgState()).isEqualTo(before);
    }

    @Test
    @DisplayName("SO-06 部門代碼已被別人用於不同名稱 → 整批停止，任何人都不調整")
    void foreignDepartmentCodeStopsEverything() {
        insertDept("PROJECT", "別人的專案部");
        String before = orgState();

        Result result = runner(true).seed();

        assertThat(result.outcome()).isEqualTo(Outcome.DEPARTMENT_COLLISION);
        assertThat(result.conflicts()).singleElement().asString().contains("PROJECT").contains("別人的專案部");
        assertThat(orgState()).isEqualTo(before);
    }

    @Test
    @DisplayName("SO-07 別人建立的同名啟用部門 → 沿用（不重建、不寫部門歷程）")
    void adoptsIdenticalForeignDepartment() {
        long existing = insertDept("PROJECT", "專案服務");

        Result result = runner(true).seed();

        assertThat(result.outcome()).isEqualTo(Outcome.DONE);
        assertThat(result.createdDepartments()).containsExactly("MANAGEMENT", "ADMINISTRATION");
        assertThat(jdbc.queryForObject("select department_id from employee where username = 'stmax_leo'", Long.class)).isEqualTo(existing);
    }

    @Test
    @DisplayName("SO-08 帳號核對不符（員工編號對不上、帳號不存在）→ 整批停止，不建立部門")
    void accountMismatchStopsEverything() {
        jdbc.update("update employee set employee_no = 'X-999' where username = 'stmax_ryan'");
        jdbc.update("delete from employee where username = 'stmax_zoe'");
        String before = orgState();

        Result result = runner(true).seed();

        assertThat(result.outcome()).isEqualTo(Outcome.ACCOUNT_MISMATCH);
        assertThat(result.conflicts()).hasSize(2);
        assertThat(orgState()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from department", Integer.class)).isZero();
    }

    @Test
    @DisplayName("SO-09 主管已不符合條件（Iris 被停用）→ 不設定那個主管並回報；其他照常")
    void invalidManagerIsReported() {
        jdbc.update("update employee set enabled = false where username = 'stmax_iris'");

        Result result = runner(true).seed();

        assertThat(result.outcome()).isEqualTo(Outcome.DONE);
        assertThat(result.conflicts()).hasSize(3).allMatch(c -> c.contains("stmax_iris"));
        assertThat(managerOf("stmax_leo")).isNull();
        assertThat(deptCodeOf("stmax_leo")).isEqualTo("PROJECT");
        assertThat(managerOf("stmax_zoe")).isEqualTo("stmax_max");
    }
}
