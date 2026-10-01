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
import java.util.Optional;

/**
 * 【僅開發用】dev profile 啟動時建立三個示範帳號，方便本機登入。
 * 密碼來自環境變數 RENEWFLOW_DEV_SEED_PASSWORD（.env），原始碼與資料庫 migration 都不含密碼；
 * 未設定時不建立任何帳號。正式環境不會啟用 dev profile，所以這段不會執行。
 *
 * <p>開發維護流程：忘記或想改開發帳號密碼時，設定 RENEWFLOW_DEV_SEED_RESET=true 後重啟，
 * 這三個帳號會「只更新密碼雜湊」——員工 ID 與出勤資料都保留（有出勤紀錄後，刪除帳號重建會被外鍵擋下，
 * 也會失去 ID）。這不是 API，只在啟動時由 dev profile 執行；用完請把旗標改回 false。
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
    private final boolean resetPasswords;

    DevSeedRunner(EmployeeRepository employeeRepository,
                  PasswordEncoder passwordEncoder,
                  @Value("${renewflow.dev-seed.password:}") String seedPassword,
                  @Value("${renewflow.dev-seed.reset-passwords:false}") boolean resetPasswords) {
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedPassword = seedPassword;
        this.resetPasswords = resetPasswords;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedPassword.isBlank()) {
            log.warn("RENEWFLOW_DEV_SEED_PASSWORD 未設定，不建立開發帳號。");
            return;
        }
        for (Seed seed : SEEDS) {
            Optional<Employee> existing = employeeRepository.findByUsername(seed.username());
            if (existing.isEmpty()) {
                employeeRepository.save(new Employee(
                        seed.employeeNo(), seed.username(), seed.displayName(),
                        passwordEncoder.encode(seedPassword), seed.role()));
                log.info("已建立開發帳號：{}（{}）", seed.username(), seed.role());
            } else if (resetPasswords) {
                // 只換密碼雜湊，不刪除、不重建：員工 ID 與關聯的出勤資料不受影響
                Employee employee = existing.get();
                employee.changePasswordHash(passwordEncoder.encode(seedPassword));
                employeeRepository.save(employee);
                log.warn("已重設開發帳號密碼：{}（員工 ID 與資料保留；請把 RENEWFLOW_DEV_SEED_RESET 改回 false）", seed.username());
            }
        }
    }
}
