package com.renewflow.eip.attendance;

import org.springframework.stereotype.Component;

/**
 * 歷程的單一寫入入口。它在呼叫端 Service 的交易內執行：寫入失敗會丟出例外，
 * 讓整個交易（出勤修改、申請狀態、歷程）一起回滾——不會有「改了卻沒有紀錄」。
 */
@Component
public class AttendanceAuditLog {

    private final AttendanceHistoryRepository historyRepository;

    public AttendanceAuditLog(AttendanceHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    void record(AuditEntry entry) {
        historyRepository.save(new AttendanceHistory(entry));
    }
}
