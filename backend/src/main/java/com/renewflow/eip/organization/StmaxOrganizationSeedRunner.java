package com.renewflow.eip.organization;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.organization.OrganizationAuditLog.Change;
import com.renewflow.eip.organization.OrganizationHistory.Source;
import com.renewflow.eip.shared.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 【僅開發用、預設關閉】把 STMAX 示範公司的六個既有帳號排進三個部門並設定直屬主管。
 *
 * <p>安全規則：
 * <ul>
 *   <li>只在 dev profile 才存在；而且必須另外明確設定 RENEWFLOW_DEMO_STMAX_ORG_ENABLED=true（與建立帳號的開關分開）。</li>
 *   <li>不建立、不修改帳號本身：ID、帳號、密碼、角色、啟用狀態全部不動，只寫部門與主管。</li>
 *   <li>寫入前先核對：六個帳號都存在且帳號與員工編號對得上；三個部門代碼若已存在，名稱必須相同且為啟用。不符就整批停止、不寫任何東西。</li>
 *   <li>只補「尚未指派」的欄位：已經是目標值→不動（版本、歷程都不變）；已經是別的值→保留並回報衝突，不覆寫使用者的調整。</li>
 *   <li>曾被管理員調整過組織資料的人（有 ADMIN 來源的歷程）整個人不動——例如管理員刻意移除了主管，重啟也不會補回去。</li>
 *   <li>同一個交易、同一把組織鎖；歷程來源標示為 SYSTEM_DEMO_SEED（操作者為空），不假冒 Max。</li>
 * </ul>
 * 執行時機在 STMAX 帳號建立之後（@Order）。重啟時重跑是安全的：第二次起沒有任何變更。
 */
@Component
@Profile("dev")
@Order(200)
public class StmaxOrganizationSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StmaxOrganizationSeedRunner.class);

    static final String ENABLED_VARIABLE = "RENEWFLOW_DEMO_STMAX_ORG_ENABLED";
    static final String REASON = "STMAX 示範組織初始化（dev）";

    record DemoDepartment(String code, String name) {
    }

    /** 示範的組織排列（帳號與員工編號必須與 StmaxDemoSeedRunner 建立的一致）。 */
    record Assignment(String employeeNo, String username, String departmentCode, String managerUsername) {
    }

    static final List<DemoDepartment> DEPARTMENTS = List.of(
            new DemoDepartment("MANAGEMENT", "經營管理"),
            new DemoDepartment("PROJECT", "專案服務"),
            new DemoDepartment("ADMINISTRATION", "行政營運"));

    static final List<Assignment> ASSIGNMENTS = List.of(
            new Assignment("STX-001", "stmax_max", "MANAGEMENT", null),
            new Assignment("STX-002", "stmax_iris", "PROJECT", "stmax_max"),
            new Assignment("STX-003", "stmax_leo", "PROJECT", "stmax_iris"),
            new Assignment("STX-004", "stmax_mia", "PROJECT", "stmax_iris"),
            new Assignment("STX-005", "stmax_ryan", "PROJECT", "stmax_iris"),
            new Assignment("STX-006", "stmax_zoe", "ADMINISTRATION", "stmax_max"));

    enum Outcome { DISABLED, ACCOUNT_MISMATCH, DEPARTMENT_COLLISION, DONE }

    /** 執行結果（給日誌與測試）。conflicts：保留既有值、沒有覆寫的欄位。 */
    record Result(Outcome outcome, List<String> createdDepartments, List<String> updated, List<String> unchanged,
                  List<String> conflicts) {
        static Result stopped(Outcome outcome, List<String> problems) {
            return new Result(outcome, List.of(), List.of(), List.of(), problems);
        }
    }

    private final EmployeeAdminRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final OrganizationHistoryRepository historyRepository;
    private final OrganizationAuditLog auditLog;
    private final OrganizationLock lock;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean enabled;

    StmaxOrganizationSeedRunner(EmployeeAdminRepository employeeRepository, DepartmentRepository departmentRepository,
                                OrganizationHistoryRepository historyRepository, OrganizationAuditLog auditLog, OrganizationLock lock,
                                TransactionTemplate transactions, Clock clock,
                                @Value("${renewflow.demo.stmax.org.enabled:false}") boolean enabled) {
        this.employeeRepository = employeeRepository;
        this.departmentRepository = departmentRepository;
        this.historyRepository = historyRepository;
        this.auditLog = auditLog;
        this.lock = lock;
        this.transactions = transactions;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        Result result = seed();
        switch (result.outcome()) {
            case DISABLED -> log.info("STMAX 示範組織未啟用（{} 不是 true），不調整。", ENABLED_VARIABLE);
            case ACCOUNT_MISMATCH -> log.error("STMAX 示範組織：帳號核對不符，已安全停止、沒有寫入：{}", result.conflicts());
            case DEPARTMENT_COLLISION -> log.error("STMAX 示範組織：部門代碼與既有資料衝突，已安全停止、沒有寫入：{}", result.conflicts());
            case DONE -> {
                log.info("STMAX 示範組織：新建部門 {}；已調整 {}；已符合 {}", result.createdDepartments(), result.updated(), result.unchanged());
                if (!result.conflicts().isEmpty()) {
                    log.warn("STMAX 示範組織：以下欄位已有不同的值，保留現況、沒有覆寫：{}", result.conflicts());
                }
            }
        }
    }

    Result seed() {
        if (!enabled) {
            return Result.stopped(Outcome.DISABLED, List.of());
        }
        return transactions.execute(status -> {
            lock.acquire();
            return seedLocked();
        });
    }

    private Result seedLocked() {
        // 1. 核對六個帳號：帳號存在且員工編號相符
        List<String> mismatches = accountMismatches();
        if (!mismatches.isEmpty()) {
            return Result.stopped(Outcome.ACCOUNT_MISMATCH, mismatches);
        }
        // 2. 核對部門代碼
        List<String> collisions = departmentCollisions();
        if (!collisions.isEmpty()) {
            return Result.stopped(Outcome.DEPARTMENT_COLLISION, collisions);
        }
        Instant now = BusinessTime.truncateToDatabasePrecision(clock.instant());
        List<String> createdDepartments = new ArrayList<>();
        Map<String, Long> departmentIds = ensureDepartments(createdDepartments, now);

        // 3. 逐人補上尚未指派的部門與主管（順序：先 Max、Iris，再其部屬）
        List<String> updated = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        for (Assignment a : ASSIGNMENTS) {
            Employee e = employeeRepository.findByUsername(a.username()).orElseThrow();
            Long targetDepartmentId = departmentIds.get(a.departmentCode());
            Employee targetManager = a.managerUsername() == null ? null : employeeRepository.findByUsername(a.managerUsername()).orElseThrow();
            boolean changed;
            if (historyRepository.existsByEmployeeIdAndSource(e.getId(), Source.ADMIN.name())) {
                // 管理員調整過這個人的組織資料（例如刻意移除主管）→ 整個人不動，重啟時不會把使用者的調整改回去
                changed = false;
                if (!matchesTarget(e, targetDepartmentId, targetManager)) {
                    conflicts.add(a.username() + " 已由管理員調整過組織資料（保留現況）");
                }
            } else {
                changed = fillUnassigned(a, e, targetDepartmentId, targetManager, conflicts, now);
            }
            if (changed) {
                updated.add(a.username());
            } else if (matchesTarget(employeeRepository.findById(e.getId()).orElseThrow(), targetDepartmentId, targetManager)) {
                unchanged.add(a.username());
            }
        }
        return new Result(Outcome.DONE, createdDepartments, updated, unchanged, conflicts);
    }

    private List<String> accountMismatches() {
        List<String> mismatches = new ArrayList<>();
        for (Assignment a : ASSIGNMENTS) {
            Optional<Employee> byUsername = employeeRepository.findByUsername(a.username());
            if (byUsername.isEmpty()) {
                mismatches.add("找不到帳號 " + a.username());
            } else if (!a.employeeNo().equals(byUsername.get().getEmployeeNo())) {
                mismatches.add("帳號 " + a.username() + " 的員工編號是 " + byUsername.get().getEmployeeNo() + "，預期 " + a.employeeNo());
            }
        }
        return mismatches;
    }

    /**
     * 由示範初始化建立的部門 → 沿用（之後被管理員改名或停用也一樣，不改回去）；
     * 別人建立的同代碼部門 → 必須同名且啟用才沿用，否則整批停止（不接管、不改名）。
     */
    private List<String> departmentCollisions() {
        List<String> collisions = new ArrayList<>();
        for (DemoDepartment d : DEPARTMENTS) {
            departmentRepository.findByCode(d.code()).ifPresent(existing -> {
                boolean ours = historyRepository.existsByDepartmentIdAndSourceAndAction(existing.getId(),
                        Source.SYSTEM_DEMO_SEED.name(), "DEPARTMENT_CREATED");
                if (!ours && (!existing.getName().equals(d.name()) || !existing.isActive())) {
                    collisions.add("部門代碼 " + d.code() + " 已存在（名稱「" + existing.getName() + "」、"
                            + (existing.isActive() ? "啟用" : "停用") + "），與示範的「" + d.name() + "」不一致");
                }
            });
        }
        return collisions;
    }

    private Map<String, Long> ensureDepartments(List<String> createdDepartments, Instant now) {
        Map<String, Long> departmentIds = new HashMap<>();
        for (DemoDepartment d : DEPARTMENTS) {
            Long id = departmentRepository.findByCode(d.code()).map(Department::getId).orElse(null);
            if (id == null) {
                id = departmentRepository.insertIfCodeFree(d.code(), d.name(), now).orElseThrow();
                auditLog.department(id, "DEPARTMENT_CREATED", REASON,
                        List.of(new Change("code", null, d.code()), new Change("name", null, d.name()), new Change("active", null, true)),
                        null, Source.SYSTEM_DEMO_SEED, now);
                createdDepartments.add(d.code());
            }
            departmentIds.put(d.code(), id);
        }
        return departmentIds;
    }

    private static boolean matchesTarget(Employee e, Long targetDepartmentId, Employee targetManager) {
        return Objects.equals(e.getDepartmentId(), targetDepartmentId)
                && Objects.equals(e.getManagerId(), targetManager == null ? null : targetManager.getId());
    }

    /** 只補空白的欄位；已有不同的值 → 保留並記到 conflicts。有寫入時回傳 true。 */
    private boolean fillUnassigned(Assignment a, Employee e, Long targetDepartmentId, Employee targetManager,
                                   List<String> conflicts, Instant now) {
        Department targetDepartment = departmentRepository.findById(targetDepartmentId).orElseThrow();
        Long departmentId = e.getDepartmentId();
        Long managerId = e.getManagerId();
        List<Change> changes = new ArrayList<>();

        if (departmentId == null && targetDepartment.isActive()) {
            departmentId = targetDepartment.getId();
            changes.add(new Change("department", null, EmployeeAdminService.ref(targetDepartment)));
        } else if (departmentId == null) {
            conflicts.add(a.username() + " 的目標部門 " + a.departmentCode() + " 已停用（未指派）");
        } else if (!departmentId.equals(targetDepartmentId)) {
            conflicts.add(a.username() + " 的部門已是其他部門（保留）");
        }

        if (targetManager != null && managerId == null) {
            String problem = managerProblem(e, targetManager);
            if (problem == null) {
                managerId = targetManager.getId();
                changes.add(new Change("manager", null, EmployeeAdminService.ref(targetManager)));
            } else {
                conflicts.add(a.username() + " 不能設定主管 " + a.managerUsername() + "：" + problem + "（未設定）");
            }
        } else if (targetManager != null && !managerId.equals(targetManager.getId())) {
            conflicts.add(a.username() + " 的直屬主管已是其他人（保留）");
        }

        if (changes.isEmpty()) {
            return false;
        }
        // 只寫部門與主管；名稱、角色、啟用狀態用目前的值原樣寫回（鎖內讀到的就是最新值）
        if (employeeRepository.updateIfVersion(e.getId(), e.getVersion(), e.getDisplayName(), e.getRole().name(), e.isEnabled(),
                departmentId, managerId, now) == 0) {
            throw new IllegalStateException("組織鎖內版本不應改變：" + a.username());
        }
        auditLog.employee(e.getId(), "EMPLOYEE_UPDATED", REASON, changes, null, Source.SYSTEM_DEMO_SEED, now);
        return true;
    }

    /** 與 EmployeeAdminService 相同的主管規則：啟用中的 MANAGER／ADMIN、不是自己、不形成循環。 */
    private String managerProblem(Employee employee, Employee manager) {
        if (!manager.isEnabled() || manager.getRole() == Role.EMPLOYEE) {
            return "對方不是啟用中的主管或管理員";
        }
        Set<Long> visited = new HashSet<>();
        Employee cursor = manager;
        while (cursor != null && visited.add(cursor.getId())) {
            if (Objects.equals(cursor.getId(), employee.getId())) {
                return "會形成主管循環";
            }
            cursor = cursor.getManagerId() == null ? null : employeeRepository.findById(cursor.getManagerId()).orElse(null);
        }
        return null;
    }
}
