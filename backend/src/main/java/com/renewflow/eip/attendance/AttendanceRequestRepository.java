package com.renewflow.eip.attendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceRequestRepository extends JpaRepository<AttendanceRequest, Long> {

    /**
     * 建立待審申請。回傳新申請的 id；若該員工該日已有待審申請則回傳空（資料庫部分唯一索引
     * uq_attendance_request_pending 裁決，衝突時不產生例外、交易仍然健康）。
     */
    @Query(value = """
            insert into attendance_request (employee_id, request_type, status, work_date,
                   target_attendance_id, target_version, original_clock_in_at, original_clock_out_at,
                   requested_clock_in_at, requested_clock_out_at, reason, created_at, updated_at)
            values (:employeeId, :type, 'PENDING', :workDate,
                   :targetId, :targetVersion, :originalIn, :originalOut,
                   :requestedIn, :requestedOut, :reason, :now, :now)
            on conflict (employee_id, work_date) where status = 'PENDING' do nothing
            returning id
            """, nativeQuery = true)
    Optional<Long> insertPending(@Param("employeeId") Long employeeId,
                                 @Param("type") String type,
                                 @Param("workDate") LocalDate workDate,
                                 @Param("targetId") Long targetId,
                                 @Param("targetVersion") Long targetVersion,
                                 @Param("originalIn") Instant originalIn,
                                 @Param("originalOut") Instant originalOut,
                                 @Param("requestedIn") Instant requestedIn,
                                 @Param("requestedOut") Instant requestedOut,
                                 @Param("reason") String reason,
                                 @Param("now") Instant now);

    /**
     * 審核（核准／退回）：只有仍是 PENDING 才會成功，回傳 0＝已被處理過。
     * 這個 UPDATE 會鎖住申請列，因此同一申請的並行核准／退回／撤回只會有一個成功。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update attendance_request
               set status = :status, reviewer_id = :reviewerId, reviewed_at = :now,
                   review_comment = :comment, updated_at = :now
             where id = :id and status = 'PENDING'
            """, nativeQuery = true)
    int review(@Param("id") Long id,
               @Param("status") String status,
               @Param("reviewerId") Long reviewerId,
               @Param("comment") String comment,
               @Param("now") Instant now);

    /** 撤回：只有本人且仍是 PENDING 才會成功。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update attendance_request set status = 'CANCELLED', updated_at = :now
             where id = :id and employee_id = :employeeId and status = 'PENDING'
            """, nativeQuery = true)
    int cancel(@Param("id") Long id, @Param("employeeId") Long employeeId, @Param("now") Instant now);

    Optional<AttendanceRequest> findByIdAndEmployeeId(Long id, Long employeeId);

    List<AttendanceRequest> findTop50ByEmployeeIdOrderByIdDesc(Long employeeId);

    /** 管理端：依狀態列出，舊的在前（先來先審）。 */
    List<AttendanceRequest> findTop200ByStatusOrderByIdAsc(AttendanceRequestStatus status);

    List<AttendanceRequest> findTop200ByOrderByIdDesc();
}
