package com.renewflow.eip.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 在 Flyway clean（會刪除資料庫內所有物件）之前的最後一道防線。
 *
 * <p>原本只檢查 JDBC URL 字串是否以 "_test" 結尾，但 URL 的結尾不等於「實際連到的資料庫」，
 * 例如 {@code jdbc:postgresql://localhost/renewflow?ApplicationName=eip_test} 實際連到 renewflow。
 * 所以這裡改為比對<b>連線建立後由資料庫回報的名稱</b>（{@link Connection#getCatalog()}）。
 *
 * <p>任何一項不成立、或無法確認，都直接拒絕（fail closed）。
 */
public final class TestDatabaseGuard {

    /** 測試資料庫的命名慣例：小寫英文開頭，以 _test 結尾。與下面的「明確允許名稱」是兩道獨立條件。 */
    private static final Pattern TEST_DATABASE_NAME = Pattern.compile("[a-z][a-z0-9_]*_test");

    private TestDatabaseGuard() {
    }

    /** 向資料庫詢問「這條連線實際連到哪個資料庫」。查不到就視為不安全。 */
    public static String actualDatabase(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException e) {
            throw new IllegalStateException("無法確認實際連線的資料庫名稱，拒絕執行 clean", e);
        }
    }

    /**
     * @param actualDatabase      連線實際連到的資料庫（來自資料庫，不是來自 URL 字串）
     * @param allowedTestDatabase 設定中「明確允許被清空」的唯一資料庫
     * @param devDatabase         開發資料庫名稱，用來確認兩者沒有被設成同一個
     * @throws IllegalStateException 只要有任何疑慮
     */
    public static void assertSafeToClean(String actualDatabase, String allowedTestDatabase, String devDatabase) {
        if (isBlank(actualDatabase) || isBlank(allowedTestDatabase) || isBlank(devDatabase)) {
            throw refuse("無法確認資料庫名稱（actual=%s, allowedTest=%s, dev=%s）", actualDatabase, allowedTestDatabase, devDatabase);
        }
        if (!TEST_DATABASE_NAME.matcher(allowedTestDatabase).matches()) {
            throw refuse("允許清空的資料庫名稱 '%s' 不符合測試資料庫命名（小寫英數底線並以 _test 結尾）", allowedTestDatabase);
        }
        // 原因：開發庫與測試庫被設成同一個目標時，上一條規則仍可能通過，所以要單獨比對
        if (allowedTestDatabase.toLowerCase(Locale.ROOT).equals(devDatabase.toLowerCase(Locale.ROOT))) {
            throw refuse("測試資料庫與開發資料庫設定相同：'%s'", allowedTestDatabase);
        }
        if (!actualDatabase.equals(allowedTestDatabase)) {
            throw refuse("實際連線的資料庫是 '%s'，不是允許清空的 '%s'", actualDatabase, allowedTestDatabase);
        }
        if (actualDatabase.toLowerCase(Locale.ROOT).equals(devDatabase.toLowerCase(Locale.ROOT))) {
            throw refuse("實際連線的資料庫就是開發資料庫 '%s'", actualDatabase);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalStateException refuse(String format, Object... args) {
        return new IllegalStateException("拒絕對資料庫執行 clean：" + String.format(format, args));
    }
}
