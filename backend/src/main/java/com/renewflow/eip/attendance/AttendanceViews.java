package com.renewflow.eip.attendance;

import com.renewflow.eip.attendance.AttendanceDtos.AfterState;
import com.renewflow.eip.attendance.AttendanceDtos.AttendanceRequestResponse;
import com.renewflow.eip.attendance.AttendanceDtos.EmployeeSummary;
import com.renewflow.eip.attendance.AttendanceDtos.HistoryEntryResponse;
import com.renewflow.eip.attendance.AttendanceDtos.TimePair;
import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.EmployeeRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Entity → DTO 的轉換（名稱查詢與「過時」判斷集中在這裡）。每個方法都在呼叫端的交易內執行。 */
@Component
class AttendanceViews {

    private final EmployeeRepository employeeRepository;
    private final AttendanceRepository attendanceRepository;

    AttendanceViews(EmployeeRepository employeeRepository, AttendanceRepository attendanceRepository) {
        this.employeeRepository = employeeRepository;
        this.attendanceRepository = attendanceRepository;
    }

    AttendanceRequestResponse request(AttendanceRequest request, LocalDate today) {
        return requests(List.of(request), today).get(0);
    }

    List<AttendanceRequestResponse> requests(List<AttendanceRequest> requests, LocalDate today) {
        Set<Long> ids = new HashSet<>();
        requests.forEach(r -> {
            ids.add(r.getEmployeeId());
            if (r.getReviewerId() != null) {
                ids.add(r.getReviewerId());
            }
        });
        Map<Long, Employee> employees = employeesById(ids);

        return requests.stream().map(r -> {
            Employee employee = employees.get(r.getEmployeeId());
            Employee reviewer = r.getReviewerId() == null ? null : employees.get(r.getReviewerId());
            boolean pending = r.getStatus() == AttendanceRequestStatus.PENDING;
            Attendance current = pending
                    ? attendanceRepository.findByEmployeeIdAndWorkDateAndVoidedAtIsNull(r.getEmployeeId(), r.getWorkDate()).orElse(null)
                    : null;
            return new AttendanceRequestResponse(
                    r.getId(), r.getType(), r.getStatus(), r.getWorkDate(), r.getReason(),
                    new EmployeeSummary(r.getEmployeeId(), employee == null ? null : employee.getEmployeeNo(),
                            employee == null ? null : employee.getDisplayName()),
                    r.getTargetAttendanceId() == null && r.getOriginalClockInAt() == null ? null
                            : new TimePair(r.getOriginalClockInAt(), r.getOriginalClockOutAt()),
                    r.getType() == AttendanceRequestType.RESET_TODAY ? null
                            : new TimePair(r.getRequestedClockInAt(), r.getRequestedClockOutAt()),
                    current == null ? null : new TimePair(current.getClockInAt(), current.getClockOutAt()),
                    pending && isStale(r, current, today),
                    r.getCreatedAt(), r.getReviewedAt(),
                    reviewer == null ? null : reviewer.getDisplayName(), r.getReviewComment());
        }).toList();
    }

    /**
     * 待審申請是否已過時：申請當時看到的紀錄（id＋版本）與現在的有效紀錄不同。
     * 沒有目標（補登）的申請，只要現在已有有效紀錄就算過時。RESET_TODAY 另外在跨日後也算過時。
     */
    private static boolean isStale(AttendanceRequest r, Attendance current, LocalDate today) {
        if (r.getType() == AttendanceRequestType.RESET_TODAY && !r.getWorkDate().equals(today)) {
            return true;
        }
        if (r.getTargetAttendanceId() == null) {
            return current != null;
        }
        return current == null
                || !current.getId().equals(r.getTargetAttendanceId())
                || current.getVersion() != r.getTargetVersion();
    }

    List<HistoryEntryResponse> history(List<AttendanceHistory> entries) {
        Set<Long> ids = new HashSet<>();
        entries.forEach(h -> {
            ids.add(h.getEmployeeId());
            ids.add(h.getActorId());
        });
        Map<Long, Employee> employees = employeesById(ids);

        return entries.stream().map(h -> new HistoryEntryResponse(
                h.getId(), h.getOperation(), h.getWorkDate(), h.getRequestId(), h.getRequestType(), h.getReason(),
                nameOf(employees, h.getActorId()), nameOf(employees, h.getEmployeeId()),
                new TimePair(h.getOriginalClockInAt(), h.getOriginalClockOutAt()),
                h.getRequestedClockInAt() == null && h.getRequestedClockOutAt() == null ? null
                        : new TimePair(h.getRequestedClockInAt(), h.getRequestedClockOutAt()),
                h.getAfterVoided() == null ? null
                        : new AfterState(h.getAfterClockInAt(), h.getAfterClockOutAt(), h.getAfterVoided()),
                h.getResult(), h.getOccurredAt())).toList();
    }

    EmployeeSummary employee(Employee employee) {
        return new EmployeeSummary(employee.getId(), employee.getEmployeeNo(), employee.getDisplayName());
    }

    private Map<Long, Employee> employeesById(Set<Long> ids) {
        Map<Long, Employee> map = new HashMap<>();
        employeeRepository.findAllById(ids).forEach(e -> map.put(e.getId(), e));
        return map;
    }

    private static String nameOf(Map<Long, Employee> employees, Long id) {
        Employee e = employees.get(id);
        return e == null ? null : e.getDisplayName();
    }
}
