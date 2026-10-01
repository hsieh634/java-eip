package com.renewflow.eip.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.sql.Connection;

/**
 * 測試啟動時：先清空資料庫，再從 V1 開始套用全部 migration。
 * 這同時驗證了「乾淨資料庫可以由 migration 完整重建」。
 *
 * <p>clean 之前一定先經過 {@link TestDatabaseGuard}：以「資料庫實際回報的名稱」比對明確允許的測試資料庫，
 * 通過才會清空；任何一步出錯都不會執行 clean。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestFlywayConfig {

    @Bean
    public FlywayMigrationStrategy cleanThenMigrate(
            @Value("${renewflow.test.database}") String allowedTestDatabase,
            @Value("${renewflow.test.dev-database}") String devDatabase) {
        return flyway -> {
            String actualDatabase;
            try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
                actualDatabase = TestDatabaseGuard.actualDatabase(connection);
            } catch (Exception e) {
                throw new IllegalStateException("無法確認實際連線的資料庫，拒絕執行 clean", e);
            }
            TestDatabaseGuard.assertSafeToClean(actualDatabase, allowedTestDatabase, devDatabase);

            flyway.clean();
            flyway.migrate();
        };
    }
}
