package com.renewflow.eip.leave;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeaveHistoryRepository extends JpaRepository<LeaveHistory, Long> {

    List<LeaveHistory> findByLeaveRequestIdOrderByIdAsc(Long leaveRequestId);

    List<LeaveHistory> findTop100BySubjectEmployeeIdOrderByIdDesc(Long subjectEmployeeId);
}
