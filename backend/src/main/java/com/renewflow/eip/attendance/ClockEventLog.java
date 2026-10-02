package com.renewflow.eip.attendance;

import com.renewflow.eip.geofence.ClockAction;
import com.renewflow.eip.geofence.LocationDecision;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 打卡證據的單一寫入入口。在打卡交易內執行：寫入失敗會丟出例外，讓出勤寫入一起回滾——
 * 不會留下「打卡成功卻沒有證據」。（測試以替身注入失敗驗證這件事。）
 */
@Component
public class ClockEventLog {

    private final AttendanceClockEventRepository eventRepository;

    public ClockEventLog(AttendanceClockEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    void record(Long employeeId, Long attendanceId, ClockAction action, Instant acceptedAt, LocationDecision decision) {
        eventRepository.save(new AttendanceClockEvent(employeeId, attendanceId, action, acceptedAt, decision));
    }
}
