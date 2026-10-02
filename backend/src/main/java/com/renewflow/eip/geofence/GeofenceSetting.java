package com.renewflow.eip.geofence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * 對應 geofence_setting（只有 id = 1 一列）。
 * 標成 @Immutable：只用來讀取；修改一律走 {@link GeofenceSettingRepository#updateIfVersion} 的條件式 UPDATE，
 * 不靠 JPA 的「改欄位自動寫回」，避免繞過版本比對。
 */
@Entity
@Immutable
@Table(name = "geofence_setting")
public class GeofenceSetting {

    public static final short SINGLETON_ID = 1;

    @Id
    private Short id;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "center_latitude")
    private Double centerLatitude;

    @Column(name = "center_longitude")
    private Double centerLongitude;

    @Column(name = "radius_meters", nullable = false)
    private int radiusMeters;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private GeofenceScope scope;

    @Column(nullable = false)
    private long version;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected GeofenceSetting() {
    }

    /** 轉成不可變的快照：一次打卡或一次修改只使用這一份，不會在中途混到別的版本。 */
    public GeofenceSnapshot snapshot() {
        return new GeofenceSnapshot(version, enabled, centerLatitude, centerLongitude, radiusMeters, scope, updatedBy, updatedAt);
    }
}
