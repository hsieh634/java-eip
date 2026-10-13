package com.renewflow.eip.attendance;

import com.renewflow.eip.geofence.ClockAction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/** 單一 SELECT 的一致快照；只投影出勤與原事件，完全不讀 GPS 欄位。 */
@Repository
class AttendanceEvidenceRepository {
    record Rows(List<AttendanceRecordResponse> records, List<AttendanceEvidenceService.Event> events) {}
    private final JdbcTemplate jdbc;
    AttendanceEvidenceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    Rows read(Long employeeId, Collection<LocalDate> dates, LocalDate today) {
        var records = new LinkedHashMap<Long, AttendanceRecordResponse>();
        var events = new ArrayList<AttendanceEvidenceService.Event>();
        if (dates.isEmpty()) return new Rows(List.of(), List.of());
        var args = new ArrayList<Object>(); args.add(employeeId); args.addAll(dates);
        String sql = "select a.*,e.id as event_id,e.action,e.accepted_at from attendance a left join attendance_clock_event e on e.attendance_id=a.id " +
            "where a.employee_id=? and a.work_date in (" + dates.stream().map(d -> "?").collect(Collectors.joining(",")) +
            ") order by a.work_date,a.clock_in_at,a.id,e.id";
        jdbc.query(sql, rs -> {
            Long id = rs.getLong("id"); LocalDate date = rs.getDate("work_date").toLocalDate();
            var in = rs.getTimestamp("clock_in_at").toInstant(); var outTs = rs.getTimestamp("clock_out_at"); var voidTs = rs.getTimestamp("voided_at");
            var status = voidTs != null ? AttendanceStatus.VOIDED : outTs != null ? AttendanceStatus.COMPLETED : date.equals(today) ? AttendanceStatus.WORKING : AttendanceStatus.INCOMPLETE;
            records.putIfAbsent(id, new AttendanceRecordResponse(id, date, in, outTs == null ? null : outTs.toInstant(), status,
                rs.getString("source"), rs.getTimestamp("corrected_at") != null, voidTs == null ? null : voidTs.toInstant(), rs.getLong("version"), null));
            if (rs.getObject("event_id") != null) events.add(new AttendanceEvidenceService.Event(rs.getLong("event_id"), id, date,
                ClockAction.valueOf(rs.getString("action")), rs.getTimestamp("accepted_at").toInstant()));
        }, args.toArray());
        return new Rows(List.copyOf(records.values()), List.copyOf(events));
    }
}
