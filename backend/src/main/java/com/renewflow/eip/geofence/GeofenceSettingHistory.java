package com.renewflow.eip.geofence;

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

/** 對應 geofence_setting_history。只新增、不修改（資料庫觸發器也會擋 UPDATE／DELETE）。 */
@Entity
@Immutable
@Table(name = "geofence_setting_history")
public class GeofenceSettingHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "setting_version", nullable = false)
    private long settingVersion;

    @Column(name = "before_enabled", nullable = false)
    private boolean beforeEnabled;
    @Column(name = "before_center_latitude")
    private Double beforeLatitude;
    @Column(name = "before_center_longitude")
    private Double beforeLongitude;
    @Column(name = "before_radius_meters", nullable = false)
    private int beforeRadius;
    @Enumerated(EnumType.STRING)
    @Column(name = "before_scope", nullable = false, length = 10)
    private GeofenceScope beforeScope;

    @Column(name = "after_enabled", nullable = false)
    private boolean afterEnabled;
    @Column(name = "after_center_latitude")
    private Double afterLatitude;
    @Column(name = "after_center_longitude")
    private Double afterLongitude;
    @Column(name = "after_radius_meters", nullable = false)
    private int afterRadius;
    @Enumerated(EnumType.STRING)
    @Column(name = "after_scope", nullable = false, length = 10)
    private GeofenceScope afterScope;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected GeofenceSettingHistory() {
    }

    GeofenceSettingHistory(GeofenceSnapshot before, GeofenceSnapshot after, String reason, Long actorId, Instant occurredAt) {
        this.settingVersion = after.version();
        this.beforeEnabled = before.enabled();
        this.beforeLatitude = before.centerLatitude();
        this.beforeLongitude = before.centerLongitude();
        this.beforeRadius = before.radiusMeters();
        this.beforeScope = before.scope();
        this.afterEnabled = after.enabled();
        this.afterLatitude = after.centerLatitude();
        this.afterLongitude = after.centerLongitude();
        this.afterRadius = after.radiusMeters();
        this.afterScope = after.scope();
        this.reason = reason;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public long getSettingVersion() {
        return settingVersion;
    }

    public GeofenceSnapshot before() {
        return new GeofenceSnapshot(settingVersion - 1, beforeEnabled, beforeLatitude, beforeLongitude, beforeRadius, beforeScope, null, null);
    }

    public GeofenceSnapshot after() {
        return new GeofenceSnapshot(settingVersion, afterEnabled, afterLatitude, afterLongitude, afterRadius, afterScope, actorId, occurredAt);
    }

    public String getReason() {
        return reason;
    }

    public Long getActorId() {
        return actorId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
