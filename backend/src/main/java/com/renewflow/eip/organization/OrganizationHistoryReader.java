package com.renewflow.eip.organization;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.organization.OrganizationDtos.HistoryActor;
import com.renewflow.eip.organization.OrganizationDtos.OrganizationHistoryEntry;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 把歷程列轉成回應：補上操作者的員工編號與名稱、把 changes 文字解析回 JSON。只讀。 */
@Component
class OrganizationHistoryReader {

    private final EmployeeAdminRepository employeeRepository;
    private final ObjectMapper objectMapper;

    OrganizationHistoryReader(EmployeeAdminRepository employeeRepository, ObjectMapper objectMapper) {
        this.employeeRepository = employeeRepository;
        this.objectMapper = objectMapper;
    }

    List<OrganizationHistoryEntry> toEntries(List<OrganizationHistory> rows) {
        List<Long> actorIds = rows.stream().map(OrganizationHistory::getActorId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Employee> actors = employeeRepository.findAllById(actorIds).stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));
        return rows.stream().map(h -> {
            Employee a = h.getActorId() == null ? null : actors.get(h.getActorId());
            HistoryActor actor = a == null ? null : new HistoryActor(a.getId(), a.getEmployeeNo(), a.getDisplayName());
            return new OrganizationHistoryEntry(h.getId(), h.getAction(), h.getReason(), objectMapper.readTree(h.getChanges()),
                    actor, h.getSource(), h.getOccurredAt());
        }).toList();
    }
}
