package com.renewflow.eip.leave;

import com.renewflow.eip.identity.EmployeeRepository;
import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.organization.OrganizationLock;
import com.renewflow.eip.shared.time.BusinessTime;
import com.renewflow.eip.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import static com.renewflow.eip.leave.LeaveWorkflowDtos.*;

@Service
public class LeaveWorkflowService {
    private final LeaveWorkflowRepository repository;
    private final EmployeeRepository employees;
    private final ApproverResolver resolver;
    private final OrganizationLock lock;
    private final ObjectMapper mapper;
    private final Clock clock;
    LeaveWorkflowService(LeaveWorkflowRepository repository,EmployeeRepository employees,ApproverResolver resolver,OrganizationLock lock,ObjectMapper mapper,Clock clock) {
        this.repository=repository;this.employees=employees;this.resolver=resolver;this.lock=lock;this.mapper=mapper;this.clock=clock;
    }
    @Transactional
    public Settings settings(EmployeeUserDetails actor) {
        admin(actor);lock.acquireFor(actor);
        return new Settings(repository.current(),repository.draft(),repository.versions(),repository.history());
    }
    @Transactional(readOnly=true)
    public Operation operation(EmployeeUserDetails actor,UUID key) {
        admin(actor);var stored=repository.operation(key);
        if(stored==null) throw error(HttpStatus.NOT_FOUND,"WORKFLOW_OPERATION_NOT_FOUND","尚未查到此操作；可能仍在處理，請保留原識別碼查證");
        return stored.result();
    }
    @Transactional
    public Operation save(EmployeeUserDetails actor,Long draftId,DraftRequest body) {
        admin(actor);lock.acquireFor(actor);
        Values values=normalize(body.values());
        String action=draftId==null?"DRAFT_CREATED":"DRAFT_SAVED";
        String fingerprint=mapper.writeValueAsString(new Object[]{action,draftId,body.expectedPublishedId(),body.expectedVersion(),values});
        var prior=replay(actor,body.requestId(),fingerprint);if(prior!=null) return prior;
        Long current=repository.currentId();
        if(!Objects.equals(current,body.expectedPublishedId())) throw changed();
        validate(values);
        Version before=null,after;
        var now=BusinessTime.truncateToDatabasePrecision(clock.instant());
        if(draftId==null) {
            if(repository.draft()!=null) throw error(HttpStatus.CONFLICT,"WORKFLOW_DRAFT_EXISTS","已有草稿，請重新載入後編輯該草稿");
            after=repository.create(values,current,actor.getEmployeeId(),now);
        } else {
            before=requireDraft(draftId);
            if(body.expectedVersion()==null || before.rowVersion()!=body.expectedVersion() || !Objects.equals(before.basePublishedId(),current)) throw changed();
            if(repository.save(draftId,body.expectedVersion(),values)!=1) throw changed();
            after=repository.find(draftId);
        }
        repository.append(body.requestId(),action,fingerprint,before,after,current,current,actorPerson(actor),"儲存流程草稿",now);
        return new Operation(body.requestId(),action,after,false);
    }
    @Transactional
    public Operation publish(EmployeeUserDetails actor,long draftId,PublishRequest body) {
        admin(actor);lock.acquireFor(actor);
        String reason=LeaveRules.requireReason(body.reason(),"發布原因");
        String fingerprint=mapper.writeValueAsString(new Object[]{"PUBLISHED",draftId,body.expectedVersion(),reason});
        var prior=replay(actor,body.requestId(),fingerprint);if(prior!=null) return prior;
        var before=requireDraft(draftId);Long current=repository.currentId();
        if(before.rowVersion()!=body.expectedVersion() || !Objects.equals(before.basePublishedId(),current)) throw changed();
        validate(before.values()); // 發布與提交都在組織共同鎖內重驗，不能混合新舊組織／流程。
        var now=BusinessTime.truncateToDatabasePrecision(clock.instant());
        if(repository.publish(draftId,body.expectedVersion(),actor.getEmployeeId(),reason,now)!=1) throw changed();
        repository.activate(draftId);
        var after=repository.find(draftId);
        repository.append(body.requestId(),"PUBLISHED",fingerprint,before,after,current,draftId,actorPerson(actor),reason,now);
        return new Operation(body.requestId(),"PUBLISHED",after,false);
    }
    @Transactional
    public Preview preview(EmployeeUserDetails actor,long employeeId,Long draftId) {
        admin(actor);lock.acquireFor(actor);
        var employee=employees.findById(employeeId).orElseThrow(()->error(HttpStatus.NOT_FOUND,"EMPLOYEE_NOT_FOUND","找不到這位員工"));
        Version version=draftId==null?repository.current():requireDraft(draftId);
        var r=resolver.resolve(employee,version);
        return new Preview(LeaveViews.person(employee),ApproverResolver.snapshotSource(r),r.workflow()==null?null:r.workflow().id(),
            r.workflow()==null?null:r.workflow().number(),r.workflow()==null?null:r.workflow().values().name(),"請假審核",r.rule(),
            LeaveViews.person(r.approver()),r.source()==LeaveEnums.ApproverSource.DESIGNATED,
            !employee.isEnabled()?"申請人已停用，不能提交":r.problem());
    }
    private Operation replay(EmployeeUserDetails actor,UUID key,String fingerprint) {
        var p=repository.operation(key);if(p==null) return null;
        if(p.actorId()!=actor.getEmployeeId() || !p.fingerprint().equals(fingerprint))
            throw error(HttpStatus.CONFLICT,"WORKFLOW_REQUEST_CONFLICT","此操作識別碼已用於其他內容或操作者，原操作未改動");
        return p.result();
    }
    private Version requireDraft(long id) {
        var v=repository.find(id);
        if(v==null) throw error(HttpStatus.NOT_FOUND,"WORKFLOW_NOT_FOUND","找不到此流程版本");
        if(!"DRAFT".equals(v.status())) throw error(HttpStatus.CONFLICT,"WORKFLOW_NOT_DRAFT","此版本已發布，請重新載入並建立後續草稿");
        return v;
    }
    private Values normalize(Values v) {
        if(v==null || v.name()==null || v.name().trim().isEmpty() || v.name().trim().length()>100 || v.description()==null || v.description().trim().length()>1000 || v.source()==null ||
            (v.source()==Rule.MANAGER && v.designatedApproverId()!=null) || (v.source()==Rule.DESIGNATED && v.designatedApproverId()==null))
            throw error(HttpStatus.BAD_REQUEST,"INVALID_WORKFLOW","請填寫流程名稱、說明及合法的單關卡來源");
        return new Values(v.name().trim(),v.description().trim(),v.source(),v.designatedApproverId());
    }
    private void validate(Values v) {
        if(v.source()==Rule.DESIGNATED && !ApproverResolver.canApprove(employees.findById(v.designatedApproverId()).orElse(null)))
            throw error(HttpStatus.CONFLICT,"APPROVER_INVALID","指定人必須為啟用中的主管或管理員；不會自動換人");
    }
    private LeaveDtos.PersonRef actorPerson(EmployeeUserDetails actor) { return LeaveViews.person(employees.findById(actor.getEmployeeId()).orElseThrow()); }
    private static void admin(EmployeeUserDetails actor) { if(actor==null || actor.getRole()!=Role.ADMIN) throw new AccessDeniedException("administrator role required"); }
    private static ApiException changed() { return error(HttpStatus.CONFLICT,"WORKFLOW_CHANGED","草稿或目前發布版本已變更，請保留輸入並查詢最新版本後再操作"); }
    private static ApiException error(HttpStatus status,String code,String message) { return new ApiException(status,code,message); }
}
