package com.renewflow.eip.leave;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeaveHistoryRepository extends JpaRepository<LeaveHistory, Long> {

    @org.springframework.data.jpa.repository.Query(value="select h.* from leave_history h join leave_request r on r.id=h.leave_request_id where r.document_id=:documentId order by h.id",nativeQuery=true)
    List<LeaveHistory> findDocumentHistory(@org.springframework.data.repository.query.Param("documentId") Long documentId);

    List<LeaveHistory> findByLeaveRequestIdOrderByIdAsc(Long leaveRequestId);

    List<LeaveHistory> findTop100BySubjectEmployeeIdOrderByIdDesc(Long subjectEmployeeId);
}
