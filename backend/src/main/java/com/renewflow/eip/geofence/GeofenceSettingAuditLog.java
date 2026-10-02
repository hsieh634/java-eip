package com.renewflow.eip.geofence;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 設定歷程的單一寫入入口。在呼叫端 Service 的交易內執行：寫入失敗會丟出例外，
 * 讓設定更新一起回滾——不會有「設定改了卻沒有紀錄」。
 */
@Component
public class GeofenceSettingAuditLog {

    private final GeofenceSettingHistoryRepository historyRepository;

    public GeofenceSettingAuditLog(GeofenceSettingHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    public void record(GeofenceSnapshot before, GeofenceSnapshot after, String reason, Long actorId, Instant occurredAt) {
        historyRepository.save(new GeofenceSettingHistory(before, after, reason, actorId, occurredAt));
    }
}
