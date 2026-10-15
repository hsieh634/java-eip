package com.renewflow.eip.leave;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;

/** 新內容永遠另建列，來源與去重指紋和本文同交易寫入。 */
@Repository
class LeaveRevisionRepository {
    private final JdbcTemplate jdbc;
    LeaveRevisionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    Long insert(LeaveRequest source, LeaveDtos.RevisionRequest body, LeaveRules.Submission s, int number,
                Long approver, String approverSource, String fingerprint, Instant now,Long workflowVersionId,String approvalSnapshot) {
        return jdbc.queryForObject("""
            insert into leave_request(employee_id,request_id,leave_type,work_date,start_time,end_time,minutes,reason,
              status,approver_id,approver_source,created_at,updated_at,document_id,revision_no,operation,
              source_request_id,source_version,base_effective_request_id,base_effective_version,fingerprint,workflow_version_id,approval_snapshot)
            values(?,?,?,?,?,?,?,?,'PENDING',?,?,?,?,?,?,?,?,?,?,?,?,?,?) returning id
            """,Long.class,source.getEmployeeId(),body.requestId(),s.leaveType().name(),s.workDate(),s.startTime(),s.endTime(),s.minutes(),s.reason(),
            approver,approverSource,Timestamp.from(now),Timestamp.from(now),source.getDocumentId(),number,body.operation().name(),source.getId(),
            body.expectedSourceVersion(),body.expectedEffectiveRequestId(),body.expectedEffectiveVersion(),fingerprint,workflowVersionId,approvalSnapshot);
    }
}
