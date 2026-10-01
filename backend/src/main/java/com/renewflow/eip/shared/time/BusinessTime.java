package com.renewflow.eip.shared.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 業務時間規則：本系統的業務日期固定使用 Asia/Taipei（學習假設 A-07，不代表任何公司制度）。
 *
 * <p>重點：同一個時間點，UTC 日期與台北日期可能不同。例如 2026-10-01T16:30:00Z 在 UTC 是 10/1，
 * 在台北已是 10/2 的 00:30，所以業務日期是 10/2。
 */
public final class BusinessTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Taipei");

    private BusinessTime() {
    }

    /** 時間點對應的業務日期。 */
    public static LocalDate workDateOf(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    /** PostgreSQL 的 timestamptz 精度是微秒；先截斷，確保「回給前端的值」與「資料庫存的值」完全一致。 */
    public static Instant truncateToDatabasePrecision(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
