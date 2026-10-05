package com.renewflow.eip.organization;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findAllByOrderByCodeAsc();

    Optional<Department> findByCode(String code);

    /** 新增：代碼重複時不丟例外、回傳空（由 uq_department_code 裁決，交易仍然健康）。 */
    @Query(value = """
            insert into department (code, name, active, created_at, updated_at)
            values (:code, :name, true, :now, :now)
            on conflict (code) do nothing
            returning id
            """, nativeQuery = true)
    Optional<Long> insertIfCodeFree(@Param("code") String code, @Param("name") String name, @Param("now") Instant now);

    /** 版本相符才更新（名稱、啟用），同時 version + 1。代碼不在這裡（建立後不可修改）。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update department set name = :name, active = :active, version = version + 1, updated_at = :now
             where id = :id and version = :expectedVersion
            """, nativeQuery = true)
    int updateIfVersion(@Param("id") Long id, @Param("expectedVersion") long expectedVersion,
                        @Param("name") String name, @Param("active") boolean active, @Param("now") Instant now);

    @Query(value = "select count(*) from employee where department_id = :id and enabled", nativeQuery = true)
    long countActiveEmployees(@Param("id") Long id);

    /** 每個部門的啟用員工數：[departmentId, count]。 */
    @Query(value = "select department_id, count(*) from employee where enabled and department_id is not null group by department_id", nativeQuery = true)
    List<Object[]> activeEmployeeCounts();
}
