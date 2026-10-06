package com.renewflow.eip.leave;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * 對應 leave_approver_setting：某位員工的「指定請假審核人」（優先於直屬主管）。
 * 只用來讀取；寫入走 {@link LeaveApproverSettingRepository} 的條件式 SQL。沒有列＝從未設定（版本視為 0）。
 */
@Entity
@Immutable
@Table(name = "leave_approver_setting")
public class LeaveApproverSetting {

    @Id
    @Column(name = "employee_id")
    private Long employeeId;

    /** null＝已移除指定，採直屬主管。 */
    @Column(name = "approver_id")
    private Long approverId;

    @Column(nullable = false)
    private long version;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LeaveApproverSetting() {
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public Long getApproverId() {
        return approverId;
    }

    public long getVersion() {
        return version;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
