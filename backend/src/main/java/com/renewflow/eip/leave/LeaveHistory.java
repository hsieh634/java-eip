package com.renewflow.eip.leave;

import com.renewflow.eip.leave.LeaveEnums.HistoryAction;
import com.renewflow.eip.leave.LeaveEnums.LeaveStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** 對應 leave_history。只新增、不修改（資料庫觸發器也會擋 UPDATE／DELETE）。 */
@Entity
@Immutable
@Table(name = "leave_history")
public class LeaveHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "leave_request_id")
    private Long leaveRequestId;

    @Column(name = "subject_employee_id")
    private Long subjectEmployeeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private HistoryAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private LeaveStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 20)
    private LeaveStatus toStatus;

    @Column(name = "before_approver_id")
    private Long beforeApproverId;

    @Column(name = "after_approver_id")
    private Long afterApproverId;

    @Column(length = 500)
    private String reason;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name="decision_snapshot", columnDefinition="text") private String decisionSnapshot;

    public String getDecisionSnapshot() { return decisionSnapshot; }

    protected LeaveHistory() {
    }

    LeaveHistory(Long leaveRequestId, Long subjectEmployeeId, HistoryAction action, LeaveStatus fromStatus, LeaveStatus toStatus,
                 Long beforeApproverId, Long afterApproverId, String reason, Long actorId, Instant occurredAt) {
        this.leaveRequestId = leaveRequestId;
        this.subjectEmployeeId = subjectEmployeeId;
        this.action = action;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.beforeApproverId = beforeApproverId;
        this.afterApproverId = afterApproverId;
        this.reason = reason;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
    }

    LeaveHistory(Long leaveRequestId, HistoryAction action, LeaveStatus fromStatus, LeaveStatus toStatus,
                 String reason, Long actorId, Instant now, String snapshot) {
        this(leaveRequestId,null,action,fromStatus,toStatus,null,null,reason,actorId,now);
        this.decisionSnapshot=snapshot;
    }

    public Long getLeaveRequestId() { return leaveRequestId; }

    public Long getId() {
        return id;
    }

    public HistoryAction getAction() {
        return action;
    }

    public LeaveStatus getFromStatus() {
        return fromStatus;
    }

    public LeaveStatus getToStatus() {
        return toStatus;
    }

    public Long getBeforeApproverId() {
        return beforeApproverId;
    }

    public Long getAfterApproverId() {
        return afterApproverId;
    }

    public String getReason() {
        return reason;
    }

    public Long getActorId() {
        return actorId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
