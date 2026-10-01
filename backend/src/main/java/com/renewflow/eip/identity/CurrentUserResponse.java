package com.renewflow.eip.identity;

/** 回給前端的「目前使用者」。刻意不含 passwordHash、enabled、時間戳等前端用不到的欄位。 */
public record CurrentUserResponse(
        Long id,
        String employeeNo,
        String username,
        String displayName,
        Role role) {

    public static CurrentUserResponse from(Employee employee) {
        return new CurrentUserResponse(
                employee.getId(),
                employee.getEmployeeNo(),
                employee.getUsername(),
                employee.getDisplayName(),
                employee.getRole());
    }
}
