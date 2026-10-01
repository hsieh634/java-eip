package com.renewflow.eip.identity;

import com.renewflow.eip.support.TestData;
import com.renewflow.eip.support.TestFlywayConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-28：開發帳號密碼維護流程，只在「測試庫」驗證，不碰任何開發帳號。
 * 重點：重設只更新密碼雜湊——員工 ID 與出勤資料保留（舊的「刪帳號重建」在有出勤外鍵後會失敗、也會失去 ID）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestFlywayConfig.class)
class DevSeedRunnerTest {

    // 測試固定資料，非任何真實帳號密碼
    private static final String OLD_PASSWORD = "old-test-only-Passw0rd";
    private static final String NEW_PASSWORD = "new-test-only-Passw0rd";

    @Autowired
    EmployeeRepository employeeRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    JdbcTemplate jdbc;

    Long adminId;

    @BeforeEach
    void setUp() {
        TestData.reset(jdbc);
        Employee admin = employeeRepository.save(
                new Employee("D0001", "admin", "開發管理員", passwordEncoder.encode(OLD_PASSWORD), Role.ADMIN));
        adminId = admin.getId();
        jdbc.update("insert into attendance (employee_id, work_date, clock_in_at) values (?, ?, now())",
                adminId, LocalDate.parse("2026-10-01"));
    }

    private DevSeedRunner runner(String password, boolean reset) {
        return new DevSeedRunner(employeeRepository, passwordEncoder, password, reset);
    }

    @Test
    @DisplayName("A-28 reset=true：只更新密碼雜湊；員工 ID、出勤資料、其他欄位都保留；缺少的帳號仍會建立")
    void resetKeepsIdentityAndAttendance() {
        runner(NEW_PASSWORD, true).run(null);

        Employee admin = employeeRepository.findByUsername("admin").orElseThrow();
        assertThat(admin.getId()).as("員工 ID 不變").isEqualTo(adminId);
        assertThat(admin.getDisplayName()).isEqualTo("開發管理員");
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, admin.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(OLD_PASSWORD, admin.getPasswordHash())).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from attendance where employee_id = ?", Integer.class, adminId))
                .as("出勤資料保留").isEqualTo(1);
        // 另外兩個開發帳號原本不存在，會被建立；總數 3，admin 沒有被重複建立
        assertThat(employeeRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("A-28 reset=false（預設）：已存在的帳號密碼不被更動")
    void withoutResetFlagNothingChanges() {
        runner(NEW_PASSWORD, false).run(null);

        Employee admin = employeeRepository.findByUsername("admin").orElseThrow();
        assertThat(passwordEncoder.matches(OLD_PASSWORD, admin.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(NEW_PASSWORD, admin.getPasswordHash())).isFalse();
    }

    @Test
    @DisplayName("A-28 沒有設定密碼時，即使 reset=true 也不做任何事")
    void blankPasswordDoesNothing() {
        runner("", true).run(null);

        Employee admin = employeeRepository.findByUsername("admin").orElseThrow();
        assertThat(passwordEncoder.matches(OLD_PASSWORD, admin.getPasswordHash())).isTrue();
        assertThat(employeeRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("A-28 reset 只影響三個開發帳號，其他員工的密碼不變")
    void resetDoesNotTouchOtherEmployees() {
        employeeRepository.save(new Employee("T0009", "someone", "其他人", passwordEncoder.encode(OLD_PASSWORD), Role.EMPLOYEE));

        runner(NEW_PASSWORD, true).run(null);

        Employee someone = employeeRepository.findByUsername("someone").orElseThrow();
        assertThat(passwordEncoder.matches(OLD_PASSWORD, someone.getPasswordHash())).isTrue();
    }
}
