package com.renewflow.eip.geofence;

/**
 * 球面距離（Haversine）。輸入是十進位度數，輸出是公尺。
 *
 * <p>把地球當成半徑 6,371,008.8 公尺的球（IUGG 平均半徑）。真實地球是橢球，在公司範圍這種公尺級的距離，
 * 這個近似的誤差遠小於瀏覽器定位本身的誤差（accuracy 常是數公尺到數十公尺）。
 * 回傳值不取整：是否在範圍內由呼叫端以 {@code distance <= radius} 判斷。
 */
public final class GeoDistance {

    public static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoDistance() {
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lon2 - lon1);

        double sinHalfPhi = Math.sin(deltaPhi / 2);
        double sinHalfLambda = Math.sin(deltaLambda / 2);
        double a = sinHalfPhi * sinHalfPhi + Math.cos(phi1) * Math.cos(phi2) * sinHalfLambda * sinHalfLambda;
        // 浮點誤差可能讓 a 稍微超出 [0, 1]，夾住後 asin／sqrt 才不會得到 NaN
        a = Math.min(1.0, Math.max(0.0, a));
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
    }

    /** 範圍判斷：距離小於或等於半徑才算在範圍內。不先取整、不加上 accuracy。 */
    public static boolean isWithin(double distanceMeters, int radiusMeters) {
        return distanceMeters <= radiusMeters;
    }
}
