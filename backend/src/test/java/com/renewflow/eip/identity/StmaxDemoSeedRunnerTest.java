package com.renewflow.eip.identity;

import com.renewflow.eip.identity.StmaxDemoSeedRunner.Outcome;
import com.renewflow.eip.identity.StmaxDemoSeedRunner.Result;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STMAX 示範帳號初始化：只在測試庫驗證（測試前清空的是測試庫，不是開發庫）。
 * 直接建構 runner 並傳入開關與密碼，逐一驗證：關閉、缺密碼、首次建立、重跑冪等、碰撞時安全停止、既有帳號保留、
 * 非 dev profile 時元件不存在。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestFlywayConfig.class)
class StmaxDemoSeedRunnerTest {

    // 測試固定資料，非任何真實帳號密碼
    private static final String DEMO_PASSWORD = "test-only-Demo1234";
    private static final String OTHER_PASSWORD = "test-only-Other5678";

    @Autowired
    EmployeeRepository employeeRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ApplicationContext context;

    private Long existingAdminId;

    @BeforeEach
    void setUp() {
        TestData.reset(jdbc);
        // 既有開發帳號與它的出勤：初始化不能碰
        Employee admin = employeeRepository.save(new Employee("D0001", "admin", "開發管理員", passwordEncoder.encode(OTHER_PASSWORD), Role.ADMIN));
        existingAdminId = admin.getId();
        jdbc.update("insert into attendance (employee_id, work_date, clock_in_at) values (?, ?, now())", existingAdminId, LocalDate.parse("2026-10-01"));
    }

    private StmaxDemoSeedRunner runner(boolean enabled, String password) {
        return new StmaxDemoSeedRunner(employeeRepository, passwordEncoder, transactions, enabled, password);
    }

    private int stmaxCount() {
        return jdbc.queryForObject("select count(*) from employee where username like 'stmax\\_%'", Integer.class);
    }

    private String fingerprintOfExisting() {
        return jdbc.queryForList("select row_to_json(e)::text from employee e where username not like 'stmax\\_%' order by id", String.class)
                + "|" + jdbc.queryForList("select row_to_json(a)::text from attendance a order by id", String.class);
    }

    @Test
    @DisplayName("D-01 非 dev profile：元件根本不存在（測試 profile 下沒有這個 bean）")
    void absentOutsideDevProfile() {
        assertThat(context.getBeanNamesForType(StmaxDemoSeedRunner.class)).isEmpty();
    }

    @Test
    @DisplayName("D-02 開關關閉（預設）→ 不建立；開啟但沒有密碼 → 不建立（沒有預設密碼）")
    void disabledOrNoPassword() {
        assertThat(runner(false, DEMO_PASSWORD).seed().outcome()).isEqualTo(Outcome.DISABLED);
        assertThat(runner(true, "").seed().outcome()).isEqualTo(Outcome.NO_PASSWORD);
        assertThat(runner(true, "   ").seed().outcome()).isEqualTo(Outcome.NO_PASSWORD);
        assertThat(stmaxCount()).isZero();
    }

    @Test
    @DisplayName("D-03 首次建立：六個帳號、角色正確、密碼以 PasswordEncoder 雜湊（不是明文）；既有帳號與出勤完全不變")
    void firstRunCreatesSixAccounts() {
        String before = fingerprintOfExisting();

        Result result = runner(true, DEMO_PASSWORD).seed();

        assertThat(result.outcome()).isEqualTo(Outcome.DONE);
        assertThat(result.created()).containsExactly("stmax_max", "stmax_iris", "stmax_leo", "stmax_mia", "stmax_ryan", "stmax_zoe");
        Map<String, Role> roles = Map.of("stmax_max", Role.ADMIN, "stmax_iris", Role.MANAGER, "stmax_leo", Role.EMPLOYEE,
                "stmax_mia", Role.EMPLOYEE, "stmax_ryan", Role.EMPLOYEE, "stmax_zoe", Role.EMPLOYEE);
        roles.forEach((username, role) -> {
            Employee e = employeeRepository.findByUsername(username).orElseThrow();
            assertThat(e.getRole()).as(username).isEqualTo(role);
            assertThat(e.isEnabled()).isTrue();
            assertThat(e.getPasswordHash()).doesNotContain(DEMO_PASSWORD).startsWith("{bcrypt}");
            assertThat(passwordEncoder.matches(DEMO_PASSWORD, e.getPasswordHash())).isTrue();
        });
        assertThat(employeeRepository.findByUsername("stmax_iris").orElseThrow().getEmployeeNo()).isEqualTo("STX-002");
        assertThat(fingerprintOfExisting()).as("既有帳號與出勤不變").isEqualTo(before);
    }

    @Test
    @DisplayName("D-04 重跑冪等：不新增、不重設密碼（即使換了密碼設定）、不改回角色或啟用狀態；員工 ID 不變")
    void rerunChangesNothing() {
        runner(true, DEMO_PASSWORD).seed();
        // 建立後，使用者自己（例如管理員）調整了其中兩個帳號
        jdbc.update("update employee set enabled = false where username = 'stmax_leo'");
        jdbc.update("update employee set role = 'MANAGER' where username = 'stmax_mia'");
        List<Map<String, Object>> before = jdbc.queryForList("select * from employee where username like 'stmax\\_%' order by id");

        Result again = runner(true, OTHER_PASSWORD).seed();

        assertThat(again.outcome()).isEqualTo(Outcome.DONE);
        assertThat(again.created()).isEmpty();
        assertThat(again.alreadyPresent()).hasSize(6);
        assertThat(jdbc.queryForList("select * from employee where username like 'stmax\\_%' order by id")).isEqualTo(before);
        assertThat(passwordEncoder.matches(DEMO_PASSWORD, employeeRepository.findByUsername("stmax_max").orElseThrow().getPasswordHash()))
                .as("密碼沒有被新設定重設").isTrue();
    }

    @Test
    @DisplayName("D-05 帳號碰撞：stmax_leo 已被別的員工編號使用 → 安全停止，一個示範帳號都不建立，既有帳號不被接管")
    void usernameCollisionStopsSafely() {
        Employee someone = employeeRepository.save(new Employee("X0999", "stmax_leo", "別人的帳號", passwordEncoder.encode(OTHER_PASSWORD), Role.EMPLOYEE));
        String before = fingerprintOfExisting() + jdbc.queryForObject("select row_to_json(e)::text from employee e where id = ?", String.class, someone.getId());

        Result result = runner(true, DEMO_PASSWORD).seed();

        assertThat(result.outcome()).isEqualTo(Outcome.COLLISION);
        assertThat(result.collisions()).singleElement().asString().contains("stmax_leo").contains("X0999");
        assertThat(stmaxCount()).as("只有原本那一個").isEqualTo(1);
        assertThat(fingerprintOfExisting() + jdbc.queryForObject("select row_to_json(e)::text from employee e where id = ?", String.class, someone.getId()))
                .isEqualTo(before);
        assertThat(passwordEncoder.matches(OTHER_PASSWORD, employeeRepository.findByUsername("stmax_leo").orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("D-06 員工編號碰撞：STX-005 已被別的帳號使用 → 安全停止，不建立任何示範帳號")
    void employeeNumberCollisionStopsSafely() {
        employeeRepository.save(new Employee("STX-005", "someone_else", "別人", passwordEncoder.encode(OTHER_PASSWORD), Role.EMPLOYEE));

        Result result = runner(true, DEMO_PASSWORD).seed();

        assertThat(result.outcome()).isEqualTo(Outcome.COLLISION);
        assertThat(result.collisions()).singleElement().asString().contains("STX-005").contains("someone_else");
        assertThat(stmaxCount()).isZero();
    }

    @Test
    @DisplayName("D-07 部分已存在（先前建立過其中幾個）→ 只補建缺少的，已存在的不動")
    void createsOnlyMissing() {
        runner(true, DEMO_PASSWORD).seed();
        jdbc.update("delete from employee where username in ('stmax_ryan', 'stmax_zoe')");
        Long maxId = employeeRepository.findByUsername("stmax_max").orElseThrow().getId();

        Result result = runner(true, DEMO_PASSWORD).seed();

        assertThat(result.created()).containsExactly("stmax_ryan", "stmax_zoe");
        assertThat(result.alreadyPresent()).hasSize(4);
        assertThat(employeeRepository.findByUsername("stmax_max").orElseThrow().getId()).isEqualTo(maxId);
        assertThat(stmaxCount()).isEqualTo(6);
    }
}
