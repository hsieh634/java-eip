package com.renewflow.eip.geofence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * 定位設定的讀寫。鎖定規則（見 docs/renewflow/t03-2/02-assumptions.md D-31）：
 * <ul>
 *   <li>打卡：{@link #lockForClock()}（FOR SHARE）。多個打卡可以同時持有；但只要有人持有，修改設定就要等。</li>
 *   <li>修改：{@link #lockForUpdate()}（FOR UPDATE）。會等進行中的打卡交易結束；修改提交之前，新的打卡也會等，
 *       之後讀到的就是新版本。</li>
 * </ul>
 * 兩者都必須在交易內呼叫，鎖會持有到交易結束。
 */
public interface GeofenceSettingRepository extends JpaRepository<GeofenceSetting, Short> {

    @Query(value = "select * from geofence_setting where id = 1 for share", nativeQuery = true)
    Optional<GeofenceSetting> lockForClock();

    @Query(value = "select * from geofence_setting where id = 1 for update", nativeQuery = true)
    Optional<GeofenceSetting> lockForUpdate();

    /** 只讀、不加鎖（顯示用）。 */
    @Query(value = "select * from geofence_setting where id = 1", nativeQuery = true)
    Optional<GeofenceSetting> current();

    /** 版本相符才更新，同時 version + 1。回傳 0＝設定已被別人改過。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update geofence_setting
               set enabled = :enabled, center_latitude = :latitude, center_longitude = :longitude,
                   radius_meters = :radius, scope = :scope, version = version + 1,
                   updated_by = :actorId, updated_at = :now
             where id = 1 and version = :expectedVersion
            """, nativeQuery = true)
    int updateIfVersion(@Param("expectedVersion") long expectedVersion,
                        @Param("enabled") boolean enabled,
                        @Param("latitude") Double latitude,
                        @Param("longitude") Double longitude,
                        @Param("radius") int radius,
                        @Param("scope") String scope,
                        @Param("actorId") Long actorId,
                        @Param("now") Instant now);
}
