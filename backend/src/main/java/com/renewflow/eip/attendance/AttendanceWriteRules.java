package com.renewflow.eip.attendance;

import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.organization.OrganizationLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** 所有出勤寫入在同一順序鎖下檢查跨列規則，版本條件更新仍作最後防線。 */
@Component
class AttendanceWriteRules {
    private final OrganizationLock organizationLock;
    private final JdbcTemplate jdbc;
    private final AttendanceRepository repository;
    AttendanceWriteRules(OrganizationLock organizationLock, JdbcTemplate jdbc, AttendanceRepository repository) {
        this.organizationLock = organizationLock; this.jdbc = jdbc; this.repository = repository;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Long employeeId) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof EmployeeUserDetails actor) organizationLock.acquireFor(actor);
        else organizationLock.acquire();
        // NO KEY UPDATE 與工時的 UPDATE 鎖互斥，但允許定位歷程引用操作者時的 KEY SHARE，避免設定鎖形成循環。
        if (jdbc.queryForList("select id from employee where id = ? for no key update", Long.class, employeeId).isEmpty())
            throw new AttendanceNotFoundException("EMPLOYEE_NOT_FOUND", "找不到這位員工");
    }
    public void validate(Long employeeId, LocalDate date, Long excludedId, Instant in, Instant out) {
        for (Attendance other : repository.findAllByEmployeeIdAndWorkDateAndVoidedAtIsNullOrderByClockInAtAscIdAsc(employeeId, date)) {
            if (Objects.equals(other.getId(), excludedId)) continue;
            Instant otherOut = other.getClockOutAt();
            boolean twoOpen = out == null && otherOut == null;
            boolean laterThanOpen = out == null && !other.getClockInAt().isBefore(in);
            boolean afterOtherOpen = otherOut == null && !in.isBefore(other.getClockInAt());
            boolean overlaps = (out == null || other.getClockInAt().isBefore(out)) && (otherOut == null || in.isBefore(otherOut));
            if (twoOpen || laterThanOpen || afterOtherOpen || overlaps)
                throw new AttendanceConflictException("ATTENDANCE_OVERLAP", "與其他有效時段重疊，或已有後續時段，不能建立或恢復這組未完成出勤");
        }
    }
}
