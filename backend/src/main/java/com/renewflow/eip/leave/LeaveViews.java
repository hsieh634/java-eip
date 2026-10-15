package com.renewflow.eip.leave;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.EmployeeRepository;
import com.renewflow.eip.leave.LeaveDtos.ApproverRef;
import com.renewflow.eip.leave.LeaveDtos.LeaveDetail;
import com.renewflow.eip.leave.LeaveDtos.LeaveHistoryEntry;
import com.renewflow.eip.leave.LeaveDtos.LeavePage;
import com.renewflow.eip.leave.LeaveDtos.LeaveRequestResponse;
import com.renewflow.eip.leave.LeaveDtos.PersonRef;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Entity → DTO：一次載入相關員工（申請人、審核人、決定者、操作者），不逐筆查詢。只讀。 */
@Component
class LeaveViews {

    private final EmployeeRepository employeeRepository;
    private final LeaveHistoryRepository historyRepository;
    private final LeaveRequestRepository requestRepository;
    private final LeaveDocumentRepository documents;
    private final com.renewflow.eip.attendance.AttendanceEvidenceService evidenceService;
    private final tools.jackson.databind.ObjectMapper mapper;

    LeaveViews(EmployeeRepository employeeRepository, LeaveHistoryRepository historyRepository,
               LeaveRequestRepository requestRepository, LeaveDocumentRepository documents,
               com.renewflow.eip.attendance.AttendanceEvidenceService evidenceService, tools.jackson.databind.ObjectMapper mapper) {
        this.employeeRepository = employeeRepository;
        this.historyRepository = historyRepository;
        this.requestRepository=requestRepository; this.documents=documents; this.evidenceService=evidenceService; this.mapper=mapper;
    }

    LeavePage page(Page<LeaveRequest> page) {
        var items=requests(page.getContent());
        return new LeavePage(items, page.getNumber(), page.getSize(), page.getTotalElements(),
            items.stream().filter(r -> r.revision().currentlyEffective()).mapToInt(LeaveRequestResponse::minutes).sum());
    }

    LeavePage approvalPage(Page<LeaveRequest> page) {
        var list=page.getContent();
        var grouped=requestRepository.findByDocumentIdInOrderByRevisionNoAsc(list.stream().map(LeaveRequest::getDocumentId).distinct().toList())
            .stream().collect(Collectors.groupingBy(LeaveRequest::getDocumentId));
        var scoped=list.stream().collect(Collectors.toMap(LeaveRequest::getId,r -> historicalDocument(r,visibleThrough(r,grouped.get(r.getDocumentId())))));
        // 不把各歷史截面相加，亦不讀全局有效合計來透露未授權後續狀態。
        return new LeavePage(requests(list,scoped,LeaveDtos.ViewMode.REVIEW_HISTORY),page.getNumber(),page.getSize(),page.getTotalElements(),null);
    }

    LeaveRequestResponse request(LeaveRequest r) {
        return requests(List.of(r)).getFirst();
    }

    /** 已由本人或管理入口檢查完整資料權限後使用。 */
    LeaveDetail detail(LeaveRequest r) {
        var rows=requestRepository.findByDocumentIdOrderByRevisionNoAsc(r.getDocumentId());
        return detail(r,rows,documents.find(r.getDocumentId()),LeaveDtos.ViewMode.FULL);
    }

    /** 即使操作者是ADMIN，審核入口也不借用完整管理權限。 */
    LeaveDetail approvalDetail(LeaveRequest r) {
        var rows=visibleThrough(r,requestRepository.findByDocumentIdOrderByRevisionNoAsc(r.getDocumentId()));
        return detail(r,rows,historicalDocument(r,rows),LeaveDtos.ViewMode.REVIEW_HISTORY);
    }

    private List<LeaveRequest> visibleThrough(LeaveRequest selected,List<LeaveRequest> rows) {
        return rows.stream().filter(v -> v.getRevisionNo()<=selected.getRevisionNo()).toList();
    }

    private LeaveDocumentRepository.Document historicalDocument(LeaveRequest selected,List<LeaveRequest> visible) {
        var approved=visible.stream().filter(v -> v.getStatus()==LeaveEnums.LeaveStatus.APPROVED).toList();
        var last=approved.isEmpty()?null:approved.getLast();
        boolean canceled=last!=null && last.getOperation()==LeaveEnums.Operation.CANCEL_ALL;
        return new LeaveDocumentRepository.Document(selected.getDocumentId(),selected.getEmployeeId(),
            last==null||canceled?null:last.getId(),approved.size(),canceled);
    }

    private LeaveDetail detail(LeaveRequest r,List<LeaveRequest> rows,LeaveDocumentRepository.Document doc,LeaveDtos.ViewMode mode) {
        var overrides=rows.stream().collect(Collectors.toMap(LeaveRequest::getId,v -> doc));
        var versions=requests(rows,overrides,mode);
        var origin=versions.stream().filter(v -> v.id().equals(doc.id())).findFirst().orElseThrow();
        var approved=versions.stream().filter(v -> v.status()==LeaveEnums.LeaveStatus.APPROVED).findFirst().orElse(null);
        var effective=versions.stream().filter(v -> v.id().equals(doc.effectiveRequestId())).findFirst().orElse(null);
        var pending=versions.stream().filter(v -> v.status()==LeaveEnums.LeaveStatus.PENDING).findFirst().orElse(null);
        var document=new LeaveDtos.DocumentResponse(doc.id(),doc.effectiveVersion(),doc.canceled(),origin,approved,effective,pending,versions,mode,
            mode==LeaveDtos.ViewMode.REVIEW_HISTORY?r.getRevisionNo():rows.getLast().getRevisionNo());
        var historyRows=mode==LeaveDtos.ViewMode.FULL?historyRepository.findDocumentHistory(doc.id()):
            historyRepository.findDocumentHistoryThroughRevision(doc.id(),r.getRevisionNo());
        var ids=rows.stream().map(LeaveRequest::getId).collect(Collectors.toSet());
        var dates=rows.stream().map(LeaveRequest::getWorkDate).collect(Collectors.toSet());
        return new LeaveDetail(versions.stream().filter(v -> v.id().equals(r.getId())).findFirst().orElseThrow(),
            history(historyRows,mode==LeaveDtos.ViewMode.FULL?null:ids,dates),document,evidence(r,rows,doc));
    }

    com.renewflow.eip.attendance.AttendanceEvidenceService.Snapshot evidence(LeaveRequest r,List<LeaveRequest> rows) {
        return evidence(r,rows,documents.find(r.getDocumentId())); // writer在共同鎖內先驗base，合法待審的當前依據。
    }

    private com.renewflow.eip.attendance.AttendanceEvidenceService.Snapshot evidence(LeaveRequest r,List<LeaveRequest> rows,LeaveDocumentRepository.Document doc) {
        var dates=new java.util.LinkedHashSet<java.time.LocalDate>(); dates.add(r.getWorkDate());
        rows.stream().filter(v -> v.getId().equals(doc.effectiveRequestId())).findFirst().ifPresent(v -> dates.add(v.getWorkDate()));
        rows.stream().filter(v -> v.getStatus()==LeaveEnums.LeaveStatus.APPROVED).findFirst().ifPresent(v -> dates.add(v.getWorkDate()));
        return evidenceService.forAuthorizedLeave(r.getEmployeeId(),dates);
    }

    List<LeaveRequestResponse> requests(List<LeaveRequest> list) {
        var docs=documents.findAll(list.stream().map(LeaveRequest::getDocumentId).distinct().toList());
        return requests(list,list.stream().collect(Collectors.toMap(LeaveRequest::getId,r -> docs.get(r.getDocumentId()))),LeaveDtos.ViewMode.FULL);
    }

    private List<LeaveRequestResponse> requests(List<LeaveRequest> list,Map<Long,LeaveDocumentRepository.Document> scoped,LeaveDtos.ViewMode mode) {
        Map<Long, Employee> people = load(list.stream().flatMap(r -> Stream.of(r.getEmployeeId(), r.getApproverId(), r.getDecidedBy(),r.getSubmittedApproverId())));
        return list.stream().map(r -> new LeaveRequestResponse(r.getId(), r.getRequestId(), r.getLeaveType(), r.getWorkDate(),
                r.getStartTime(), r.getEndTime(), r.getMinutes(), r.getReason(), r.getStatus(),
                person(people.get(r.getEmployeeId())), approver(people.get(r.getApproverId())), r.getApproverSource(),
                person(people.get(r.getDecidedBy())), r.getDecidedAt(), r.getDecisionReason(),
                r.getVersion(), r.getCreatedAt(), r.getUpdatedAt(),
                new LeaveDtos.RevisionInfo(r.getDocumentId(),r.getRevisionNo(),r.getOperation(),r.getSourceRequestId(),r.getSourceVersion(),
                    r.getBaseEffectiveRequestId(),r.getBaseEffectiveVersion(),scoped.get(r.getId()).effectiveVersion(),
                    r.getId().equals(scoped.get(r.getId()).effectiveRequestId()),scoped.get(r.getId()).canceled(),
                    person(people.get(r.getSubmittedApproverId())),r.getSubmittedApproverSource(),mode),
                r.getApprovalSnapshot()==null?null:mapper.readValue(r.getApprovalSnapshot(),LeaveWorkflowDtos.SubmissionSnapshot.class))).toList();
    }

    List<LeaveHistoryEntry> history(List<LeaveHistory> rows) {return history(rows,null,java.util.Set.of());}

    private List<LeaveHistoryEntry> history(List<LeaveHistory> rows,java.util.Set<Long> ids,java.util.Set<java.time.LocalDate> dates) {
        Map<Long, Employee> people=load(rows.stream().flatMap(h -> Stream.of(h.getBeforeApproverId(),h.getAfterApproverId(),h.getActorId())));
        return rows.stream().map(h -> {
            var decision=h.getDecisionSnapshot()==null?null:mapper.readValue(h.getDecisionSnapshot(),LeaveDtos.DecisionContext.class);
            boolean restricted=ids!=null && decision!=null && !withinScope(decision,ids,dates);
            return new LeaveHistoryEntry(h.getId(),h.getAction(),h.getFromStatus(),h.getToStatus(),person(people.get(h.getBeforeApproverId())),
                person(people.get(h.getAfterApproverId())),h.getReason(),person(people.get(h.getActorId())),h.getOccurredAt(),
                restricted?null:decision,h.getLeaveRequestId(),restricted);
        }).toList();
    }

    private boolean withinScope(LeaveDtos.DecisionContext d,java.util.Set<Long> ids,java.util.Set<java.time.LocalDate> dates) {
        if((d.beforeEffectiveRequestId()!=null && !ids.contains(d.beforeEffectiveRequestId())) ||
           (d.afterEffectiveRequestId()!=null && !ids.contains(d.afterEffectiveRequestId()))) return false;
        var a=d.attendance();
        return a!=null && dates.containsAll(a.dates()) && a.records().stream().allMatch(r -> dates.contains(r.workDate())) &&
            a.events().stream().allMatch(e -> dates.contains(e.workDate()));
    }

    Map<Long, Employee> load(Stream<Long> ids) {
        Collection<Long> distinct = ids.filter(Objects::nonNull).distinct().toList();
        return employeeRepository.findAllById(distinct).stream().collect(Collectors.toMap(Employee::getId, Function.identity()));
    }

    static PersonRef person(Employee e) {
        return e == null ? null : new PersonRef(e.getId(), e.getEmployeeNo(), e.getDisplayName());
    }

    static ApproverRef approver(Employee e) {
        return e == null ? null : new ApproverRef(e.getId(), e.getEmployeeNo(), e.getDisplayName(), ApproverResolver.canApprove(e));
    }
}
