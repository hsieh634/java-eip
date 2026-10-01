package com.renewflow.eip.shared.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 時間來源。所有「現在幾點」都必須透過注入的 Clock 取得，不直接呼叫 Instant.now()，
 * 這樣測試可以換成可控制的 Clock，不必修改作業系統時間。
 */
@Configuration
class TimeConfig {

    @Bean
    Clock clock() {
        // UTC：Clock 只提供「時間點」；要不要換算成台北日期，由 BusinessTime 負責
        return Clock.systemUTC();
    }
}
