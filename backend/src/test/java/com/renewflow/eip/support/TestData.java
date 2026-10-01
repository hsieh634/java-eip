package com.renewflow.eip.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** 只對「測試資料庫」重置資料（連線已由 TestFlywayConfig／TestDatabaseGuard 把關）。 */
public final class TestData {

    private TestData() {
    }

    /** 先刪出勤再刪員工：出勤對員工有外鍵（ON DELETE RESTRICT）。 */
    public static void reset(JdbcTemplate jdbc) {
        jdbc.update("delete from attendance");
        jdbc.update("delete from employee");
    }
}
