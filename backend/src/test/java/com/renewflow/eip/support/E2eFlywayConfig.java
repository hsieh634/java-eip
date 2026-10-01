package com.renewflow.eip.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.sql.Connection;

/**
 * 【僅 E2E 隔離堆疊】把「資料庫是不是對的」的檢查提前到 migration 之前。
 *
 * <p>先前只在 ApplicationRunner（應用程式啟動完成之後）檢查，但那時 Flyway 早已套用過 migration，
 * 設定錯誤時會先寫入、再拒絕。現在改以 {@link FlywayMigrationStrategy} 取代預設的「直接 migrate」：
 * 先用 <b>Flyway 實際使用的 datasource</b> 向資料庫詢問實際名稱，通過 {@link TestDatabaseGuard}
 * （必須是明確允許的測試庫、且不同於開發庫）才呼叫 migrate；否則丟出例外，migrate 完全不會被呼叫，
 * 後面的種子寫入（E2eSeedRunner）自然也不會執行。
 */
@Configuration(proxyBeanMethods = false)
@Profile("e2e")
class E2eFlywayConfig {

    @Bean
    FlywayMigrationStrategy guardedMigration(
            @Value("${renewflow.test.database}") String allowedTestDatabase,
            @Value("${renewflow.test.dev-database}") String devDatabase) {
        return strategy(allowedTestDatabase, devDatabase);
    }

    /** 獨立成靜態方法，讓測試可以用替身 Flyway 直接驗證「拒絕時沒有呼叫 migrate」。 */
    static FlywayMigrationStrategy strategy(String allowedTestDatabase, String devDatabase) {
        return flyway -> {
            String actualDatabase;
            try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
                actualDatabase = TestDatabaseGuard.actualDatabase(connection);
            } catch (Exception e) {
                throw new IllegalStateException("無法確認 Flyway 實際連線的資料庫，拒絕執行 migration", e);
            }
            TestDatabaseGuard.assertSafeToClean(actualDatabase, allowedTestDatabase, devDatabase);
            flyway.migrate();
        };
    }
}
