package com.renewflow.eip.leave;

import com.renewflow.eip.leave.LeaveEnums.HistoryAction;
import com.renewflow.eip.leave.LeaveEnums.LeaveStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 請假歷程的單一寫入入口。在呼叫端的交易內執行：寫入失敗會丟出例外，讓業務修改一起回滾。
 * 只記錄穩定 ID、動作、狀態、審核人前後、原因、操作者與時間，不含密碼或其他個資。
 */
@Component
public class LeaveAuditLog {

    private final LeaveHistoryRepository historyRepository;

    public LeaveAuditLog(LeaveHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    /** 申請相關的動作（送出、核准、退回、撤回、改派）。 */
    public void request(Long leaveRequestId, HistoryAction action, LeaveStatus fromStatus, LeaveStatus toStatus,
                        Long beforeApproverId, Long afterApproverId, String reason, Long actorId, Instant now) {
        historyRepository.save(new LeaveHistory(leaveRequestId, null, action, fromStatus, toStatus,
                beforeApproverId, afterApproverId, reason, actorId, now));
    }

    /** 指定請假審核人的變更。 */
    public void setting(Long employeeId, Long beforeApproverId, Long afterApproverId, String reason, Long actorId, Instant now) {
        historyRepository.save(new LeaveHistory(null, employeeId, HistoryAction.APPROVER_SETTING_CHANGED, null, null,
                beforeApproverId, afterApproverId, reason, actorId, now));
    }
}
