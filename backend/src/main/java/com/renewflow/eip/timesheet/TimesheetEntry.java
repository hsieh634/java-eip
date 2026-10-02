package com.renewflow.eip.timesheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 對應 timesheet_entry：一筆個人工時。只用來讀取；新增與修改走 {@link TimesheetEntryRepository} 的原生條件式 SQL。
 * 關聯以外鍵 ID 表示（employeeId、projectId），不使用 @ManyToOne：本階段只需要 ID，
 * 顯示專案名稱時另外批次查詢，避免延遲載入與 N+1 查詢的問題。
 */
@Entity
@Immutable
@Table(name = "timesheet_entry")
public class TimesheetEntry {

    @Id
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(nullable = false, length = 1000)
    private String description;

    /** 整數分鐘（15～1440、15 的倍數）。畫面上的「1.5 小時」只在前端換算，資料一律是 90 分鐘。 */
    @Column(nullable = false)
    private int minutes;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TimesheetEntry() {
    }

    public Long getId() {
        return id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public String getDescription() {
        return description;
    }

    public int getMinutes() {
        return minutes;
    }

    public UUID getRequestId() {
        return requestId;
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
}
