package com.renewflow.eip.attendance;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.EmployeeRepository;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.support.MutableClock;
import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestClockConfig;
import com.renewflow.eip.support.TestData;
import com.renewflow.eip.support.TestFlywayConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T03 整合測試共用基礎：真的 Tomcat＋真的 PostgreSQL 測試庫＋完整 Security Filter Chain＋可控制的 Clock。
 * 每個測試前重置測試庫的員工與出勤資料，建立 alice（員工）與 bob（主管）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestFlywayConfig.class, TestClockConfig.class})
public abstract class AttendanceTestBase {

    /** 測試固定資料，非任何真實帳號密碼。 */
    protected static final String PASSWORD = "test-only-Passw0rd";
    /** 台北 2026-10-01 09:00。 */
    protected static final Instant DEFAULT_NOW = Instant.parse("2026-10-01T01:00:00Z");

    @LocalServerPort
    protected int port;
    @Autowired
    protected EmployeeRepository employeeRepository;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected MutableClock clock;

    protected Employee alice;
    protected Employee bob;

    @BeforeEach
    protected void resetData() {
        TestData.reset(jdbc);
        clock.set(DEFAULT_NOW);
        alice = employeeRepository.save(new Employee("T0001", "alice", "愛麗絲", passwordEncoder.encode(PASSWORD), Role.EMPLOYEE));
        bob = employeeRepository.save(new Employee("T0002", "bob", "鮑伯", passwordEncoder.encode(PASSWORD), Role.MANAGER));
    }

    protected TestBrowser loginAs(String username) {
        TestBrowser browser = new TestBrowser(port);
        TestBrowser.Reply reply = browser.login(username, PASSWORD);
        assertThat(reply.status()).as("login as " + username).isEqualTo(200);
        return browser;
    }

    protected int attendanceRows() {
        return jdbc.queryForObject("select count(*) from attendance", Integer.class);
    }

    protected int attendanceRowsOf(Employee employee) {
        return jdbc.queryForObject("select count(*) from attendance where employee_id = ?", Integer.class, employee.getId());
    }
}
