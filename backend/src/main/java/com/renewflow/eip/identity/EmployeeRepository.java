package com.renewflow.eip.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Spring Data 會依方法名稱產生查詢：findByUsername → where username = ? */
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<Employee> findByEmployeeNo(String employeeNo);

    /** 每個已驗證請求都會呼叫一次：以主鍵查單列、只取兩個欄位。 */
    @Query("select new com.renewflow.eip.identity.AccountStatus(e.enabled, e.role) from Employee e where e.id = :id")
    Optional<AccountStatus> findAccountStatusById(@Param("id") Long id);
}
