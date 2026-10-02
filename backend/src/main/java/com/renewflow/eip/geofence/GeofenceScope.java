package com.renewflow.eip.geofence;

/** 定位限制的適用操作。 */
public enum GeofenceScope {
    /** 只限制上班 */
    CLOCK_IN,
    /** 只限制下班 */
    CLOCK_OUT,
    /** 上下班都限制 */
    BOTH;

    public boolean covers(ClockAction action) {
        return this == BOTH || name().equals(action.name());
    }
}
