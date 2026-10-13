package com.renewflow.eip.leave;

import com.renewflow.eip.leave.LeaveEnums.ApproverSource;
import com.renewflow.eip.leave.LeaveEnums.LeaveStatus;
import com.renewflow.eip.leave.LeaveEnums.LeaveType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** 對應 leave_request。只用來讀取；新增與狀態轉移走 {@link LeaveRequestRepository} 的條件式 SQL。 */
@Entity
@Immutable
@Table(name = "leave_request")
public class LeaveRequest {

    @Id
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 20)
    private LeaveType leaveType;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private int minutes;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeaveStatus status;

    @Column(name = "approver_id", nullable = false)
    private Long approverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "approver_source", nullable = false, length = 20)
    private ApproverSource approverSource;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name="document_id") private Long documentId;
    @Column(name="revision_no") private int revisionNo;
    @Enumerated(EnumType.STRING) @Column(name="operation") private LeaveEnums.Operation operation;
    @Column(name="source_request_id") private Long sourceRequestId;
    @Column(name="source_version") private Long sourceVersion;
    @Column(name="base_effective_request_id") private Long baseEffectiveRequestId;
    @Column(name="base_effective_version") private long baseEffectiveVersion;
    @Column(name="fingerprint") private String fingerprint;
    @Column(name="submitted_approver_id") private Long submittedApproverId;
    @Column(name="submitted_approver_source") private String submittedApproverSource;
    public Long getDocumentId() { return documentId; }
    public int getRevisionNo() { return revisionNo; }
    public LeaveEnums.Operation getOperation() { return operation; }
    public Long getSourceRequestId() { return sourceRequestId; }
    public Long getSourceVersion() { return sourceVersion; }
    public Long getBaseEffectiveRequestId() { return baseEffectiveRequestId; }
    public long getBaseEffectiveVersion() { return baseEffectiveVersion; }
    public String getFingerprint() { return fingerprint; }
    public Long getSubmittedApproverId() { return submittedApproverId; }
    public String getSubmittedApproverSource() { return submittedApproverSource; }

    protected LeaveRequest() {
    }

    public Long getId() {
        return id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public LeaveType getLeaveType() {
        return leaveType;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public int getMinutes() {
        return minutes;
    }

    public String getReason() {
        return reason;
    }

    public LeaveStatus getStatus() {
        return status;
    }

    public Long getApproverId() {
        return approverId;
    }

    public ApproverSource getApproverSource() {
        return approverSource;
    }

    public Long getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 重送比對：與一份正規化後的提交內容是否完全相同。 */
    boolean sameContentAs(LeaveRules.Submission s) {
        return leaveType == s.leaveType() && workDate.equals(s.workDate()) && startTime.equals(s.startTime())
                && endTime.equals(s.endTime()) && reason.equals(s.reason());
    }
}
