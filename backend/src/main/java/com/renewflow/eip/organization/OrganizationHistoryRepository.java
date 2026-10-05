package com.renewflow.eip.organization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrganizationHistoryRepository extends JpaRepository<OrganizationHistory, Long> {

    List<OrganizationHistory> findTop100ByEmployeeIdOrderByIdDesc(Long employeeId);

    List<OrganizationHistory> findTop100ByDepartmentIdOrderByIdDesc(Long departmentId);

    /** dev 示範初始化用：這位員工是否曾被管理員調整過（有就整個人不動）。 */
    boolean existsByEmployeeIdAndSource(Long employeeId, String source);

    /** dev 示範初始化用：這個部門是不是由示範初始化建立的。 */
    boolean existsByDepartmentIdAndSourceAndAction(Long departmentId, String source, String action);
}
