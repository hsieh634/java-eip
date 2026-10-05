package com.renewflow.eip.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 【僅開發用、預設關閉】建立 STMAX 示範公司的六個帳號（虛構的網站開發與數位行銷公司；姓名、員工編號皆為虛構）。
 *
 * <p>安全規則：
 * <ul>
 *   <li>只在 dev profile 才存在這個元件；而且必須明確設定 RENEWFLOW_DEMO_STMAX_ENABLED=true。</li>
 *   <li>沒有預設密碼：RENEWFLOW_DEMO_STMAX_PASSWORD 未設定就不建立。密碼只以 PasswordEncoder 雜湊後保存，不寫進日誌。</li>
 *   <li>冪等：已由這裡建立的帳號（帳號與員工編號都相同）重跑時完全不動——不重設密碼、不改角色、不改啟用狀態。</li>
 *   <li>碰撞時安全停止：帳號或員工編號已被「別的」員工使用，就不建立任何一個示範帳號，也不接管或覆寫既有帳號。</li>
 *   <li>全部建立在同一個交易內：要嘛六個缺少的都建立，要嘛都不建立。</li>
 * </ul>
 * Max 的「負責人」只是示範職務，角色仍是既有的 ADMIN，沒有任何繞過規則的權限。
 */
@Component
@Profile("dev")
@Order(100) // 先於 StmaxOrganizationSeedRunner（@Order(200)）：組織排列需要帳號已存在
public class StmaxDemoSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StmaxDemoSeedRunner.class);

    static final String ENABLED_VARIABLE = "RENEWFLOW_DEMO_STMAX_ENABLED";
    static final String PASSWORD_VARIABLE = "RENEWFLOW_DEMO_STMAX_PASSWORD";

    /** 示範帳號（全部虛構）。情境職務只寫在文件；系統角色沿用既有的三種。 */
    record DemoAccount(String employeeNo, String username, String displayName, Role role) {
    }

    static final List<DemoAccount> ACCOUNTS = List.of(
            new DemoAccount("STX-001", "stmax_max", "Max", Role.ADMIN),
            new DemoAccount("STX-002", "stmax_iris", "Iris 林怡安", Role.MANAGER),
            new DemoAccount("STX-003", "stmax_leo", "Leo 陳柏宇", Role.EMPLOYEE),
            new DemoAccount("STX-004", "stmax_mia", "Mia 吳佳穎", Role.EMPLOYEE),
            new DemoAccount("STX-005", "stmax_ryan", "Ryan 黃冠霖", Role.EMPLOYEE),
            new DemoAccount("STX-006", "stmax_zoe", "Zoe 許雅婷", Role.EMPLOYEE));

    enum Outcome { DISABLED, NO_PASSWORD, COLLISION, DONE }

    /** 執行結果（給日誌與測試使用）。 */
    record Result(Outcome outcome, List<String> created, List<String> alreadyPresent, List<String> collisions) {
    }

    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final String password;

    StmaxDemoSeedRunner(EmployeeRepository employeeRepository,
                        PasswordEncoder passwordEncoder,
                        TransactionTemplate transactions,
                        @Value("${renewflow.demo.stmax.enabled:false}") boolean enabled,
                        @Value("${renewflow.demo.stmax.password:}") String password) {
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.transactions = transactions;
        this.enabled = enabled;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        Result result = seed();
        switch (result.outcome()) {
            case DISABLED -> log.info("STMAX 示範帳號未啟用（{} 不是 true），不建立。", ENABLED_VARIABLE);
            case NO_PASSWORD -> log.warn("STMAX 示範已啟用，但 {} 未設定，不建立任何示範帳號（沒有預設密碼）。", PASSWORD_VARIABLE);
            case COLLISION -> log.error("STMAX 示範帳號與既有員工碰撞，已安全停止、沒有建立或修改任何帳號：{}", result.collisions());
            case DONE -> log.info("STMAX 示範帳號：新建立 {}；已存在（未修改）{}", result.created(), result.alreadyPresent());
        }
    }

    /** 規劃 → 檢查碰撞 → 在一個交易內建立缺少的帳號。 */
    Result seed() {
        if (!enabled) {
            return new Result(Outcome.DISABLED, List.of(), List.of(), List.of());
        }
        if (password == null || password.isBlank()) {
            return new Result(Outcome.NO_PASSWORD, List.of(), List.of(), List.of());
        }
        List<DemoAccount> toCreate = new ArrayList<>();
        List<String> present = new ArrayList<>();
        List<String> collisions = new ArrayList<>();
        for (DemoAccount account : ACCOUNTS) {
            Optional<Employee> byUsername = employeeRepository.findByUsername(account.username());
            Optional<Employee> byNumber = employeeRepository.findByEmployeeNo(account.employeeNo());
            if (byUsername.isPresent() && account.employeeNo().equals(byUsername.get().getEmployeeNo())) {
                present.add(account.username()); // 先前由這裡建立：保持原狀
            } else if (byUsername.isPresent()) {
                collisions.add("帳號 " + account.username() + " 已被員工編號 " + byUsername.get().getEmployeeNo() + " 使用");
            } else if (byNumber.isPresent()) {
                collisions.add("員工編號 " + account.employeeNo() + " 已被帳號 " + byNumber.get().getUsername() + " 使用");
            } else {
                toCreate.add(account);
            }
        }
        if (!collisions.isEmpty()) {
            return new Result(Outcome.COLLISION, List.of(), present, collisions);
        }
        transactions.executeWithoutResult(status -> {
            for (DemoAccount account : toCreate) {
                employeeRepository.save(new Employee(account.employeeNo(), account.username(), account.displayName(),
                        passwordEncoder.encode(password), account.role()));
            }
        });
        return new Result(Outcome.DONE, toCreate.stream().map(DemoAccount::username).toList(), present, List.of());
    }
}
