package com.renewflow.eip.geofence;

import org.springframework.http.HttpStatus;

/**
 * 定位打卡與定位設定的預期拒絕。狀態碼與 code 由例外指定（400／422／403／409），
 * 資料庫故障等其他例外不會被包裝成它，維持 500。
 */
public class GeofenceException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public GeofenceException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
