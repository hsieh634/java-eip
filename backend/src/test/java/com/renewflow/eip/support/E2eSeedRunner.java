package com.renewflow.eip.support;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.EmployeeRepository;
import com.renewflow.eip.identity.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 【僅 E2E 隔離堆疊】profile=e2e 啟動時，為「這一次執行」建立專用員工（A、C 為員工，B 為主管，另有一位 ADMIN）（帳號含執行識別碼）。
 * 因為每次都是全新的帳號，E2E 重複執行不需要清空任何資料。
 * 只存在於測試原始碼，不會進入正式程式；寫入前先確認實際連到的是測試資料庫、不是開發資料庫。
 * 更早的一道保護在 {@link E2eFlywayConfig}：連 migration 都會在資料庫檢查通過之後才執行。
 */
@Component
@Profile("e2e")
class E2eSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(E2eSeedRunner.class);

    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final DataSource dataSource;
    private final String runId;
    private final String password;
    private final String testDatabase;
    private final String devDatabase;

    E2eSeedRunner(EmployeeRepository employeeRepository,
                  PasswordEncoder passwordEncoder,
                  DataSource dataSource,
                  @Value("${renewflow.e2e.run-id}") String runId,
                  @Value("${renewflow.e2e.password}") String password,
                  @Value("${renewflow.test.database}") String testDatabase,
                  @Value("${renewflow.test.dev-database}") String devDatabase) {
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.dataSource = dataSource;
        this.runId = runId;
        this.password = password;
        this.testDatabase = testDatabase;
        this.devDatabase = devDatabase;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            // 與測試清空保護同一套檢查：實際資料庫必須是明確允許的測試庫、且不是開發庫
            TestDatabaseGuard.assertSafeToClean(TestDatabaseGuard.actualDatabase(connection), testDatabase, devDatabase);
        }
        if (runId.isBlank() || password.isBlank()) {
            throw new IllegalStateException("RENEWFLOW_E2E_RUN_ID／RENEWFLOW_E2E_PASSWORD 未設定");
        }
        employeeRepository.save(new Employee("EA-" + runId, "e2e_a_" + runId, "E2E 員工 A", passwordEncoder.encode(password), Role.EMPLOYEE));
        employeeRepository.save(new Employee("EB-" + runId, "e2e_b_" + runId, "E2E 主管 B", passwordEncoder.encode(password), Role.MANAGER));
        employeeRepository.save(new Employee("EC-" + runId, "e2e_c_" + runId, "E2E 員工 C", passwordEncoder.encode(password), Role.EMPLOYEE));
        employeeRepository.save(new Employee("EX-" + runId, "e2e_admin_" + runId, "E2E 管理員", passwordEncoder.encode(password), Role.ADMIN));
        log.info("E2E 專用員工已建立（run id {}），資料庫 {}", runId, testDatabase);
    }
}
