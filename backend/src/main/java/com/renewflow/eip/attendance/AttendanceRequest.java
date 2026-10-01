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

/**
 * 對應 attendance_request，唯讀對映：建立與狀態轉移都走 Repository 的原子 SQL
 * （INSERT ... ON CONFLICT ... RETURNING、WHERE status = PENDING 的條件更新）。
 */
@Entity
@Immutable
@Table(name = "attendance_request")
public class AttendanceRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false)
    private AttendanceRequestType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttendanceRequestStatus status;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "target_attendance_id")
    private Long targetAttendanceId;

    @Column(name = "target_version")
    private Long targetVersion;

    @Column(name = "original_clock_in_at")
    private Instant originalClockInAt;

    @Column(name = "original_clock_out_at")
    private Instant originalClockOutAt;

    @Column(name = "requested_clock_in_at")
    private Instant requestedClockInAt;

    @Column(name = "requested_clock_out_at")
    private Instant requestedClockOutAt;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "reviewer_id")
    private Long reviewerId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_comment", length = 500)
    private String reviewComment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AttendanceRequest() {
    }

    public Long getId() {
        return id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public AttendanceRequestType getType() {
        return type;
    }

    public AttendanceRequestStatus getStatus() {
        return status;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public Long getTargetAttendanceId() {
        return targetAttendanceId;
    }

    public Long getTargetVersion() {
        return targetVersion;
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

    public String getReason() {
        return reason;
    }

    public Long getReviewerId() {
        return reviewerId;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
