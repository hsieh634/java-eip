package com.renewflow.eip.organization;

import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.organization.EmployeeAdminService.StatusFilter;
import com.renewflow.eip.organization.OrganizationDtos.CreateDepartmentRequest;
import com.renewflow.eip.organization.OrganizationDtos.CreateEmployeeRequest;
import com.renewflow.eip.organization.OrganizationDtos.DepartmentListResponse;
import com.renewflow.eip.organization.OrganizationDtos.DepartmentResponse;
import com.renewflow.eip.organization.OrganizationDtos.EmployeeAdminResponse;
import com.renewflow.eip.organization.OrganizationDtos.EmployeePageResponse;
import com.renewflow.eip.organization.OrganizationDtos.HistoryResponse;
import com.renewflow.eip.organization.OrganizationDtos.ManagerOptionsResponse;
import com.renewflow.eip.organization.OrganizationDtos.UpdateDepartmentRequest;
import com.renewflow.eip.organization.OrganizationDtos.UpdateEmployeeRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 組織與員工管理的 HTTP 邊界。全部在 /api/admin/**：URL 規則要求 ROLE_ADMIN，Service 內再檢查一次。
 * 回應只有 DTO，不含密碼或密碼雜湊。
 */
@RestController
public class OrganizationController {

    private final DepartmentService departmentService;
    private final EmployeeAdminService employeeAdminService;

    public OrganizationController(DepartmentService departmentService, EmployeeAdminService employeeAdminService) {
        this.departmentService = departmentService;
        this.employeeAdminService = employeeAdminService;
    }

    // ---------------------------------------------------------------- 部門

    @GetMapping("/api/admin/departments")
    public DepartmentListResponse departments(@AuthenticationPrincipal EmployeeUserDetails actor) {
        return new DepartmentListResponse(departmentService.listAll(actor));
    }

    @PostMapping("/api/admin/departments")
    @ResponseStatus(HttpStatus.CREATED)
    public DepartmentResponse createDepartment(@AuthenticationPrincipal EmployeeUserDetails actor,
                                               @Valid @RequestBody CreateDepartmentRequest body) {
        return departmentService.create(actor, body);
    }

    @PutMapping("/api/admin/departments/{id}")
    public DepartmentResponse updateDepartment(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long id,
                                               @Valid @RequestBody UpdateDepartmentRequest body) {
        return departmentService.update(actor, id, body);
    }

    @GetMapping("/api/admin/departments/{id}/history")
    public HistoryResponse departmentHistory(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long id) {
        return new HistoryResponse(departmentService.history(actor, id));
    }

    // ---------------------------------------------------------------- 員工

    @GetMapping("/api/admin/employees")
    public EmployeePageResponse employees(@AuthenticationPrincipal EmployeeUserDetails actor,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) StatusFilter status,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        return employeeAdminService.search(actor, q, status, page, size);
    }

    @GetMapping("/api/admin/employees/manager-options")
    public ManagerOptionsResponse managerOptions(@AuthenticationPrincipal EmployeeUserDetails actor) {
        return new ManagerOptionsResponse(employeeAdminService.managerOptions(actor));
    }

    @GetMapping("/api/admin/employees/{id}")
    public EmployeeAdminResponse employee(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long id) {
        return employeeAdminService.get(actor, id);
    }

    @PostMapping("/api/admin/employees")
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeAdminResponse createEmployee(@AuthenticationPrincipal EmployeeUserDetails actor,
                                                @Valid @RequestBody CreateEmployeeRequest body) {
        return employeeAdminService.create(actor, body);
    }

    @PutMapping("/api/admin/employees/{id}")
    public EmployeeAdminResponse updateEmployee(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long id,
                                                @Valid @RequestBody UpdateEmployeeRequest body) {
        return employeeAdminService.update(actor, id, body);
    }

    @GetMapping("/api/admin/employees/{id}/history")
    public HistoryResponse employeeHistory(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long id) {
        return new HistoryResponse(employeeAdminService.history(actor, id));
    }
}
