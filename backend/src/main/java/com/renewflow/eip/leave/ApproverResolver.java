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
 *   <li>否則採已發布請假流程；來源失效同樣拒絕，不fallback。</li>
 *   <li>尚無發布流程才採原直屬主管預設。</li>
 *   <li>都沒有 → 拒絕，需要管理員設定。</li>
 * </ol>
 * 合格的審核人：啟用中的 MANAGER 或 ADMIN，而且不是申請人本人。只讀取組織資料，不修改。
 */
@Component
class ApproverResolver {

    /** approver 為 null 時 problem 一定有值。 */
    record Resolution(Employee approver, ApproverSource source, String problem, LeaveWorkflowDtos.Version workflow, LeaveWorkflowDtos.Rule rule) {
        boolean ok() {
            return approver != null;
        }
    }

    private final EmployeeRepository employeeRepository;
    private final LeaveApproverSettingRepository settingRepository;
    private final LeaveWorkflowRepository workflows;

    ApproverResolver(EmployeeRepository employeeRepository, LeaveApproverSettingRepository settingRepository, LeaveWorkflowRepository workflows) {
        this.employeeRepository = employeeRepository;
        this.settingRepository = settingRepository;
        this.workflows=workflows;
    }

    static boolean canApprove(Employee e) {
        return e != null && e.isEnabled() && e.getRole() != Role.EMPLOYEE;
    }

    Resolution resolve(Employee applicant) {
        return resolve(applicant,workflows.current());
    }

    /** 正式與草稿預覽共用；個人指定存在但失效時不得fallback。提交者已持有組織鎖。 */
    Resolution resolve(Employee applicant,LeaveWorkflowDtos.Version workflow) {
        Long designatedId = settingRepository.findById(applicant.getId()).map(LeaveApproverSetting::getApproverId).orElse(null);
        if (designatedId != null) {
            Employee designated = employeeRepository.findById(designatedId).orElse(null);
            if (!canApprove(designated) || Objects.equals(designatedId, applicant.getId())) {
                return new Resolution(null, ApproverSource.DESIGNATED,
                        "指定的請假審核人目前無效（停用、資格不符或自審），請管理員更新「指定請假審核人」後再送出",null,LeaveWorkflowDtos.Rule.DESIGNATED);
            }
            return new Resolution(designated, ApproverSource.DESIGNATED, null,null,LeaveWorkflowDtos.Rule.DESIGNATED);
        }
        if(workflow!=null) {
            Long target=workflow.values().source()==LeaveWorkflowDtos.Rule.MANAGER?applicant.getManagerId():workflow.values().designatedApproverId();
            Employee approver=target==null?null:employeeRepository.findById(target).orElse(null);
            if(!canApprove(approver) || Objects.equals(target,applicant.getId()))
                return new Resolution(null,ApproverSource.WORKFLOW,"流程審核人無效（無主管、停用、資格不符或自審），請管理員調整組織／流程或合法指定；不會自動換人",workflow,workflow.values().source());
            return new Resolution(approver,ApproverSource.WORKFLOW,null,workflow,workflow.values().source());
        }
        if (applicant.getManagerId() != null) {
            Employee manager = employeeRepository.findById(applicant.getManagerId()).orElse(null);
            if (!canApprove(manager) || Objects.equals(applicant.getManagerId(), applicant.getId())) {
                return new Resolution(null, ApproverSource.MANAGER, "直屬主管目前無法審核，請管理員設定指定請假審核人或調整主管",null,LeaveWorkflowDtos.Rule.MANAGER);
            }
            return new Resolution(manager, ApproverSource.MANAGER, null,null,LeaveWorkflowDtos.Rule.MANAGER);
        }
        return new Resolution(null, null, "你沒有直屬主管，也沒有指定的請假審核人，請管理員設定後再送出",null,LeaveWorkflowDtos.Rule.MANAGER);
    }

    static String snapshotSource(Resolution r) {
        return r.source()==ApproverSource.DESIGNATED?"PERSONAL":r.workflow()!=null?"WORKFLOW":"DEFAULT_MANAGER";
    }
    static LeaveWorkflowDtos.SubmissionSnapshot snapshot(Employee applicant,Resolution r,java.time.Instant now) {
        var v=r.workflow();
        return new LeaveWorkflowDtos.SubmissionSnapshot(snapshotSource(r),v==null?null:v.id(),v==null?null:v.number(),v==null?null:v.values().name(),
            "請假審核",r.rule(),LeaveViews.person(applicant),LeaveViews.person(r.approver()),now);
    }
}
