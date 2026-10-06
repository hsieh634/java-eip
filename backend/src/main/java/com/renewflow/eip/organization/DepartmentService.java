package com.renewflow.eip.organization;

import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.organization.OrganizationAuditLog.Change;
import com.renewflow.eip.organization.OrganizationDtos.CreateDepartmentRequest;
import com.renewflow.eip.organization.OrganizationDtos.DepartmentResponse;
import com.renewflow.eip.organization.OrganizationDtos.OrganizationHistoryEntry;
import com.renewflow.eip.organization.OrganizationDtos.UpdateDepartmentRequest;
import com.renewflow.eip.organization.OrganizationHistory.Source;
import com.renewflow.eip.shared.time.BusinessTime;
import com.renewflow.eip.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.renewflow.eip.organization.OrganizationRules.departmentNotFound;
import static com.renewflow.eip.organization.OrganizationRules.noChange;
import static com.renewflow.eip.organization.OrganizationRules.requireAdmin;
import static com.renewflow.eip.organization.OrganizationRules.requireReason;

/**
 * 部門管理（僅 ADMIN）：建立、清單、改名、停用／重新啟用、歷程。沒有刪除。
 *
 * <p>每個寫入方法都在交易開頭取得 {@link OrganizationLock}，再讀取、檢查、寫入。
 * 「停用部門時沒有啟用中的員工」因此不會與「同時把員工指派到這個部門／重新啟用該部門的員工」互相錯過。
 */
@Service
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final OrganizationHistoryRepository historyRepository;
    private final OrganizationHistoryReader historyReader;
    private final OrganizationAuditLog auditLog;
    private final OrganizationLock lock;
    private final Clock clock;

    DepartmentService(DepartmentRepository departmentRepository, OrganizationHistoryRepository historyRepository,
                      OrganizationHistoryReader historyReader, OrganizationAuditLog auditLog, OrganizationLock lock, Clock clock) {
        this.departmentRepository = departmentRepository;
        this.historyRepository = historyRepository;
        this.historyReader = historyReader;
        this.auditLog = auditLog;
        this.lock = lock;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<DepartmentResponse> listAll(EmployeeUserDetails actor) {
        requireAdmin(actor);
        Map<Long, Long> counts = departmentRepository.activeEmployeeCounts().stream()
                .collect(Collectors.toMap(r -> ((Number) r[0]).longValue(), r -> ((Number) r[1]).longValue()));
        return departmentRepository.findAllByOrderByCodeAsc().stream()
                .map(d -> DepartmentResponse.of(d, counts.getOrDefault(d.getId(), 0L)))
                .toList();
    }

    @Transactional
    public DepartmentResponse create(EmployeeUserDetails actor, CreateDepartmentRequest body) {
        requireAdmin(actor);
        String code = OrganizationRules.normalizeDepartmentCode(body.code());
        String name = OrganizationRules.normalizeDepartmentName(body.name());
        lock.acquireFor(actor);
        Instant now = now();
        Long id = departmentRepository.insertIfCodeFree(code, name, now)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "DEPARTMENT_CODE_DUPLICATE", "部門代碼 " + code + " 已經存在"));
        auditLog.department(id, "DEPARTMENT_CREATED", null,
                List.of(new Change("code", null, code), new Change("name", null, name), new Change("active", null, true)),
                actor.getEmployeeId(), Source.ADMIN, now);
        return DepartmentResponse.of(departmentRepository.findById(id).orElseThrow(), 0);
    }

    /** 改名、停用、重新啟用。啟用狀態有變更時原因必填；停用時不得有啟用中的員工。版本不符 → 409。 */
    @Transactional
    public DepartmentResponse update(EmployeeUserDetails actor, Long id, UpdateDepartmentRequest body) {
        requireAdmin(actor);
        String name = OrganizationRules.normalizeDepartmentName(body.name());
        boolean active = body.active();
        String reason = OrganizationRules.normalizeReason(body.reason());

        lock.acquireFor(actor);
        Department current = departmentRepository.findById(id).orElseThrow(OrganizationRules::departmentNotFound);
        if (current.getVersion() != body.expectedVersion()) {
            throw changed();
        }
        List<Change> changes = new ArrayList<>();
        if (!current.getName().equals(name)) {
            changes.add(new Change("name", current.getName(), name));
        }
        if (current.isActive() != active) {
            changes.add(new Change("active", current.isActive(), active));
            requireReason(reason, "部門啟用狀態");
        }
        if (changes.isEmpty()) {
            throw noChange();
        }
        long activeEmployees = departmentRepository.countActiveEmployees(id);
        if (current.isActive() && !active && activeEmployees > 0) {
            throw OrganizationRules.conflict("DEPARTMENT_HAS_ACTIVE_EMPLOYEES",
                    "部門仍有 " + activeEmployees + " 位啟用中的員工，請先調整他們的部門或停用帳號",
                    Map.of("activeEmployeeCount", activeEmployees));
        }
        Instant now = now();
        if (departmentRepository.updateIfVersion(id, body.expectedVersion(), name, active, now) == 0) {
            throw changed();
        }
        auditLog.department(id, "DEPARTMENT_UPDATED", reason, changes, actor.getEmployeeId(), Source.ADMIN, now);
        return DepartmentResponse.of(departmentRepository.findById(id).orElseThrow(), activeEmployees);
    }

    @Transactional(readOnly = true)
    public List<OrganizationHistoryEntry> history(EmployeeUserDetails actor, Long id) {
        requireAdmin(actor);
        if (!departmentRepository.existsById(id)) {
            throw departmentNotFound();
        }
        return historyReader.toEntries(historyRepository.findTop100ByDepartmentIdOrderByIdDesc(id));
    }

    private Instant now() {
        return BusinessTime.truncateToDatabasePrecision(clock.instant());
    }

    private static ApiException changed() {
        return new ApiException(HttpStatus.CONFLICT, "DEPARTMENT_CHANGED", "部門已被其他管理員修改，請重新載入後再操作");
    }
}
