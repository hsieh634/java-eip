package com.renewflow.eip.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * R1：用「真的 JDBC 連線」確認資料庫回報的名稱，並套用 guard。
 * 只讀取名稱，不執行 clean／任何寫入，所以連到開發庫也不會改動它。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestFlywayConfig.class)
class TestDatabaseGuardConnectionTest {

    @Autowired
    Environment env;

    private String url(String database, String query) {
        return "jdbc:postgresql://" + env.getProperty("DB_HOST", "localhost") + ":" + env.getProperty("DB_PORT", "5432")
                + "/" + database + query;
    }

    private Connection open(String url) throws SQLException {
        return DriverManager.getConnection(url, env.getProperty("spring.datasource.username"),
                env.getProperty("spring.datasource.password"));
    }

    @Test
    @DisplayName("R1 真實連線：測試庫回報的名稱通過 guard")
    void realTestDatabasePasses() throws SQLException {
        String test = env.getRequiredProperty("renewflow.test.database");
        String dev = env.getRequiredProperty("renewflow.test.dev-database");
        try (Connection c = open(url(test, ""))) {
            String actual = TestDatabaseGuard.actualDatabase(c);
            assertThat(actual).isEqualTo(test);
            assertThatCode(() -> TestDatabaseGuard.assertSafeToClean(actual, test, dev)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("R1 真實連線：開發庫的 URL 加上 ?ApplicationName=eip_test 偽裝，舊檢查會放行，新檢查拒絕")
    void realDevDatabaseDisguisedByQueryParameterIsRefused() {
        String test = env.getRequiredProperty("renewflow.test.database");
        String dev = env.getRequiredProperty("renewflow.test.dev-database");
        String disguised = url(dev, "?ApplicationName=eip_test");

        // 舊作法：只看 URL 結尾 → 會誤判為測試庫
        assertThat(disguised.endsWith("_test")).as("舊的 endsWith 檢查會被騙過").isTrue();

        try (Connection c = open(disguised)) {
            String actual = TestDatabaseGuard.actualDatabase(c);
            assertThat(actual).as("資料庫實際回報的名稱").isEqualTo(dev);
            assertThatThrownBy(() -> TestDatabaseGuard.assertSafeToClean(actual, test, dev))
                    .isInstanceOf(IllegalStateException.class);
        } catch (SQLException e) {
            // 開發資料庫不存在（例如只建立了測試庫的 CI）時無法做此項驗證；以「略過」而非「通過」呈現
            assumeTrue(false, "無法連到開發資料庫 '" + dev + "'，略過此項：" + e.getMessage());
        }
    }
}
