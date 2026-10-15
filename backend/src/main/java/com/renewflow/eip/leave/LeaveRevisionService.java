package com.renewflow.eip.leave;

import com.renewflow.eip.identity.EmployeeRepository;
import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.organization.OrganizationLock;
import com.renewflow.eip.shared.time.BusinessTime;
import com.renewflow.eip.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import static com.renewflow.eip.leave.LeaveEnums.*;
import static com.renewflow.eip.leave.LeaveDtos.*;

/** 後續版本、有效內容與審核證據；所有寫入沿用組織共同鎖及單一交易。 */
@Service
class LeaveRevisionService {
    record Prepared(LeaveDocumentRepository.Document before, com.renewflow.eip.attendance.AttendanceEvidenceService.Snapshot evidence,
                    boolean verified) {}
    private final LeaveRequestRepository requests;
    private final LeaveRevisionRepository revisions;
    private final LeaveDocumentRepository documents;
    private final EmployeeRepository employees;
    private final ApproverResolver approvers;
    private final LeaveAuditLog audit;
    private final LeaveViews views;
    private final OrganizationLock lock;
    private final Clock clock;
    private final ObjectMapper mapper;
    LeaveRevisionService(LeaveRequestRepository requests,LeaveRevisionRepository revisions,LeaveDocumentRepository documents,
                         EmployeeRepository employees,ApproverResolver approvers,LeaveAuditLog audit,LeaveViews views,
                         OrganizationLock lock,Clock clock,ObjectMapper mapper) {
        this.requests=requests;this.revisions=revisions;this.documents=documents;this.employees=employees;
        this.approvers=approvers;this.audit=audit;this.views=views;this.lock=lock;this.clock=clock;this.mapper=mapper;
    }
    @Transactional
    public RevisionSubmitResult submit(EmployeeUserDetails actor,Long sourceId,RevisionRequest body) {
        lock.acquireFor(actor);
        String fingerprint=fingerprint(sourceId,body);
        var prior=requests.findByEmployeeIdAndRequestId(actor.getEmployeeId(),body.requestId()).orElse(null);
        if(prior!=null) {
            if(!fingerprint.equals(prior.getFingerprint())) throw conflict("REQUEST_ID_CONFLICT","此識別碼已用於其他操作、來源快照或內容，原提交未改動");
            return result(true,prior); // 必須先確認原提交，不因終態或後來設定改動重新送審。
        }
        var source=requests.findByIdAndEmployeeId(sourceId,actor.getEmployeeId()).orElseThrow(LeaveRules::notFound);
        if(source.getVersion()!=body.expectedSourceVersion()) throw LeaveRules.changed();
        var doc=documents.find(source.getDocumentId());
        if(doc.effectiveVersion()!=body.expectedEffectiveVersion() || !Objects.equals(doc.effectiveRequestId(),body.expectedEffectiveRequestId()))
            throw conflict("EFFECTIVE_CHANGED","目前有效請假版本已變更，請重新取得原單");
        var all=requests.findByDocumentIdOrderByRevisionNoAsc(doc.id());
        var pending=all.stream().filter(r -> r.getStatus()==LeaveStatus.PENDING).findFirst().orElse(null);
        if(pending!=null && !pending.getId().equals(sourceId)) throw conflict("REVISION_OPEN","這筆業務假單已有待審版本，請先處理或撤回");
        if(body.operation()==Operation.CREATE) throw LeaveRules.invalid("後續版本不能改成新增另一筆假單");
        if(source.getStatus()==LeaveStatus.APPROVED) {
            if(!source.getId().equals(doc.effectiveRequestId())) throw inactive();
            if(body.operation()==Operation.REVISE) throw invalidRevision("已核准假單請申請變更或銷假");
        } else if(source.getStatus()==LeaveStatus.PENDING || source.getStatus()==LeaveStatus.REJECTED) {
            validateBase(source);
            if(!source.getId().equals(all.getLast().getId())) throw inactive();
            Operation expected=source.getOperation()==Operation.CREATE ? Operation.REVISE : source.getOperation();
            if(body.operation()!=expected) throw invalidRevision("修改或退回重送須保留原流程類型，其他動作請先結案原流程");
        } else throw inactive();
        if(body.operation()==Operation.REVISE && (doc.effectiveRequestId()!=null || doc.canceled())) throw inactive();
        if(body.operation()!=Operation.REVISE && doc.effectiveRequestId()==null) throw inactive();
        Instant now=BusinessTime.truncateToDatabasePrecision(clock.instant());
        var effective=doc.effectiveRequestId()==null ? null : requests.findById(doc.effectiveRequestId()).orElseThrow();
        var s=normalize(body,effective,now);
        checkOverlap(source.getEmployeeId(),doc.id(),s,body.operation());
        var applicant=employees.findById(actor.getEmployeeId()).orElseThrow();
        var resolution=approvers.resolve(applicant);
        if(!resolution.ok()) throw conflict("NO_VALID_APPROVER",resolution.problem());
        if(source.getStatus()==LeaveStatus.PENDING) {
            if(requests.decideIfPending(sourceId,body.expectedSourceVersion(),"WITHDRAWN",actor.getEmployeeId(),"已由新版本取代",now)!=1) throw LeaveRules.changed();
            audit.request(sourceId,HistoryAction.WITHDRAWN,LeaveStatus.PENDING,LeaveStatus.WITHDRAWN,null,null,"已由新版本取代",actor.getEmployeeId(),now);
        }
        Long id=revisions.insert(source,body,s,all.getLast().getRevisionNo()+1,resolution.approver().getId(),resolution.source().name(),fingerprint,now,
            resolution.workflow()==null?null:resolution.workflow().id(),mapper.writeValueAsString(ApproverResolver.snapshot(applicant,resolution,now)));
        audit.request(id,HistoryAction.SUBMITTED,null,LeaveStatus.PENDING,null,resolution.approver().getId(),null,actor.getEmployeeId(),now);
        return result(false,requests.findById(id).orElseThrow());
    }
    private LeaveRules.Submission normalize(RevisionRequest body,LeaveRequest effective,Instant now) {
        boolean cancel=body.operation()==Operation.CANCEL_ALL || body.operation()==Operation.CANCEL_PART;
        if(cancel && (effective==null || body.leaveType()!=effective.getLeaveType() || !body.workDate().equals(effective.getWorkDate())))
            throw invalidRevision("銷假不得更換日期或假別");
        var s=LeaveRules.normalize(body.leaveType(),body.workDate(),body.startTime(),body.endTime(),body.reason(),
            cancel ? body.workDate() : BusinessTime.workDateOf(now)); // 銷假可處理已過日期，不套一般日期窗口。
        if(body.operation()==Operation.CANCEL_ALL && (!s.startTime().equals(effective.getStartTime()) || !s.endTime().equals(effective.getEndTime())))
            throw invalidRevision("整段銷假須保留原完整時段，不使用零分鐘或清空時間");
        if(body.operation()==Operation.CANCEL_PART) validatePartial(effective,s);
        return s;
    }
    private void validatePartial(LeaveRequest original,LeaveRules.Submission s) {
        boolean sameStart=s.startTime().equals(original.getStartTime()),sameEnd=s.endTime().equals(original.getEndTime());
        if(s.startTime().isBefore(original.getStartTime()) || s.endTime().isAfter(original.getEndTime()) || sameStart==sameEnd)
            throw invalidRevision("部分銷假須只縮短開頭或結尾、保留非空真子集；不可增加時數或同時改兩側");
    }
    void validateBase(LeaveRequest r) {
        var d=documents.find(r.getDocumentId());
        if(d.effectiveVersion()!=r.getBaseEffectiveVersion() || !Objects.equals(d.effectiveRequestId(),r.getBaseEffectiveRequestId()))
            throw conflict("EFFECTIVE_CHANGED","原有效版本已被另一個核准結果取代，本次操作不會套到新內容");
    }
    Prepared prepareDecision(LeaveRequest r,String token,boolean approving) {
        validateBase(r);
        if(approving) {
            var s=new LeaveRules.Submission(r.getLeaveType(),r.getWorkDate(),r.getStartTime(),r.getEndTime(),r.getMinutes(),r.getReason());
            checkOverlap(r.getEmployeeId(),r.getDocumentId(),s,r.getOperation());
            if(r.getOperation()==Operation.CANCEL_PART) validatePartial(requests.findById(r.getBaseEffectiveRequestId()).orElseThrow(),s);
        }
        var snapshot=views.evidence(r,requests.findByDocumentIdOrderByRevisionNoAsc(r.getDocumentId()));
        boolean required=r.getOperation()==Operation.CHANGE || r.getOperation()==Operation.CANCEL_ALL || r.getOperation()==Operation.CANCEL_PART;
        if((required && token==null) || (token!=null && !token.equals(snapshot.token())))
            throw conflict("ATTENDANCE_EVIDENCE_CHANGED","出勤依據未確認或已變動，請重新載入詳情再審核");
        return new Prepared(documents.find(r.getDocumentId()),snapshot,token!=null);
    }
    String finishDecision(LeaveRequest r,Prepared prepared,boolean approved) {
        if(approved && documents.activate(r.getDocumentId(),r.getBaseEffectiveVersion(),r.getBaseEffectiveRequestId(),
            r.getOperation()==Operation.CANCEL_ALL ? null : r.getId(),r.getOperation()==Operation.CANCEL_ALL)!=1)
            throw conflict("EFFECTIVE_CHANGED","有效內容更新衝突，整個核准已回滾");
        var after=documents.find(r.getDocumentId()); var before=prepared.before();
        return mapper.writeValueAsString(new DecisionContext(prepared.verified(),before.effectiveRequestId(),before.effectiveVersion(),
            after.effectiveRequestId(),after.effectiveVersion(),prepared.evidence()));
    }
    private void checkOverlap(Long employee,Long doc,LeaveRules.Submission s,Operation operation) {
        if(operation==Operation.CANCEL_ALL) return;
        if(!requests.findOverlappingExcludingDocument(employee,doc,s.workDate(),s.startTime(),s.endTime()).isEmpty())
            throw conflict("LEAVE_OVERLAP","與其他業務假單的有效或待審預留時段重疊");
    }
    private RevisionSubmitResult result(boolean replayed,LeaveRequest r) {
        return new RevisionSubmitResult(replayed,r.getRequestId(),r.getOperation(),r.getSourceRequestId(),r.getSourceVersion(),
            r.getBaseEffectiveRequestId(),r.getBaseEffectiveVersion(),views.request(r));
    }
    private String fingerprint(Long source,RevisionRequest b) {
        String canonical=source+"|"+b.operation()+"|"+b.expectedSourceVersion()+"|"+b.expectedEffectiveRequestId()+"|"+b.expectedEffectiveVersion()+"|"+
            b.leaveType()+"|"+b.workDate()+"|"+b.startTime()+"|"+b.endTime()+"|"+LeaveRules.requireReason(b.reason(),"申請原因");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static ApiException inactive() { return conflict("SOURCE_INACTIVE","原選擇的版本已失效，請明確重新取得業務單目前內容；不會改成另一單或新增"); }
    private static ApiException invalidRevision(String text) { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REVISION",text); }
    private static ApiException conflict(String code,String text) { return new ApiException(HttpStatus.CONFLICT,code,text); }
}
