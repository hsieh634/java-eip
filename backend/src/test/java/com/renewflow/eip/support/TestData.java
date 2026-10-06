package com.renewflow.eip.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** 只對「測試資料庫」重置資料（連線已由 TestFlywayConfig／TestDatabaseGuard 把關）。 */
public final class TestData {

    private TestData() {
    }

    /**
     * 清空測試庫的員工與出勤相關資料。
     * 用 TRUNCATE 而不是 DELETE：歷程表有觸發器禁止 DELETE／UPDATE（歷程只能新增）；
     * TRUNCATE 需要把所有互相有外鍵的表列在同一個指令內。
     * T03.2：打卡事件與設定歷程同樣只能新增，也用 TRUNCATE；定位設定列（引用 employee）一起清掉後，
     * 重新建立 V4 的預設值（關閉、未設定中心、100 公尺、上下班都限制）。
     * T04：專案與工時（工時引用員工與專案）一起清空。
     * T05 前置：組織歷程（只能新增）與部門一起清空（員工引用部門）。
     * T05：請假歷程、申請、指定審核人設定一起清空。
     */
    public static void reset(JdbcTemplate jdbc) {
        jdbc.execute("truncate table leave_history, leave_request, leave_approver_setting, organization_history, timesheet_entry, project, attendance_clock_event, geofence_setting_history,"
                + " geofence_setting, attendance_history, attendance_request, attendance, employee, department");
        jdbc.execute("insert into geofence_setting (id) values (1)");
    }

    /**
     * 只把定位設定恢復為預設值（不動員工與出勤）。給 E2E 隔離堆疊使用：測試庫是共用的拋棄式資料庫，
     * 前一次測試留下的「已啟用」不能影響這一次。只寫測試庫，不寫設定歷程。
     */
    public static void resetGeofence(JdbcTemplate jdbc) {
        jdbc.update("""
                update geofence_setting set enabled = false, center_latitude = null, center_longitude = null,
                       radius_meters = 100, scope = 'BOTH', version = version + 1, updated_by = null, updated_at = null
                 where id = 1
                """);
    }
}
