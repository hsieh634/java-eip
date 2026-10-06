package com.renewflow.eip.organization;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.organization.OrganizationAuditLog.Change;
import com.renewflow.eip.organization.OrganizationAuditLog.Ref;
import com.renewflow.eip.organization.OrganizationDtos.CreateEmployeeRequest;
import com.renewflow.eip.organization.OrganizationDtos.EmployeeAdminResponse;
import com.renewflow.eip.organization.OrganizationDtos.EmployeePageResponse;
import com.renewflow.eip.organization.OrganizationDtos.ManagerOption;
import com.renewflow.eip.organization.OrganizationDtos.OrganizationHistoryEntry;
import com.renewflow.eip.organization.OrganizationDtos.UpdateEmployeeRequest;
import com.renewflow.eip.organization.OrganizationHistory.Source;
import com.renewflow.eip.shared.time.BusinessTime;
import com.renewflow.eip.shared.web.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.renewflow.eip.organization.OrganizationRules.conflict;
import static com.renewflow.eip.organization.OrganizationRules.employeeNotFound;
import static com.renewflow.eip.organization.OrganizationRules.noChange;
import static com.renewflow.eip.organization.OrganizationRules.requireAdmin;
import static com.renewflow.eip.organization.OrganizationRules.requireReason;

/**
 * 員工管理（僅 ADMIN）：清單、建立、修改基本資料／角色／啟用／部門／直屬主管、歷程。沒有刪除，也沒有密碼修改。
 *
 * <p>不變條件（每個寫入都在 {@link OrganizationLock} 內「讀取 → 檢查 → 寫入」，所以並行時仍成立）：
 * <ol>
 *   <li>至少一位啟用中的 ADMIN；ADMIN 不能停用自己或把自己改成非 ADMIN。</li>
 *   <li>直屬主管必須是啟用中的 MANAGER 或 ADMIN、不是自己、不形成直接或間接循環。</li>
 *   <li>仍被任何員工設為直屬主管的人，不能被停用或改成 EMPLOYEE（不自動改派）。</li>
 *   <li>新指派的部門、以及重新啟用員工時所屬的部門，必須是啟用中的部門。</li>
 * </ol>
 * 版本號（expectedVersion）只負責擋「依過期畫面送出的表單」；跨多筆的規則靠鎖。
 */
@Service
public class EmployeeAdminService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    public enum StatusFilter { ALL, ACTIVE, INACTIVE }

    private final EmployeeAdminRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final OrganizationHistoryRepository historyRepository;
    private final OrganizationHistoryReader historyReader;
    private final OrganizationAuditLog auditLog;
    private final OrganizationLock lock;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    EmployeeAdminService(EmployeeAdminRepository employeeRepository, DepartmentRepository departmentRepository,
                         OrganizationHistoryRepository historyRepository, OrganizationHistoryReader historyReader,
                         OrganizationAuditLog auditLog, OrganizationLock lock, PasswordEncoder passwordEncoder, Clock clock) {
        this.employeeRepository = employeeRepository;
        this.departmentRepository = departmentRepository;
        this.historyRepository = historyRepository;
        this.historyReader = historyReader;
        this.auditLog = auditLog;
        this.lock = lock;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 讀取

    @Transactional(readOnly = true)
    public EmployeePageResponse search(EmployeeUserDetails actor, String q, StatusFilter status, Integer page, Integer size) {
        requireAdmin(actor);
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        StatusFilter filter = status == null ? StatusFilter.ALL : status;
        Page<Employee> result = employeeRepository.search(likePattern(q), filter == StatusFilter.ALL,
                filter != StatusFilter.INACTIVE, PageRequest.of(p, s, Sort.by("employeeNo").and(Sort.by("id"))));
        return new EmployeePageResponse(toResponses(result.getContent()), p, s, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public EmployeeAdminResponse get(EmployeeUserDetails actor, Long id) {
        requireAdmin(actor);
        return toResponse(employeeRepository.findById(id).orElseThrow(OrganizationRules::employeeNotFound));
    }

    @Transactional(readOnly = true)
    public List<ManagerOption> managerOptions(EmployeeUserDetails actor) {
        requireAdmin(actor);
        return employeeRepository.findTop200ByEnabledTrueAndRoleInOrderByEmployeeNoAsc(List.of(Role.MANAGER, Role.ADMIN))
                .stream().map(ManagerOption::of).toList();
    }

    @Transactional(readOnly = true)
    public List<OrganizationHistoryEntry> history(EmployeeUserDetails actor, Long id) {
        requireAdmin(actor);
        if (!employeeRepository.existsById(id)) {
            throw employeeNotFound();
        }
        return historyReader.toEntries(historyRepository.findTop100ByEmployeeIdOrderByIdDesc(id));
    }

    // ---------------------------------------------------------------- 建立

    /** 建立員工（一律啟用）。初始密碼只經 PasswordEncoder 雜湊後保存；歷程不記錄密碼。 */
    @Transactional
    public EmployeeAdminResponse create(EmployeeUserDetails actor, CreateEmployeeRequest body) {
        requireAdmin(actor);
        String employeeNo = OrganizationRules.normalizeEmployeeNo(body.employeeNo());
        String username = OrganizationRules.normalizeUsername(body.username());
        String displayName = OrganizationRules.normalizeDisplayName(body.displayName());
        String reason = OrganizationRules.normalizeReason(body.reason());
        Role role = body.role();

        lock.acquireFor(actor);
        Department department = body.departmentId() == null ? null : requireAssignableDepartment(body.departmentId());
        Employee manager = body.managerId() == null ? null : requireValidManager(body.managerId());
        Instant now = now();
        // 密碼原樣交給 PasswordEncoder（不 trim、不截斷；長度已由 @MaxUtf8Bytes 限制）
        Long id = employeeRepository.insertIfUnique(employeeNo, username, displayName, passwordEncoder.encode(body.initialPassword()),
                        role.name(), body.departmentId(), body.managerId(), now)
                .orElseThrow(() -> duplicate(employeeNo));
        List<Change> changes = new ArrayList<>();
        changes.add(new Change("employeeNo", null, employeeNo));
        changes.add(new Change("username", null, username));
        changes.add(new Change("displayName", null, displayName));
        changes.add(new Change("role", null, role.name()));
        changes.add(new Change("enabled", null, true));
        changes.add(new Change("department", null, ref(department)));
        changes.add(new Change("manager", null, ref(manager)));
        auditLog.employee(id, "EMPLOYEE_CREATED", reason, changes, actor.getEmployeeId(), Source.ADMIN, now);
        return toResponse(employeeRepository.findById(id).orElseThrow());
    }

    // ---------------------------------------------------------------- 修改

    @Transactional
    public EmployeeAdminResponse update(EmployeeUserDetails actor, Long id, UpdateEmployeeRequest body) {
        requireAdmin(actor);
        String displayName = OrganizationRules.normalizeDisplayName(body.displayName());
        String reason = OrganizationRules.normalizeReason(body.reason());
        Role role = body.role();
        boolean enabled = body.enabled();
        Long departmentId = body.departmentId();
        Long managerId = body.managerId();

        // 從這裡開始到交易結束，其他組織／請假寫入都在排隊；取鎖後先重新確認操作者仍有效（等待期間可能被停用或降級）
        lock.acquireFor(actor);
        Employee current = employeeRepository.findById(id).orElseThrow(OrganizationRules::employeeNotFound);
        if (current.getVersion() != body.expectedVersion()) {
            throw changed();
        }

        boolean roleChanged = current.getRole() != role;
        boolean enabledChanged = current.isEnabled() != enabled;
        boolean departmentChanged = !Objects.equals(current.getDepartmentId(), departmentId);
        boolean managerChanged = !Objects.equals(current.getManagerId(), managerId);
        boolean nameChanged = !current.getDisplayName().equals(displayName);
        if (!roleChanged && !enabledChanged && !departmentChanged && !managerChanged && !nameChanged) {
            throw noChange();
        }
        if (roleChanged || enabledChanged || departmentChanged || managerChanged) {
            requireReason(reason, "角色、啟用狀態、部門或主管");
        }

        // 1. 自己：可以改自己的名稱、部門、主管；不能停用自己或把自己改成非 ADMIN
        if (Objects.equals(actor.getEmployeeId(), id) && (!enabled || role != Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_ADMIN_CHANGE_FORBIDDEN", "不能停用自己的帳號，也不能移除自己的管理員角色");
        }

        // 2. 部門：新指派的必須啟用；重新啟用員工時，所屬部門也必須啟用
        Department oldDepartment = current.getDepartmentId() == null ? null
                : departmentRepository.findById(current.getDepartmentId()).orElse(null);
        Department newDepartment = oldDepartment;
        if (departmentChanged) {
            newDepartment = departmentId == null ? null : requireAssignableDepartment(departmentId);
        } else if (enabled && !current.isEnabled() && newDepartment != null && !newDepartment.isActive()) {
            throw OrganizationRules.departmentInactive(newDepartment.label());
        }

        // 3. 主管：只在有變更時檢查（未變更的主管由不變條件 3 保證仍有效）
        Employee oldManager = current.getManagerId() == null ? null : employeeRepository.findById(current.getManagerId()).orElse(null);
        Employee newManager = oldManager;
        if (managerChanged) {
            if (managerId != null && managerId.equals(id)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MANAGER_SELF", "不能把員工設為自己的主管");
            }
            newManager = managerId == null ? null : requireValidManager(managerId);
            if (newManager != null) {
                rejectCycle(id, newManager);
            }
        }

        // 4. 仍是別人的直屬主管 → 不能停用、不能改成 EMPLOYEE
        boolean wasEligibleManager = current.isEnabled() && current.getRole() != Role.EMPLOYEE;
        boolean staysEligibleManager = enabled && role != Role.EMPLOYEE;
        if (wasEligibleManager && !staysEligibleManager) {
            long subordinates = employeeRepository.countSubordinates(id);
            if (subordinates > 0) {
                throw conflict("MANAGER_HAS_SUBORDINATES",
                        "這位員工仍是 " + subordinates + " 位員工的直屬主管，請先調整他們的主管",
                        Map.of("subordinateCount", subordinates));
            }
        }

        // 5. 至少保留一位啟用中的 ADMIN。T05 起取鎖後會先確認操作者仍是啟用的 ADMIN（互相降級時，後到者在那一步就得到 401），
        //    這裡保留為第二道防線（例如操作者檢查被誤刪時）
        boolean wasActiveAdmin = current.isEnabled() && current.getRole() == Role.ADMIN;
        boolean staysActiveAdmin = enabled && role == Role.ADMIN;
        if (wasActiveAdmin && !staysActiveAdmin && employeeRepository.countOtherActiveAdmins(id) == 0) {
            throw conflict("LAST_ADMIN", "這是最後一位啟用中的管理員，不能停用或變更角色", Map.of());
        }

        Instant now = now();
        if (employeeRepository.updateIfVersion(id, body.expectedVersion(), displayName, role.name(), enabled,
                departmentId, managerId, now) == 0) {
            throw changed();
        }
        List<Change> changes = new ArrayList<>();
        if (nameChanged) changes.add(new Change("displayName", current.getDisplayName(), displayName));
        if (roleChanged) changes.add(new Change("role", current.getRole().name(), role.name()));
        if (enabledChanged) changes.add(new Change("enabled", current.isEnabled(), enabled));
        if (departmentChanged) changes.add(new Change("department", ref(oldDepartment), ref(newDepartment)));
        if (managerChanged) changes.add(new Change("manager", ref(oldManager), ref(newManager)));
        auditLog.employee(id, "EMPLOYEE_UPDATED", reason, changes, actor.getEmployeeId(), Source.ADMIN, now);
        return toResponse(employeeRepository.findById(id).orElseThrow());
    }

    // ---------------------------------------------------------------- 規則

    private Department requireAssignableDepartment(Long departmentId) {
        Department department = departmentRepository.findById(departmentId).orElseThrow(OrganizationRules::departmentNotFound);
        if (!department.isActive()) {
            throw OrganizationRules.departmentInactive(department.label());
        }
        return department;
    }

    private Employee requireValidManager(Long managerId) {
        Employee manager = employeeRepository.findById(managerId).orElseThrow(OrganizationRules::employeeNotFound);
        if (!manager.isEnabled() || manager.getRole() == Role.EMPLOYEE) {
            throw conflict("MANAGER_INVALID", "直屬主管必須是啟用中的主管或管理員", Map.of());
        }
        return manager;
    }

    /**
     * 從新主管開始沿著「主管的主管」往上走；走到目標員工本人＝會形成循環。
     * 鎖內執行，所以走訪期間主管鏈不會被別人改動。visited 只是防禦既有壞資料造成無限迴圈。
     */
    private void rejectCycle(Long employeeId, Employee proposedManager) {
        Set<Long> visited = new HashSet<>();
        Employee cursor = proposedManager;
        while (cursor != null && visited.add(cursor.getId())) {
            if (cursor.getId().equals(employeeId)) {
                throw conflict("MANAGER_CYCLE", "這樣設定會讓主管關係形成循環（例如 A 的主管是 B，B 的主管又是 A）", Map.of());
            }
            cursor = cursor.getManagerId() == null ? null : employeeRepository.findById(cursor.getManagerId()).orElse(null);
        }
    }

    private ApiException duplicate(String employeeNo) {
        if (employeeRepository.findByEmployeeNo(employeeNo).isPresent()) {
            return new ApiException(HttpStatus.CONFLICT, "EMPLOYEE_NO_DUPLICATE", "員工編號 " + employeeNo + " 已經存在");
        }
        return new ApiException(HttpStatus.CONFLICT, "USERNAME_DUPLICATE", "這個帳號已經有人使用");
    }

    // ---------------------------------------------------------------- 轉換

    private EmployeeAdminResponse toResponse(Employee e) {
        return toResponses(List.of(e)).getFirst();
    }

    private List<EmployeeAdminResponse> toResponses(List<Employee> employees) {
        if (employees.isEmpty()) {
            return List.of();
        }
        Map<Long, Department> departments = departmentRepository.findAllById(
                        employees.stream().map(Employee::getDepartmentId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Department::getId, Function.identity()));
        Map<Long, Employee> managers = employeeRepository.findAllById(
                        employees.stream().map(Employee::getManagerId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Employee::getId, Function.identity()));
        Map<Long, Long> subordinates = employeeRepository.subordinateCountsFor(employees.stream().map(Employee::getId).toList())
                .stream().collect(Collectors.toMap(r -> ((Number) r[0]).longValue(), r -> ((Number) r[1]).longValue()));
        return employees.stream().map(e -> EmployeeAdminResponse.of(e,
                e.getDepartmentId() == null ? null : departments.get(e.getDepartmentId()),
                e.getManagerId() == null ? null : managers.get(e.getManagerId()),
                subordinates.getOrDefault(e.getId(), 0L))).toList();
    }

    static Ref ref(Department d) {
        return d == null ? null : new Ref(d.getId(), d.label());
    }

    static Ref ref(Employee e) {
        return e == null ? null : new Ref(e.getId(), e.getEmployeeNo() + " " + e.getDisplayName());
    }

    /** 部分比對的 LIKE 樣式（轉小寫；跳脫 ! % _）。沒有關鍵字時比對全部。 */
    static String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return "%";
        }
        String escaped = q.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private Instant now() {
        return BusinessTime.truncateToDatabasePrecision(clock.instant());
    }

    private static ApiException changed() {
        return new ApiException(HttpStatus.CONFLICT, "EMPLOYEE_CHANGED", "員工資料已被其他管理員修改，請重新載入後再操作");
    }
}
