package com.renewflow.eip.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 測試用可控制的時鐘：測試決定「現在是幾點」，不必修改作業系統時間。
 * 可設定自動前進：每次 instant() 回傳 base + n*step，讓並行請求拿到彼此不同的時間，
 * 這樣若有請求覆蓋了別人的時間，資料庫內的值就會與勝者的回應不一致而被測試抓到。
 */
public class MutableClock extends Clock {

    private volatile Instant base;
    private volatile Duration step = Duration.ZERO;
    private final AtomicLong ticks = new AtomicLong();

    public MutableClock(Instant initial) {
        this.base = initial;
    }

    public void set(Instant instant) {
        this.base = instant;
        this.step = Duration.ZERO;
        this.ticks.set(0);
    }

    /** 從 start 開始，每次讀取時間就前進 step。 */
    public void setAutoAdvance(Instant start, Duration step) {
        this.base = start;
        this.step = step;
        this.ticks.set(0);
    }

    @Override
    public Instant instant() {
        if (step.isZero()) {
            return base;
        }
        return base.plus(step.multipliedBy(ticks.getAndIncrement()));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
