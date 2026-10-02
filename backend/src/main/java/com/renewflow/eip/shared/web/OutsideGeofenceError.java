package com.renewflow.eip.shared.web;

/** 403 OUTSIDE_GEOFENCE 的回應：ApiError 的欄位，再加上後端計算的距離（公尺、未取整）與允許半徑。 */
public record OutsideGeofenceError(String code, String message, double distanceMeters, int radiusMeters) {
}
