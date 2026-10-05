package com.renewflow.eip.organization;

import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 組織管理共用的正規化規則與錯誤。格式與資料庫 CHECK 一致。 */
final class OrganizationRules {

    /** 部門代碼：去除前後空白、轉大寫後 2～32 字元（資料庫 ck_department_code 相同）。 */
    static final Pattern DEPARTMENT_CODE = Pattern.compile("^[A-Z0-9_-]{2,32}$");
    /** 員工編號：去除前後空白、轉大寫後 2～20 字元。 */
    static final Pattern EMPLOYEE_NO = Pattern.compile("^[A-Z0-9_-]{2,20}$");
    /** 帳號：與登入相同的正規化（去除前後空白、轉小寫）後 2～50 字元。 */
    static final Pattern USERNAME = Pattern.compile("^[a-z0-9._-]{2,50}$");
    static final int NAME_MAX = 100;
    static final int REASON_MAX = 500;

    private OrganizationRules() {
    }

    /** 縱深防禦：/api/admin/** 的 URL 規則已擋一次，Service 再確認一次（角色由 ActiveAccountFilter 確認是最新）。 */
    static void requireAdmin(EmployeeUserDetails actor) {
        if (actor == null || actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("administrator role required");
        }
    }

    static String normalizeDepartmentCode(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!DEPARTMENT_CODE.matcher(code).matches()) {
            throw invalidDepartment("部門代碼需為 2～32 個字元，只能使用英文字母、數字、底線（_）與連字號（-）");
        }
        return code;
    }

    static String normalizeDepartmentName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw invalidDepartment("部門名稱必填，去除前後空白後最多 " + NAME_MAX + " 個字");
        }
        return name;
    }

    static String normalizeEmployeeNo(String raw) {
        String no = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!EMPLOYEE_NO.matcher(no).matches()) {
            throw invalidEmployee("員工編號需為 2～20 個字元，只能使用英文字母、數字、底線（_）與連字號（-）");
        }
        return no;
    }

    /** 與 AuthService 登入時相同：去除前後空白、轉小寫。 */
    static String normalizeUsername(String raw) {
        String username = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(username).matches()) {
            throw invalidEmployee("帳號需為 2～50 個字元，只能使用英文字母、數字、點（.）、底線（_）與連字號（-）");
        }
        return username;
    }

    static String normalizeDisplayName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw invalidEmployee("顯示名稱必填，去除前後空白後最多 " + NAME_MAX + " 個字");
        }
        return name;
    }

    /** 原因：去除前後空白；空白視為沒有填。 */
    static String normalizeReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String reason = raw.trim();
        if (reason.length() > REASON_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "原因最多 " + REASON_MAX + " 個字");
        }
        return reason;
    }

    static void requireReason(String reason, String what) {
        if (reason == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "變更" + what + "時必須填寫原因");
        }
    }

    static ApiException invalidDepartment(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DEPARTMENT", message);
    }

    static ApiException invalidEmployee(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EMPLOYEE", message);
    }

    static ApiException noChange() {
        return new ApiException(HttpStatus.BAD_REQUEST, "NO_CHANGE", "內容與目前相同，沒有需要儲存的變更");
    }

    static ApiException departmentNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "DEPARTMENT_NOT_FOUND", "找不到這個部門");
    }

    static ApiException employeeNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND", "找不到這位員工");
    }

    static ApiException departmentInactive(String label) {
        return new ApiException(HttpStatus.CONFLICT, "DEPARTMENT_INACTIVE", "部門 " + label + " 已停用，不能指派給啟用中的員工");
    }

    static ApiException conflict(String code, String message, Map<String, Object> details) {
        return new ApiException(HttpStatus.CONFLICT, code, message, details);
    }
}
