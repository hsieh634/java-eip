package com.renewflow.eip.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/** 對應 employee 資料表。Entity 不直接對外回傳，API 一律使用 DTO。 */
@Entity
@Table(name = "employee")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_no", nullable = false, length = 20)
    private String employeeNo;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    // 用 STRING 而不是 ORDINAL：調整列舉順序時不會讓既有資料悄悄變成別的角色
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // T05 前置：組織欄位。對 JPA 唯讀（insertable／updatable = false）：只由組織管理的條件式 SQL 修改，
    // 避免其他地方的 save（例如開發帳號重設密碼）用舊值覆寫部門、主管或版本
    @Column(name = "department_id", insertable = false, updatable = false)
    private Long departmentId;

    @Column(name = "manager_id", insertable = false, updatable = false)
    private Long managerId;

    @Column(name = "version", insertable = false, updatable = false)
    private Long version;

    /** JPA 規範需要無參數建構子；設為 protected 避免業務程式誤用。 */
    protected Employee() {
    }

    public Employee(String employeeNo, String username, String displayName, String passwordHash, Role role) {
        this.employeeNo = employeeNo;
        // 原因：資料庫 CHECK 要求 username 全小寫，在入口就正規化比等資料庫報錯友善
        this.username = username.toLowerCase(java.util.Locale.ROOT);
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEmployeeNo() {
        return employeeNo;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public Long getManagerId() {
        return managerId;
    }

    /** 組織管理的版本（新建、尚未重新讀取時可能是 null，代表資料庫預設值 0）。 */
    public long getVersion() {
        return version == null ? 0 : version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 只更新密碼雜湊（呼叫端負責先編碼）；員工 ID 與其他資料不變。僅供開發帳號維護流程使用。 */
    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
