package com.renewflow.eip.attendance;

import com.renewflow.eip.geofence.GeofenceDtos.ClockLocationRequest;
import com.renewflow.eip.geofence.ClockAction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 保存原成功快照：原紀錄已完成／作廢／更正後重送，也不改變最初操作。 */
@Repository
class AttendanceClockSubmissions {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    AttendanceClockSubmissions(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    String fingerprint(ClockLocationRequest body) {
        String canonical = body.latitude() + "|" + body.longitude() + "|" + body.accuracy() + "|" + body.capturedAt() + "|" + body.expectedAttendanceId() + "|" + body.expectedVersion();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    AttendanceRecordResponse replay(Long employeeId, ClockLocationRequest body, ClockAction action, String fingerprint) {
        var rows = jdbc.queryForList("select action, fingerprint, response_json from attendance_clock_submission where employee_id=? and request_id=?", employeeId, body.requestId());
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!action.name().equals(row.get("action")) || !fingerprint.equals(row.get("fingerprint")))
            throw new AttendanceConflictException("CLOCK_REQUEST_MISMATCH", "同一打卡識別碼不可改成其他操作或內容，請確認原提交");
        return mapper.readValue((String) row.get("response_json"), AttendanceRecordResponse.class);
    }
    void record(Long employeeId, ClockLocationRequest body, ClockAction action, String fingerprint, AttendanceRecordResponse response) {
        jdbc.update("insert into attendance_clock_submission(employee_id,request_id,action,fingerprint,attendance_id,response_json) values (?,?,?,?,?,?)", employeeId, body.requestId(), action.name(), fingerprint, response.id(), mapper.writeValueAsString(response));
    }
}
