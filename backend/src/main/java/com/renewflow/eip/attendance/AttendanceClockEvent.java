package com.renewflow.eip.attendance;

import com.renewflow.eip.geofence.ClockAction;
import com.renewflow.eip.geofence.GeofenceScope;
import com.renewflow.eip.geofence.GeofenceSnapshot;
import com.renewflow.eip.geofence.LocationDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * 對應 attendance_clock_event：一次「成功」打卡的原始證據，只新增（資料庫觸發器擋 UPDATE／DELETE）。
 * 與 attendance 分開保存：出勤時間之後可能被更正、作廢、補登，但這裡記錄的是「當時實際發生的打卡」，不會被改寫。
 */
@Entity
@Immutable
@Table(name = "attendance_clock_event")
public class AttendanceClockEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "attendance_id", nullable = false)
    private Long attendanceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ClockAction action;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    @Column(name = "location_required", nullable = false)
    private boolean locationRequired;

    @Column(name = "setting_version", nullable = false)
    private long settingVersion;

    @Column(name = "setting_enabled", nullable = false)
    private boolean settingEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "setting_scope", nullable = false, length = 10)
    private GeofenceScope settingScope;

    @Column(name = "setting_center_latitude")
    private Double settingCenterLatitude;

    @Column(name = "setting_center_longitude")
    private Double settingCenterLongitude;

    @Column(name = "setting_radius_meters", nullable = false)
    private int settingRadiusMeters;

    private Double latitude;

    private Double longitude;

    @Column(name = "accuracy_meters")
    private Double accuracyMeters;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "distance_meters")
    private Double distanceMeters;

    protected AttendanceClockEvent() {
    }

    AttendanceClockEvent(Long employeeId, Long attendanceId, ClockAction action, Instant acceptedAt, LocationDecision decision) {
        GeofenceSnapshot setting = decision.setting();
        this.employeeId = employeeId;
        this.attendanceId = attendanceId;
        this.action = action;
        this.acceptedAt = acceptedAt;
        this.locationRequired = decision.required();
        this.settingVersion = setting.version();
        this.settingEnabled = setting.enabled();
        this.settingScope = setting.scope();
        this.settingCenterLatitude = setting.centerLatitude();
        this.settingCenterLongitude = setting.centerLongitude();
        this.settingRadiusMeters = setting.radiusMeters();
        this.latitude = decision.latitude();
        this.longitude = decision.longitude();
        this.accuracyMeters = decision.accuracyMeters();
        this.capturedAt = decision.capturedAt();
        this.distanceMeters = decision.distanceMeters();
    }

    public Long getId() {
        return id;
    }
}
