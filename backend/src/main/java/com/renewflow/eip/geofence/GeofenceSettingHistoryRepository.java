package com.renewflow.eip.geofence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GeofenceSettingHistoryRepository extends JpaRepository<GeofenceSettingHistory, Long> {

    List<GeofenceSettingHistory> findTop100ByOrderByIdDesc();
}
