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

    LeaveViews(EmployeeRepository employeeRepository, LeaveHistoryRepository historyRepository) {
        this.employeeRepository = employeeRepository;
        this.historyRepository = historyRepository;
    }

    LeavePage page(Page<LeaveRequest> page) {
        return new LeavePage(requests(page.getContent()), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    LeaveRequestResponse request(LeaveRequest r) {
        return requests(List.of(r)).getFirst();
    }

    LeaveDetail detail(LeaveRequest r) {
        return new LeaveDetail(request(r), history(historyRepository.findByLeaveRequestIdOrderByIdAsc(r.getId())));
    }

    List<LeaveRequestResponse> requests(List<LeaveRequest> list) {
        Map<Long, Employee> people = load(list.stream().flatMap(r -> Stream.of(r.getEmployeeId(), r.getApproverId(), r.getDecidedBy())));
        return list.stream().map(r -> new LeaveRequestResponse(r.getId(), r.getRequestId(), r.getLeaveType(), r.getWorkDate(),
                r.getStartTime(), r.getEndTime(), r.getMinutes(), r.getReason(), r.getStatus(),
                person(people.get(r.getEmployeeId())), approver(people.get(r.getApproverId())), r.getApproverSource(),
                person(people.get(r.getDecidedBy())), r.getDecidedAt(), r.getDecisionReason(),
                r.getVersion(), r.getCreatedAt(), r.getUpdatedAt())).toList();
    }

    List<LeaveHistoryEntry> history(List<LeaveHistory> rows) {
        Map<Long, Employee> people = load(rows.stream().flatMap(h -> Stream.of(h.getBeforeApproverId(), h.getAfterApproverId(), h.getActorId())));
        return rows.stream().map(h -> new LeaveHistoryEntry(h.getId(), h.getAction(), h.getFromStatus(), h.getToStatus(),
                person(people.get(h.getBeforeApproverId())), person(people.get(h.getAfterApproverId())), h.getReason(),
                person(people.get(h.getActorId())), h.getOccurredAt())).toList();
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
