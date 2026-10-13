package com.renewflow.eip.attendance;

import com.renewflow.eip.geofence.ClockAction;
import com.renewflow.eip.shared.time.BusinessTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/** 僅供已完成流程資料範圍檢查的請假服務呼叫，沒有獨立 HTTP 入口，也不修改出勤。 */
@Service
public class AttendanceEvidenceService {
    public record Event(Long id, Long attendanceId, LocalDate workDate, ClockAction action, Instant acceptedAt) {}
    public record Snapshot(List<LocalDate> dates, List<AttendanceRecordResponse> records, List<Event> events, Instant capturedAt, String token) {}
    private final AttendanceEvidenceRepository repository;
    private final Clock clock;
    private final ObjectMapper mapper;
    AttendanceEvidenceService(AttendanceEvidenceRepository repository, Clock clock, ObjectMapper mapper) {
        this.repository=repository; this.clock=clock; this.mapper=mapper;
    }
    @Transactional(readOnly=true)
    public Snapshot forAuthorizedLeave(Long employeeId, Collection<LocalDate> relevantDates) {
        var dates = relevantDates.stream().distinct().sorted().toList();
        var now = BusinessTime.truncateToDatabasePrecision(clock.instant());
        var rows = repository.read(employeeId, dates, BusinessTime.workDateOf(now));
        // token 不包含取得時間：相同資料重查仍可比對，綁定申請人與限定日期。
        String canonical = employeeId + "|" + mapper.writeValueAsString(dates) + "|" + mapper.writeValueAsString(rows);
        try {
            String token = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return new Snapshot(dates,rows.records(),rows.events(),now,token);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
