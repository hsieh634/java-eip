package com.renewflow.eip.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R1 回歸測試：不啟動 Spring、不連資料庫，因此「驗證防護」的過程本身不可能清空任何資料庫。
 * 真的連資料庫（只讀取名稱、不 clean）的案例在 {@link TestDatabaseGuardConnectionTest}。
 */
class TestDatabaseGuardTest {

    private static final String TEST_DB = "renewflow_test";
    private static final String DEV_DB = "renewflow";

    // ---------- 純規則：assertSafeToClean ----------

    @Test
    @DisplayName("R1 正常測試庫：實際連線＝允許的測試庫、且不同於開發庫 → 放行")
    void normalTestDatabaseAllowed() {
        assertThatCode(() -> TestDatabaseGuard.assertSafeToClean(TEST_DB, TEST_DB, DEV_DB)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("R1 實際連到開發庫 → 拒絕")
    void devDatabaseRefused() {
        assertThatThrownBy(() -> TestDatabaseGuard.assertSafeToClean(DEV_DB, TEST_DB, DEV_DB))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("拒絕");
    }

    @Test
    @DisplayName("R1 實際連到其他以 _test 結尾的資料庫（不是明確允許的那個）→ 拒絕")
    void otherTestSuffixedDatabaseRefused() {
        assertThatThrownBy(() -> TestDatabaseGuard.assertSafeToClean("other_test", TEST_DB, DEV_DB))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("R1 開發與測試設定相同（即使名稱以 _test 結尾）→ 拒絕")
    void sameDevAndTestConfigurationRefused() {
        assertThatThrownBy(() -> TestDatabaseGuard.assertSafeToClean(TEST_DB, TEST_DB, TEST_DB))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("相同");
        // 大小寫不同也視為相同目標
        assertThatThrownBy(() -> TestDatabaseGuard.assertSafeToClean(TEST_DB, TEST_DB, "RenewFlow_Test"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("R1 開發與測試都被設成不以 _test 結尾的同一個庫（例如 DB_TEST_NAME=renewflow）→ 拒絕")
    void testNameNotFollowingConventionRefused() {
        assertThatThrownBy(() -> TestDatabaseGuard.assertSafeToClean(DEV_DB, DEV_DB, DEV_DB))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestDatabaseGuard.assertSafeToClean("scratch", "scratch", DEV_DB))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("命名");
    }

    @Test
    @DisplayName("R1 任何名稱缺失或空白（無法確認）→ 拒絕")
    void unknownNamesRefused() {
        assertThatThrownBy(() -> TestDatabaseGuard.assertSafeToClean(null, TEST_DB, DEV_DB)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestDatabaseGuard.assertSafeToClean(TEST_DB, null, DEV_DB)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestDatabaseGuard.assertSafeToClean(TEST_DB, TEST_DB, " ")).isInstanceOf(IllegalStateException.class);
    }

    // ---------- 策略層：clean 是否真的沒有被呼叫 ----------

    private static Flyway flywayConnectedTo(String catalog) throws SQLException {
        Flyway flyway = mock(Flyway.class, Mockito.RETURNS_DEEP_STUBS);
        Connection connection = mock(Connection.class);
        when(connection.getCatalog()).thenReturn(catalog);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(flyway.getConfiguration().getDataSource()).thenReturn(dataSource);
        return flyway;
    }

    private static void runStrategy(Flyway flyway) throws Exception {
        new TestFlywayConfig().cleanThenMigrate(TEST_DB, DEV_DB).migrate(flyway);
    }

    @Test
    @DisplayName("R1 策略：連到測試庫 → 先 clean 再 migrate（順序正確）")
    void strategyCleansThenMigratesTestDatabase() throws Exception {
        Flyway flyway = flywayConnectedTo(TEST_DB);
        runStrategy(flyway);

        InOrder order = inOrder(flyway);
        order.verify(flyway).clean();
        order.verify(flyway).migrate();
    }

    @Test
    @DisplayName("R1 策略：連到開發庫（含 URL 以 _test 偽裝的情況，實際名稱仍是 renewflow）→ 丟出例外且絕不呼叫 clean")
    void strategyNeverCleansDevDatabase() throws Exception {
        Flyway flyway = flywayConnectedTo(DEV_DB);

        assertThatThrownBy(() -> runStrategy(flyway)).isInstanceOf(IllegalStateException.class);
        verify(flyway, never()).clean();
        verify(flyway, never()).migrate();
    }

    @Test
    @DisplayName("R1 策略：資料庫名稱查詢失敗（無法確認）→ 不 clean")
    void strategyFailsClosedWhenCatalogUnavailable() throws Exception {
        Flyway flyway = mock(Flyway.class, Mockito.RETURNS_DEEP_STUBS);
        Connection connection = mock(Connection.class);
        when(connection.getCatalog()).thenThrow(new SQLException("boom"));
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(flyway.getConfiguration().getDataSource()).thenReturn(dataSource);

        assertThatThrownBy(() -> runStrategy(flyway)).isInstanceOf(IllegalStateException.class);
        verify(flyway, never()).clean();
    }

    @Test
    @DisplayName("R1 策略：連線都建不起來 → 不 clean")
    void strategyFailsClosedWhenNoConnection() throws Exception {
        Flyway flyway = mock(Flyway.class, Mockito.RETURNS_DEEP_STUBS);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("no connection"));
        when(flyway.getConfiguration().getDataSource()).thenReturn(dataSource);

        assertThatThrownBy(() -> runStrategy(flyway)).isInstanceOf(IllegalStateException.class);
        verify(flyway, never()).clean();
    }

    @Test
    @DisplayName("R1 策略：開發與測試設定相同 → 即使連到名稱正確的庫也不 clean")
    void strategyRefusesWhenDevAndTestConfiguredTheSame() throws Exception {
        Flyway flyway = flywayConnectedTo(TEST_DB);

        assertThatThrownBy(() -> new TestFlywayConfig().cleanThenMigrate(TEST_DB, TEST_DB).migrate(flyway))
                .isInstanceOf(IllegalStateException.class);
        verify(flyway, never()).clean();
    }
}
