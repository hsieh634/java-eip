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
 * 【僅開發用】dev profile 啟動時建立示範帳號，方便本機登入。正式環境不會啟用 dev profile，所以這段不會執行。
 *
 * <ul>
 *   <li>admin／manager／employee：共用密碼，來自 RENEWFLOW_DEV_SEED_PASSWORD。</li>
 *   <li>developer（角色 ADMIN，本機開發用的管理帳號）：<b>獨立</b>的密碼，來自 RENEWFLOW_DEV_DEVELOPER_PASSWORD，
 *       不與一般員工共用。</li>
 * </ul>
 * 原始碼與 migration 都不含任何密碼，也沒有預設密碼：對應的環境變數沒設定，就不建立該帳號並明確提示。
 *
 * <p>開發維護流程：忘記或想改密碼時，設定 RENEWFLOW_DEV_SEED_RESET=true 後重啟，
 * 這幾個帳號會「只更新密碼雜湊」——員工 ID 與出勤資料都保留（有出勤紀錄後，刪除帳號重建會被外鍵擋下，
 * 也會失去 ID）。這不是 API，只在啟動時由 dev profile 執行；用完請把旗標改回 false。
 */
@Component
@Profile("dev")
class DevSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeedRunner.class);

    static final String SHARED_PASSWORD_VARIABLE = "RENEWFLOW_DEV_SEED_PASSWORD";
    static final String DEVELOPER_PASSWORD_VARIABLE = "RENEWFLOW_DEV_DEVELOPER_PASSWORD";

    private record Seed(String employeeNo, String username, String displayName, Role role, boolean ownPassword) {
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed("D0001", "admin", "開發管理員", Role.ADMIN, false),
            new Seed("D0002", "manager", "開發主管", Role.MANAGER, false),
            new Seed("D0003", "employee", "開發員工", Role.EMPLOYEE, false),
            new Seed("D0004", "developer", "開發者（管理）", Role.ADMIN, true));

    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final String sharedPassword;
    private final String developerPassword;
    private final boolean resetPasswords;

    DevSeedRunner(EmployeeRepository employeeRepository,
                  PasswordEncoder passwordEncoder,
                  @Value("${renewflow.dev-seed.password:}") String sharedPassword,
                  @Value("${renewflow.dev-seed.developer-password:}") String developerPassword,
                  @Value("${renewflow.dev-seed.reset-passwords:false}") boolean resetPasswords) {
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.sharedPassword = sharedPassword;
        this.developerPassword = developerPassword;
        this.resetPasswords = resetPasswords;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Seed seed : SEEDS) {
            String password = seed.ownPassword() ? developerPassword : sharedPassword;
            if (password.isBlank()) {
                log.warn("{} 未設定，不建立（或更新）開發帳號 {}。請在 .env 設定後重新啟動。",
                        seed.ownPassword() ? DEVELOPER_PASSWORD_VARIABLE : SHARED_PASSWORD_VARIABLE, seed.username());
                continue;
            }
            Optional<Employee> existing = employeeRepository.findByUsername(seed.username());
            if (existing.isEmpty()) {
                employeeRepository.save(new Employee(
                        seed.employeeNo(), seed.username(), seed.displayName(), passwordEncoder.encode(password), seed.role()));
                log.info("已建立開發帳號：{}（{}）", seed.username(), seed.role());
            } else if (resetPasswords) {
                // 只換密碼雜湊，不刪除、不重建：員工 ID 與關聯的出勤資料不受影響
                Employee employee = existing.get();
                employee.changePasswordHash(passwordEncoder.encode(password));
                employeeRepository.save(employee);
                log.warn("已重設開發帳號密碼：{}（員工 ID 與資料保留；請把 RENEWFLOW_DEV_SEED_RESET 改回 false）", seed.username());
            }
        }
    }
}
