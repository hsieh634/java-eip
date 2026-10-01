package com.renewflow.eip.attendance;

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
import java.time.LocalDate;

/** 對應 attendance_history。只新增、不修改（資料庫觸發器也會擋 UPDATE／DELETE）。 */
@Entity
@Immutable
@Table(name = "attendance_history")
public class AttendanceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttendanceOperation operation;

    @Column(name = "request_id")
    private Long requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type")
    private AttendanceRequestType requestType;

    @Column(name = "attendance_id")
    private Long attendanceId;

    @Column(name = "target_version")
    private Long targetVersion;

    @Column(length = 500)
    private String reason;

    @Column(name = "original_clock_in_at")
    private Instant originalClockInAt;

    @Column(name = "original_clock_out_at")
    private Instant originalClockOutAt;

    @Column(name = "requested_clock_in_at")
    private Instant requestedClockInAt;

    @Column(name = "requested_clock_out_at")
    private Instant requestedClockOutAt;

    @Column(name = "after_clock_in_at")
    private Instant afterClockInAt;

    @Column(name = "after_clock_out_at")
    private Instant afterClockOutAt;

    @Column(name = "after_voided")
    private Boolean afterVoided;

    @Column(length = 20)
    private String result;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AttendanceHistory() {
    }

    AttendanceHistory(AuditEntry e) {
        this.employeeId = e.employeeId();
        this.workDate = e.workDate();
        this.operation = e.operation();
        this.requestId = e.requestId();
        this.requestType = e.requestType();
        this.attendanceId = e.attendanceId();
        this.targetVersion = e.targetVersion();
        this.reason = e.reason();
        this.originalClockInAt = e.originalIn();
        this.originalClockOutAt = e.originalOut();
        this.requestedClockInAt = e.requestedIn();
        this.requestedClockOutAt = e.requestedOut();
        this.afterClockInAt = e.afterIn();
        this.afterClockOutAt = e.afterOut();
        this.afterVoided = e.afterVoided();
        this.result = e.result();
        this.actorId = e.actorId();
        this.occurredAt = e.occurredAt();
    }

    public Long getId() {
        return id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public AttendanceOperation getOperation() {
        return operation;
    }

    public Long getRequestId() {
        return requestId;
    }

    public AttendanceRequestType getRequestType() {
        return requestType;
    }

    public Long getAttendanceId() {
        return attendanceId;
    }

    public Long getTargetVersion() {
        return targetVersion;
    }

    public String getReason() {
        return reason;
    }

    public Instant getOriginalClockInAt() {
        return originalClockInAt;
    }

    public Instant getOriginalClockOutAt() {
        return originalClockOutAt;
    }

    public Instant getRequestedClockInAt() {
        return requestedClockInAt;
    }

    public Instant getRequestedClockOutAt() {
        return requestedClockOutAt;
    }

    public Instant getAfterClockInAt() {
        return afterClockInAt;
    }

    public Instant getAfterClockOutAt() {
        return afterClockOutAt;
    }

    public Boolean getAfterVoided() {
        return afterVoided;
    }

    public String getResult() {
        return result;
    }

    public Long getActorId() {
        return actorId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
