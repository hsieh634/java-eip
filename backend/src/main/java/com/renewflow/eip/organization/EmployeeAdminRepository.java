package com.renewflow.eip.organization;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 組織管理對 employee 的讀寫（與 identity 的 EmployeeRepository 是同一張表、不同用途）。
 * 寫入一律是條件式 SQL，並且只在組織鎖內呼叫。任何查詢都不把密碼雜湊帶到回應（由 DTO 決定）。
 */
public interface EmployeeAdminRepository extends JpaRepository<Employee, Long> {

    /**
     * 搜尋：顯示名稱、帳號、員工編號的部分比對（pattern 已轉小寫、以 ! 跳脫；沒有關鍵字時為 %）。
     * allStatuses 為 true 時不篩選啟用狀態，否則只取 enabled 相符者。參數都不是 null，避免 PostgreSQL 推不出 null 參數的型別。
     */
    @Query("""
            select e from Employee e
             where (lower(e.displayName) like :pattern escape '!'
                    or e.username like :pattern escape '!'
                    or lower(e.employeeNo) like :pattern escape '!')
               and (:allStatuses = true or e.enabled = :enabled)
            """)
    Page<Employee> search(@Param("pattern") String pattern, @Param("allStatuses") boolean allStatuses,
                          @Param("enabled") boolean enabled, Pageable pageable);

    List<Employee> findTop200ByEnabledTrueAndRoleInOrderByEmployeeNoAsc(Collection<Role> roles);

    Optional<Employee> findByEmployeeNo(String employeeNo);

    Optional<Employee> findByUsername(String username);

    /** 新增員工：任何唯一限制衝突（員工編號、帳號）都不丟例外、回傳空。 */
    @Query(value = """
            insert into employee (employee_no, username, display_name, password_hash, role, enabled,
                                  department_id, manager_id, created_at, updated_at)
            values (:employeeNo, :username, :displayName, :passwordHash, :role, true, :departmentId, :managerId, :now, :now)
            on conflict do nothing
            returning id
            """, nativeQuery = true)
    Optional<Long> insertIfUnique(@Param("employeeNo") String employeeNo,
                                  @Param("username") String username,
                                  @Param("displayName") String displayName,
                                  @Param("passwordHash") String passwordHash,
                                  @Param("role") String role,
                                  @Param("departmentId") Long departmentId,
                                  @Param("managerId") Long managerId,
                                  @Param("now") Instant now);

    /** 修改（不含員工編號、帳號、密碼）：版本相符才更新，同時 version + 1。回傳 0＝已被改過。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update employee
               set display_name = :displayName, role = :role, enabled = :enabled,
                   department_id = :departmentId, manager_id = :managerId,
                   version = version + 1, updated_at = :now
             where id = :id and version = :expectedVersion
            """, nativeQuery = true)
    int updateIfVersion(@Param("id") Long id,
                        @Param("expectedVersion") long expectedVersion,
                        @Param("displayName") String displayName,
                        @Param("role") String role,
                        @Param("enabled") boolean enabled,
                        @Param("departmentId") Long departmentId,
                        @Param("managerId") Long managerId,
                        @Param("now") Instant now);

    /** 有多少員工（不論是否啟用）把這個人設為直屬主管。 */
    @Query(value = "select count(*) from employee where manager_id = :id", nativeQuery = true)
    long countSubordinates(@Param("id") Long id);

    /** 指定員工各自的下屬數：[managerId, count]（沒有下屬的不會出現）。 */
    @Query(value = "select manager_id, count(*) from employee where manager_id in (:ids) group by manager_id", nativeQuery = true)
    List<Object[]> subordinateCountsFor(@Param("ids") Collection<Long> ids);

    /** 除了指定的人以外，還有幾位啟用中的 ADMIN。 */
    @Query(value = "select count(*) from employee where role = 'ADMIN' and enabled and id <> :id", nativeQuery = true)
    long countOtherActiveAdmins(@Param("id") Long id);
}
