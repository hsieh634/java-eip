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
     */
    public static void reset(JdbcTemplate jdbc) {
        jdbc.execute("truncate table attendance_history, attendance_request, attendance, employee");
    }
}
