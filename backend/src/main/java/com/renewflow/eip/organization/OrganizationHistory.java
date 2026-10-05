package com.renewflow.eip.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** 對應 organization_history。只新增、不修改（資料庫觸發器也會擋 UPDATE／DELETE）。不含任何密碼資料。 */
@Entity
@Immutable
@Table(name = "organization_history")
public class OrganizationHistory {

    public enum Source { ADMIN, SYSTEM_DEMO_SEED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_type", nullable = false, length = 20)
    private String targetType;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(length = 500)
    private String reason;

    /** 前後值（JSON 陣列文字）。 */
    @Column(nullable = false, columnDefinition = "text")
    private String changes;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(nullable = false, length = 30)
    private String source;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected OrganizationHistory() {
    }

    OrganizationHistory(String targetType, Long employeeId, Long departmentId, String action, String reason,
                        String changes, Long actorId, Source source, Instant occurredAt) {
        this.targetType = targetType;
        this.employeeId = employeeId;
        this.departmentId = departmentId;
        this.action = action;
        this.reason = reason;
        this.changes = changes;
        this.actorId = actorId;
        this.source = source.name();
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public String getChanges() {
        return changes;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getSource() {
        return source;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
