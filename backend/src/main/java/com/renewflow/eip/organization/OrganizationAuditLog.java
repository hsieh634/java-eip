package com.renewflow.eip.organization;

import com.renewflow.eip.organization.OrganizationHistory.Source;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/**
 * 組織歷程的單一寫入入口。在呼叫端的交易內執行：寫入失敗會丟出例外，讓業務修改一起回滾。
 * 只記錄呼叫端傳入的欄位變更——密碼與雜湊從來不會被放進 {@link Change}。
 */
@Component
public class OrganizationAuditLog {

    /** 一個欄位的前後值；部門與主管用 {@link Ref}（ID＋標籤），其他用字串或布林。 */
    public record Change(String field, Object before, Object after) {
    }

    public record Ref(Long id, String label) {
    }

    private final OrganizationHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    public OrganizationAuditLog(OrganizationHistoryRepository historyRepository, ObjectMapper objectMapper) {
        this.historyRepository = historyRepository;
        this.objectMapper = objectMapper;
    }

    public void employee(Long employeeId, String action, String reason, List<Change> changes, Long actorId, Source source, Instant now) {
        historyRepository.save(new OrganizationHistory("EMPLOYEE", employeeId, null, action, reason, json(changes), actorId, source, now));
    }

    public void department(Long departmentId, String action, String reason, List<Change> changes, Long actorId, Source source, Instant now) {
        historyRepository.save(new OrganizationHistory("DEPARTMENT", null, departmentId, action, reason, json(changes), actorId, source, now));
    }

    private String json(List<Change> changes) {
        return objectMapper.writeValueAsString(changes);
    }
}
