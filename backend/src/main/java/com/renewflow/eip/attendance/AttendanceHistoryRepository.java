package com.renewflow.eip.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/** 只提供新增與查詢；沒有任何修改／刪除歷程的方法。 */
public interface AttendanceHistoryRepository extends JpaRepository<AttendanceHistory, Long> {

    List<AttendanceHistory> findTop200ByEmployeeIdOrderByIdDesc(Long employeeId);

    List<AttendanceHistory> findByEmployeeIdAndWorkDateBetweenOrderByIdDesc(Long employeeId, LocalDate from, LocalDate to);
}
