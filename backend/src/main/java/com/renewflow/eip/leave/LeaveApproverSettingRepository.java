package com.renewflow.eip.leave;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** 指定請假審核人設定。寫入只在組織鎖內呼叫。 */
public interface LeaveApproverSettingRepository extends JpaRepository<LeaveApproverSetting, Long> {

    /** 目前「有指定」的設定（approver_id 不是 null）。 */
    List<LeaveApproverSetting> findByApproverIdIsNotNullOrderByEmployeeIdAsc();

    /** 第一次設定：版本 1。主鍵衝突時不丟例外（鎖內不會發生；最後一道防線），回傳 0。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into leave_approver_setting (employee_id, approver_id, version, updated_by, updated_at)
            values (:employeeId, :approverId, 1, :actorId, :now)
            on conflict (employee_id) do nothing
            """, nativeQuery = true)
    int insertFirst(@Param("employeeId") Long employeeId, @Param("approverId") Long approverId,
                    @Param("actorId") Long actorId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update leave_approver_setting set approver_id = :approverId, version = version + 1, updated_by = :actorId, updated_at = :now
             where employee_id = :employeeId and version = :expectedVersion
            """, nativeQuery = true)
    int updateIfVersion(@Param("employeeId") Long employeeId, @Param("expectedVersion") long expectedVersion,
                        @Param("approverId") Long approverId, @Param("actorId") Long actorId, @Param("now") Instant now);
}
