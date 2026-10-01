package com.renewflow.eip.support;

import com.renewflow.eip.EipApplication;
import com.renewflow.eip.identity.EmployeeRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T03.1：E2E 隔離堆疊的資料庫保護必須發生在「任何 migration 與初始化寫入之前」。
 *
 * <p>驗證分兩層，而且都不碰使用者的開發資料庫：
 * <ol>
 *   <li>替身：用假的 Flyway／DataSource，確認拒絕時 migrate 與 seed 都沒有被呼叫。</li>
 *   <li>真實啟動：建立臨時的獨立資料庫，用 e2e 設定真的啟動應用程式——指向不被允許的資料庫時啟動失敗，
 *       且該資料庫裡<b>沒有任何資料表</b>（證明 migration 沒有跑）；另有對照組（被允許時 migration 與 seed 會跑）。
 *       臨時資料庫在測試結束時刪除。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestFlywayConfig.class)
class E2eStackGuardTest {

    private static final String TEST_DB = "renewflow_test";
    private static final String DEV_DB = "renewflow";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Environment env;

    // ---------------- 第一層：替身 ----------------

    private static Flyway flywayConnectedTo(String catalog) throws SQLException {
        Flyway flyway = mock(Flyway.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        Connection connection = mock(Connection.class);
        when(connection.getCatalog()).thenReturn(catalog);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(flyway.getConfiguration().getDataSource()).thenReturn(dataSource);
        return flyway;
    }

    @Test
    @DisplayName("C-28 替身：Flyway 實際連到開發庫 → 拒絕，且 migrate 完全沒有被呼叫")
    void migrationIsNotRunAgainstDevDatabase() throws Exception {
        Flyway flyway = flywayConnectedTo(DEV_DB);

        assertThatThrownBy(() -> E2eFlywayConfig.strategy(TEST_DB, DEV_DB).migrate(flyway))
                .isInstanceOf(IllegalStateException.class);
        verify(flyway, never()).migrate();
        verify(flyway, never()).clean();
    }

    @Test
    @DisplayName("C-28 替身：連到其他非允許的資料庫、開發／測試設定相同、無法確認名稱 → 皆拒絕且不 migrate")
    void migrationRefusedForOtherMisconfigurations() throws Exception {
        Flyway other = flywayConnectedTo("some_other_db");
        assertThatThrownBy(() -> E2eFlywayConfig.strategy(TEST_DB, DEV_DB).migrate(other)).isInstanceOf(IllegalStateException.class);
        verify(other, never()).migrate();

        Flyway sameConfig = flywayConnectedTo(TEST_DB);
        assertThatThrownBy(() -> E2eFlywayConfig.strategy(TEST_DB, TEST_DB).migrate(sameConfig)).isInstanceOf(IllegalStateException.class);
        verify(sameConfig, never()).migrate();

        Flyway noConnection = mock(Flyway.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("no connection"));
        when(noConnection.getConfiguration().getDataSource()).thenReturn(broken);
        assertThatThrownBy(() -> E2eFlywayConfig.strategy(TEST_DB, DEV_DB).migrate(noConnection)).isInstanceOf(IllegalStateException.class);
        verify(noConnection, never()).migrate();
    }

    @Test
    @DisplayName("C-28 替身：連到允許的測試庫 → 才呼叫 migrate（對照）")
    void migrationRunsAgainstAllowedTestDatabase() throws Exception {
        Flyway flyway = flywayConnectedTo(TEST_DB);

        E2eFlywayConfig.strategy(TEST_DB, DEV_DB).migrate(flyway);

        verify(flyway).migrate();
    }

    @Test
    @DisplayName("C-28 替身：種子寫入在資料庫不對時被拒絕，沒有任何員工寫入")
    void seedRunnerWritesNothingWhenDatabaseIsWrong() throws Exception {
        EmployeeRepository employees = mock(EmployeeRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        Connection connection = mock(Connection.class);
        when(connection.getCatalog()).thenReturn(DEV_DB);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        E2eSeedRunner runner = new E2eSeedRunner(employees, encoder, dataSource, "run1", "pw-only-for-this-test", TEST_DB, DEV_DB);

        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(employees);
        verify(encoder, never()).encode(any());
    }

    // ---------------- 第二層：真實啟動（臨時資料庫） ----------------

    private String jdbcUrl(String database) {
        return "jdbc:postgresql://" + env.getProperty("DB_HOST", "localhost") + ":" + env.getProperty("DB_PORT", "5432") + "/" + database;
    }

    private Connection open(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database),
                env.getProperty("spring.datasource.username"), env.getProperty("spring.datasource.password"));
    }

    private int tableCount(String database) throws SQLException {
        try (Connection c = open(database); var rs = c.createStatement().executeQuery(
                "select count(*) from information_schema.tables where table_schema = 'public'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * 以 e2e 設定真的啟動應用程式。覆寫值用命令列參數傳入（優先權最高）；
     * SpringApplicationBuilder.properties() 只是「預設值」，會被 application-e2e.yml 蓋掉，不能用。
     * 開發庫名稱取自實際設定（renewflow.test.dev-database），不寫死。
     */
    private ConfigurableApplicationContext startE2e(String actualDatabase, String allowedDatabase) {
        return new SpringApplicationBuilder(EipApplication.class)
                .profiles("e2e")
                .web(WebApplicationType.SERVLET)
                .run("--spring.datasource.url=" + jdbcUrl(actualDatabase),
                        "--renewflow.test.database=" + allowedDatabase,
                        "--renewflow.test.dev-database=" + env.getRequiredProperty("renewflow.test.dev-database"),
                        "--renewflow.e2e.run-id=guardprobe",
                        "--renewflow.e2e.password=probe-only-Passw0rd",
                        "--server.port=0",
                        "--spring.main.banner-mode=off");
    }

    /**
     * 真正對 PostgreSQL 下 CREATE／DROP。名稱已由 {@link TemporaryDatabases} 驗證（固定格式、本次識別碼），
     * 所以可以安全地放進 SQL。DROP 不用 IF EXISTS、也不用 FORCE：本次的應用程式與連線都已關閉，
     * 若仍有人連著就是異常，應該失敗而不是強制中斷；只對「仍有連線正在結束」短暫重試。
     */
    private TemporaryDatabases.Admin realAdmin() {
        return new TemporaryDatabases.Admin() {
            @Override
            public void create(String name) throws SQLException {
                try (Connection c = open(env.getRequiredProperty("renewflow.test.database"))) {
                    c.createStatement().execute("CREATE DATABASE " + name);
                }
            }

            @Override
            public void drop(String name) throws SQLException {
                for (int attempt = 1; ; attempt++) {
                    try (Connection c = open(env.getRequiredProperty("renewflow.test.database"))) {
                        c.createStatement().execute("DROP DATABASE " + name);
                        return;
                    } catch (SQLException e) {
                        boolean stillInUse = "55006".equals(e.getSQLState());
                        if (!stillInUse || attempt >= 20) {
                            throw e;
                        }
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw e;
                        }
                    }
                }
            }
        };
    }

    @Test
    @DisplayName("C-28／R3 真實啟動（本次專屬的臨時庫）：不被允許 → 啟動失敗且該庫沒有任何資料表；允許 → 建表與種子；只清理本次建立的庫")
    void realStartupIsRefusedBeforeAnyMigration() throws Exception {
        String testDatabase = env.getRequiredProperty("renewflow.test.database");
        String devDatabase = env.getRequiredProperty("renewflow.test.dev-database");
        String created1;
        String created2;
        try (TemporaryDatabases temp = new TemporaryDatabases(realAdmin(), List.of(testDatabase, devDatabase))) {
            String refused = temp.create(temp.nameFor("refused", false));
            String allowed = temp.create(temp.nameFor("allowed", true));
            created1 = refused;
            created2 = allowed;

            // 拒絕：實際連到 refused，但允許的測試庫是設定中的 renewflow.test.database
            assertThatThrownBy(() -> startE2e(refused, testDatabase).close())
                    .hasStackTraceContaining("拒絕對資料庫執行");
            assertThat(tableCount(refused)).as("被拒絕的資料庫不得有任何資料表（含 flyway_schema_history）").isZero();

            // 對照組：實際連到的就是允許的（名稱符合 *_test、不同於開發庫）→ migration 與 seed 會執行
            try (ConfigurableApplicationContext ignored = startE2e(allowed, allowed)) {
                assertThat(tableCount(allowed)).as("對照組：允許時 migration 會建立資料表").isPositive();
                try (Connection c = open(allowed); var rs = c.createStatement().executeQuery(
                        "select count(*) from employee where username like 'e2e_%'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("對照組：種子員工已建立").isEqualTo(4);
                }
            } // ← 先關閉本次的應用程式（連線池），try-with-resources 結束時才清理臨時庫
        }
        // 清理後：本次建立的兩個庫都已不存在；開發庫與測試庫仍在
        var remaining = jdbc.queryForList("select datname from pg_database", String.class);
        assertThat(remaining).doesNotContain(created1, created2).contains(testDatabase, devDatabase);
    }
}
