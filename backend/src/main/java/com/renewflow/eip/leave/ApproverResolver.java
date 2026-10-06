package com.renewflow.eip.leave;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.EmployeeRepository;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.leave.LeaveEnums.ApproverSource;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 決定某位員工的請假審核人（送出時使用；預覽也用同一份規則）：
 * <ol>
 *   <li>有指定請假審核人 → 用它。它已無效時<b>拒絕</b>（不自動改用直屬主管，A-83）。</li>
 *   <li>否則採直屬主管。</li>
 *   <li>都沒有 → 拒絕，需要管理員設定。</li>
 * </ol>
 * 合格的審核人：啟用中的 MANAGER 或 ADMIN，而且不是申請人本人。只讀取組織資料，不修改。
 */
@Component
class ApproverResolver {

    /** approver 為 null 時 problem 一定有值。 */
    record Resolution(Employee approver, ApproverSource source, String problem) {
        boolean ok() {
            return approver != null;
        }
    }

    private final EmployeeRepository employeeRepository;
    private final LeaveApproverSettingRepository settingRepository;

    ApproverResolver(EmployeeRepository employeeRepository, LeaveApproverSettingRepository settingRepository) {
        this.employeeRepository = employeeRepository;
        this.settingRepository = settingRepository;
    }

    static boolean canApprove(Employee e) {
        return e != null && e.isEnabled() && e.getRole() != Role.EMPLOYEE;
    }

    Resolution resolve(Employee applicant) {
        Long designatedId = settingRepository.findById(applicant.getId()).map(LeaveApproverSetting::getApproverId).orElse(null);
        if (designatedId != null) {
            Employee designated = employeeRepository.findById(designatedId).orElse(null);
            if (!canApprove(designated) || Objects.equals(designatedId, applicant.getId())) {
                return new Resolution(null, ApproverSource.DESIGNATED,
                        "指定的請假審核人目前無效（已停用或不是主管／管理員），請管理員更新「指定請假審核人」後再送出");
            }
            return new Resolution(designated, ApproverSource.DESIGNATED, null);
        }
        if (applicant.getManagerId() != null) {
            Employee manager = employeeRepository.findById(applicant.getManagerId()).orElse(null);
            if (!canApprove(manager) || Objects.equals(applicant.getManagerId(), applicant.getId())) {
                return new Resolution(null, ApproverSource.MANAGER, "直屬主管目前無法審核，請管理員設定指定請假審核人或調整主管");
            }
            return new Resolution(manager, ApproverSource.MANAGER, null);
        }
        return new Resolution(null, null, "你沒有直屬主管，也沒有指定的請假審核人，請管理員設定後再送出");
    }
}
