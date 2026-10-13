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

    LeaveRequestResponse request(LeaveRequest r) {
        return requests(List.of(r)).getFirst();
    }

    LeaveDetail detail(LeaveRequest r) {
        var rows=requestRepository.findByDocumentIdOrderByRevisionNoAsc(r.getDocumentId());
        var versions=requests(rows); var doc=documents.find(r.getDocumentId());
        var origin=versions.stream().filter(v -> v.id().equals(doc.id())).findFirst().orElseThrow();
        var approved=versions.stream().filter(v -> v.status()==LeaveEnums.LeaveStatus.APPROVED).findFirst().orElse(null);
        var effective=versions.stream().filter(v -> v.id().equals(doc.effectiveRequestId())).findFirst().orElse(null);
        var pending=versions.stream().filter(v -> v.status()==LeaveEnums.LeaveStatus.PENDING).findFirst().orElse(null);
        var document=new LeaveDtos.DocumentResponse(doc.id(),doc.effectiveVersion(),doc.canceled(),origin,approved,effective,pending,versions);
        return new LeaveDetail(request(r), history(historyRepository.findDocumentHistory(doc.id())),document,evidence(r,rows));
    }

    com.renewflow.eip.attendance.AttendanceEvidenceService.Snapshot evidence(LeaveRequest r,List<LeaveRequest> rows) {
        var dates=new java.util.LinkedHashSet<java.time.LocalDate>(); dates.add(r.getWorkDate());
        var doc=documents.find(r.getDocumentId());
        rows.stream().filter(v -> v.getId().equals(doc.effectiveRequestId())).findFirst().ifPresent(v -> dates.add(v.getWorkDate()));
        rows.stream().filter(v -> v.getStatus()==LeaveEnums.LeaveStatus.APPROVED).findFirst().ifPresent(v -> dates.add(v.getWorkDate()));
        return evidenceService.forAuthorizedLeave(r.getEmployeeId(),dates);
    }

    List<LeaveRequestResponse> requests(List<LeaveRequest> list) {
        Map<Long, Employee> people = load(list.stream().flatMap(r -> Stream.of(r.getEmployeeId(), r.getApproverId(), r.getDecidedBy(),r.getSubmittedApproverId())));
        var docs=documents.findAll(list.stream().map(LeaveRequest::getDocumentId).distinct().toList());
        return list.stream().map(r -> new LeaveRequestResponse(r.getId(), r.getRequestId(), r.getLeaveType(), r.getWorkDate(),
                r.getStartTime(), r.getEndTime(), r.getMinutes(), r.getReason(), r.getStatus(),
                person(people.get(r.getEmployeeId())), approver(people.get(r.getApproverId())), r.getApproverSource(),
                person(people.get(r.getDecidedBy())), r.getDecidedAt(), r.getDecisionReason(),
                r.getVersion(), r.getCreatedAt(), r.getUpdatedAt(),
                new LeaveDtos.RevisionInfo(r.getDocumentId(),r.getRevisionNo(),r.getOperation(),r.getSourceRequestId(),r.getSourceVersion(),
                    r.getBaseEffectiveRequestId(),r.getBaseEffectiveVersion(),docs.get(r.getDocumentId()).effectiveVersion(),
                    r.getId().equals(docs.get(r.getDocumentId()).effectiveRequestId()),docs.get(r.getDocumentId()).canceled(),
                    person(people.get(r.getSubmittedApproverId())),r.getSubmittedApproverSource()))).toList();
    }

    List<LeaveHistoryEntry> history(List<LeaveHistory> rows) {
        Map<Long, Employee> people = load(rows.stream().flatMap(h -> Stream.of(h.getBeforeApproverId(), h.getAfterApproverId(), h.getActorId())));
        return rows.stream().map(h -> new LeaveHistoryEntry(h.getId(), h.getAction(), h.getFromStatus(), h.getToStatus(),
                person(people.get(h.getBeforeApproverId())), person(people.get(h.getAfterApproverId())), h.getReason(),
                person(people.get(h.getActorId())), h.getOccurredAt(),h.getDecisionSnapshot()==null ? null :
                    mapper.readValue(h.getDecisionSnapshot(),LeaveDtos.DecisionContext.class),h.getLeaveRequestId())).toList();
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
