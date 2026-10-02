package com.renewflow.eip.geofence;

import org.springframework.http.HttpStatus;

/** 403 OUTSIDE_GEOFENCE：附上後端計算的距離（未取整）與允許半徑，讓畫面能說明原因。 */
public class OutsideGeofenceException extends GeofenceException {

    private final double distanceMeters;
    private final int radiusMeters;

    public OutsideGeofenceException(double distanceMeters, int radiusMeters) {
        super(HttpStatus.FORBIDDEN, "OUTSIDE_GEOFENCE",
                "目前位置距離公司約 %d 公尺，超出允許範圍 %d 公尺".formatted(Math.round(distanceMeters), radiusMeters));
        this.distanceMeters = distanceMeters;
        this.radiusMeters = radiusMeters;
    }

    public double getDistanceMeters() {
        return distanceMeters;
    }

    public int getRadiusMeters() {
        return radiusMeters;
    }
}
