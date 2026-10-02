package com.renewflow.eip.geofence;

import java.time.Duration;

/**
 * 定位打卡的工程假設（不是公司正式制度），集中在這裡，前端經 API 取得同一組數值，不自行寫死。
 * 見 docs/renewflow/t03-2/02-assumptions.md（A-30～A-35）。
 */
public final class GeofenceRules {

    /** 允許的半徑範圍（公尺）。資料庫 ck_geofence_radius 也是同一組數字。 */
    public static final int RADIUS_MIN_METERS = 10;
    public static final int RADIUS_MAX_METERS = 5000;
    public static final int DEFAULT_RADIUS_METERS = 100;

    /** 可接受的定位精度上限（公尺）。accuracy 是瀏覽器提供的 95% 信心半徑，不會被加進允許半徑。 */
    public static final double MAX_ACCURACY_METERS = 50;

    /** 定位時間的接收窗口：伺服器現在往前 60 秒、往後 10 秒（容許用戶端時鐘的小偏差）。 */
    public static final Duration LOCATION_MAX_AGE = Duration.ofSeconds(60);
    public static final Duration LOCATION_MAX_AHEAD = Duration.ofSeconds(10);

    private GeofenceRules() {
    }
}
