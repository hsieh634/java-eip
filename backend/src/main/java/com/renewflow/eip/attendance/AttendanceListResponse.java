package com.renewflow.eip.attendance;

import java.time.LocalDate;
import java.util.List;

/** 本人出勤列表；from／to 是實際採用的範圍（省略時為本月），records 依日期由新到舊。 */
public record AttendanceListResponse(
        LocalDate from,
        LocalDate to,
        String timeZone,
        List<AttendanceRecordResponse> records) {
}
