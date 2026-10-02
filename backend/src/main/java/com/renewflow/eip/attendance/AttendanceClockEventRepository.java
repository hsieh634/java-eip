package com.renewflow.eip.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

/** 打卡事件只新增；本階段沒有對外的查詢 API（員工無法看到任何人的定位）。 */
public interface AttendanceClockEventRepository extends JpaRepository<AttendanceClockEvent, Long> {
}
