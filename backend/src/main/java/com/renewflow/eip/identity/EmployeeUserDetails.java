package com.renewflow.eip.identity;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security 認得的「登入者」。它會被放進 Session，所以只保留必要欄位，
 * 並在驗證完成後清掉密碼雜湊（CredentialsContainer），避免雜湊跟著 Session 長期留在記憶體。
 * 這是 Session 內的快照；需要最新資料時（例如 /me）要回資料庫查。
 */
public class EmployeeUserDetails implements UserDetails, CredentialsContainer {

    private final Long employeeId;
    private final String username;
    private final Role role;
    private final boolean enabled;
    private String passwordHash;

    public EmployeeUserDetails(Employee employee) {
        this.employeeId = employee.getId();
        this.username = employee.getUsername();
        this.role = employee.getRole();
        this.enabled = employee.isEnabled();
        this.passwordHash = employee.getPasswordHash();
    }

    /** 之後的功能要用「登入身份」取得員工 ID，而不是相信前端傳來的 employeeId。 */
    public Long getEmployeeId() {
        return employeeId;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }
}
