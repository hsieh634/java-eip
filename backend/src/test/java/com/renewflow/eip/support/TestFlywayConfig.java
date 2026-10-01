package com.renewflow.eip.support;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.sql.Connection;

/**
 * 測試啟動時：先清空資料庫，再從 V1 開始套用全部 migration。
 * 這同時驗證了「乾淨資料庫可以由 migration 完整重建」。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestFlywayConfig {

    @Bean
    FlywayMigrationStrategy cleanThenMigrate() {
        return flyway -> {
            String url;
            try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
                url = connection.getMetaData().getURL();
            } catch (Exception e) {
                throw new IllegalStateException("無法確認資料庫位置，拒絕執行 clean", e);
            }
            // 原因：clean 會刪除所有物件。若環境設定錯誤，測試絕不能清到開發或正式資料庫
            if (url == null || !url.endsWith("_test")) {
                throw new IllegalStateException("拒絕對非測試資料庫執行 clean：" + url);
            }
            flyway.clean();
            flyway.migrate();
        };
    }
}
