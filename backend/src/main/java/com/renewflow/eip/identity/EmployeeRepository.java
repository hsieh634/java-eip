package com.renewflow.eip.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data 會依方法名稱產生查詢：findByUsername → where username = ? */
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByUsername(String username);

    boolean existsByUsername(String username);
}
