package com.renewflow.eip.attendance;

import com.renewflow.eip.shared.time.BusinessTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 純單元測試：業務日期換算與時間精度，不需要 Spring 與資料庫。 */
class BusinessTimeTest {

    @Test
    @DisplayName("同一時間點的 UTC 日期與台北日期可能不同：台北比 UTC 快 8 小時")
    void taipeiDateDiffersFromUtcDate() {
        Instant instant = Instant.parse("2026-10-01T16:30:00Z");

        assertThat(instant.atZone(java.time.ZoneOffset.UTC).toLocalDate()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(BusinessTime.workDateOf(instant)).isEqualTo(LocalDate.parse("2026-10-02"));
    }

    @Test
    @DisplayName("邊界：UTC 15:59:59.999999999 仍是台北當天；16:00:00 進入隔天")
    void boundary() {
        assertThat(BusinessTime.workDateOf(Instant.parse("2026-10-01T15:59:59.999999999Z"))).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(BusinessTime.workDateOf(Instant.parse("2026-10-01T16:00:00Z"))).isEqualTo(LocalDate.parse("2026-10-02"));
    }

    @Test
    @DisplayName("資料庫精度：截斷到微秒，確保回應的時間與資料庫儲存值一致")
    void truncatesToMicroseconds() {
        Instant truncated = BusinessTime.truncateToDatabasePrecision(Instant.parse("2026-10-01T01:00:00.123456789Z"));

        assertThat(truncated).isEqualTo(Instant.parse("2026-10-01T01:00:00.123456Z"));
    }
}
