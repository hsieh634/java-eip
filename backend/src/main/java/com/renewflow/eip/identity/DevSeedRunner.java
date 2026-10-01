package com.renewflow.eip.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 【僅開發用】dev profile 啟動時建立三個示範帳號，方便本機登入。
 * 密碼來自環境變數 RENEWFLOW_DEV_SEED_PASSWORD（.env），原始碼與資料庫 migration 都不含密碼；
 * 未設定時不建立任何帳號。正式環境不會啟用 dev profile，所以這段不會執行。
 */
@Component
@Profile("dev")
class DevSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeedRunner.class);

    private record Seed(String employeeNo, String username, String displayName, Role role) {
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed("D0001", "admin", "開發管理員", Role.ADMIN),
            new Seed("D0002", "manager", "開發主管", Role.MANAGER),
            new Seed("D0003", "employee", "開發員工", Role.EMPLOYEE));

    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final String seedPassword;

    DevSeedRunner(EmployeeRepository employeeRepository,
                  PasswordEncoder passwordEncoder,
                  @Value("${renewflow.dev-seed.password:}") String seedPassword) {
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedPassword = seedPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedPassword.isBlank()) {
            log.warn("RENEWFLOW_DEV_SEED_PASSWORD 未設定，不建立開發帳號。");
            return;
        }
        for (Seed seed : SEEDS) {
            if (!employeeRepository.existsByUsername(seed.username())) {
                employeeRepository.save(new Employee(
                        seed.employeeNo(), seed.username(), seed.displayName(),
                        passwordEncoder.encode(seedPassword), seed.role()));
                log.info("已建立開發帳號：{}（{}）", seed.username(), seed.role());
            }
        }
    }
}
