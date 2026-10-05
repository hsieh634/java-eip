package com.renewflow.eip.organization;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.LoginRequest;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.shared.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * 組織管理 DTO。Bean Validation 只擋「必填與長度上限」（→ 400 VALIDATION_FAILED）；
 * 正規化後的格式、「沒有變更」、原因必填等規則在 Service。
 * <b>任何回應都不含密碼或密碼雜湊</b>：回應 record 只從 Employee 取出明列的欄位。
 */
public final class OrganizationDtos {

    private OrganizationDtos() {
    }

    // ---------------------------------------------------------------- 部門

    public record CreateDepartmentRequest(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 200) String name) {
    }

    /** 修改名稱與啟用狀態；沒有 code（建立後不可修改）。啟用狀態有變更時 reason 必填。 */
    public record UpdateDepartmentRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull Boolean active,
            @NotNull Long expectedVersion,
            @Size(max = 500) String reason) {
    }

    public record DepartmentResponse(Long id, String code, String name, boolean active, long activeEmployeeCount, long version,
                                     Instant createdAt, Instant updatedAt) {
        static DepartmentResponse of(Department d, long activeEmployeeCount) {
            return new DepartmentResponse(d.getId(), d.getCode(), d.getName(), d.isActive(), activeEmployeeCount, d.getVersion(),
                    d.getCreatedAt(), d.getUpdatedAt());
        }
    }

    public record DepartmentListResponse(List<DepartmentResponse> departments) {
    }

    // ---------------------------------------------------------------- 員工

    /**
     * 建立員工。初始密碼沿用登入的規則：必填、最多 72 個 UTF-8 位元組；不 trim、不截斷。
     * 只會以 PasswordEncoder 雜湊後保存，不會出現在任何回應、日誌或歷程。
     */
    public record CreateEmployeeRequest(
            @NotBlank @Size(max = 40) String employeeNo,
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(max = 200) String displayName,
            @NotNull Role role,
            Long departmentId,
            Long managerId,
            @NotBlank @MaxUtf8Bytes(LoginRequest.PASSWORD_MAX_BYTES) String initialPassword,
            @Size(max = 500) String reason) {

        /** 避免初始密碼意外出現在日誌或例外訊息中。 */
        @Override
        public String toString() {
            return "CreateEmployeeRequest[employeeNo=" + employeeNo + ", username=" + username + ", role=" + role
                    + ", departmentId=" + departmentId + ", managerId=" + managerId + ", initialPassword=***]";
        }
    }

    /** 修改：沒有員工編號、帳號、密碼。departmentId／managerId 為 null＝未指派。 */
    public record UpdateEmployeeRequest(
            @NotBlank @Size(max = 200) String displayName,
            @NotNull Role role,
            @NotNull Boolean enabled,
            Long departmentId,
            Long managerId,
            @NotNull Long expectedVersion,
            @Size(max = 500) String reason) {
    }

    public record DepartmentRef(Long id, String code, String name, boolean active) {
        static DepartmentRef of(Department d) {
            return d == null ? null : new DepartmentRef(d.getId(), d.getCode(), d.getName(), d.isActive());
        }
    }

    public record EmployeeRef(Long id, String employeeNo, String displayName, Role role, boolean enabled) {
        static EmployeeRef of(Employee e) {
            return e == null ? null : new EmployeeRef(e.getId(), e.getEmployeeNo(), e.getDisplayName(), e.getRole(), e.isEnabled());
        }
    }

    public record EmployeeAdminResponse(Long id, String employeeNo, String username, String displayName, Role role, boolean enabled,
                                        DepartmentRef department, EmployeeRef manager, long subordinateCount, long version,
                                        Instant createdAt, Instant updatedAt) {
        static EmployeeAdminResponse of(Employee e, Department department, Employee manager, long subordinateCount) {
            return new EmployeeAdminResponse(e.getId(), e.getEmployeeNo(), e.getUsername(), e.getDisplayName(), e.getRole(), e.isEnabled(),
                    DepartmentRef.of(department), EmployeeRef.of(manager), subordinateCount, e.getVersion(),
                    e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    public record EmployeePageResponse(List<EmployeeAdminResponse> items, int page, int size, long total) {
    }

    public record ManagerOption(Long id, String employeeNo, String displayName, Role role) {
        static ManagerOption of(Employee e) {
            return new ManagerOption(e.getId(), e.getEmployeeNo(), e.getDisplayName(), e.getRole());
        }
    }

    public record ManagerOptionsResponse(List<ManagerOption> options) {
    }

    // ---------------------------------------------------------------- 歷程

    public record HistoryActor(Long id, String employeeNo, String displayName) {
    }

    public record OrganizationHistoryEntry(Long id, String action, String reason, JsonNode changes, HistoryActor actor,
                                           String source, Instant occurredAt) {
    }

    public record HistoryResponse(List<OrganizationHistoryEntry> entries) {
    }
}
