package com.renewflow.eip.leave;

import com.renewflow.eip.leave.LeaveEnums.LeaveStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 請假申請的讀寫。讀取一律帶資料範圍條件（本人：employee_id；審核人：approver_id）；
 * 寫入是原生條件式 SQL，只在組織鎖內呼叫。
 */
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    /** requestId 只在同一位員工內比對：查詢一定帶 employee_id，無法用來探測他人的申請。 */
    Optional<LeaveRequest> findByEmployeeIdAndRequestId(Long employeeId, UUID requestId);

    Optional<LeaveRequest> findByIdAndEmployeeId(Long id, Long employeeId);

    Optional<LeaveRequest> findByIdAndApproverId(Long id, Long approverId);

    /** 有效內容與待審預留，歷史核准不占用。半開區間允許相鄰。 */
    @Query(value="""
        select r.* from leave_request r join leave_document d on d.id=r.document_id
         where r.employee_id=:employeeId and r.work_date=:workDate
          and (r.id=d.effective_request_id or (r.status='PENDING' and r.operation<>'CANCEL_ALL'))
          and r.start_time<:endTime and r.end_time>:startTime order by r.start_time,r.id
        """,nativeQuery=true)
    List<LeaveRequest> findOverlapping(@Param("employeeId") Long employeeId,@Param("workDate") LocalDate workDate,
                                     @Param("startTime") LocalTime startTime,@Param("endTime") LocalTime endTime);
    @Query(value="""
        select r.* from leave_request r join leave_document d on d.id=r.document_id
         where r.employee_id=:employeeId and r.document_id<>:documentId and r.work_date=:workDate
          and (r.id=d.effective_request_id or (r.status='PENDING' and r.operation<>'CANCEL_ALL'))
          and r.start_time<:endTime and r.end_time>:startTime order by r.start_time,r.id
        """,nativeQuery=true)
    List<LeaveRequest> findOverlappingExcludingDocument(@Param("employeeId") Long employeeId,@Param("documentId") Long documentId,
        @Param("workDate") LocalDate workDate,@Param("startTime") LocalTime startTime,@Param("endTime") LocalTime endTime);
    List<LeaveRequest> findByDocumentIdOrderByRevisionNoAsc(Long documentId);

    /**
     * 清單查詢。三種範圍共用：anyEmployee／anyApprover／anyDate 為 true 時忽略對應條件
     * （參數都不是 null，避免 PostgreSQL 推不出 null 參數的型別）。
     */
    @Query("""
            select r from LeaveRequest r
             where (:anyEmployee = true or r.employeeId = :employeeId)
               and (:anyApprover = true or r.approverId = :approverId)
               and r.status in :statuses
               and (:anyDate = true or r.workDate between :fromDate and :toDate)
            """)
    Page<LeaveRequest> search(@Param("anyEmployee") boolean anyEmployee, @Param("employeeId") long employeeId,
                              @Param("anyApprover") boolean anyApprover, @Param("approverId") long approverId,
                              @Param("statuses") Collection<LeaveStatus> statuses,
                              @Param("anyDate") boolean anyDate, @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate,
                              Pageable pageable);

    /** 新增：同員工＋同 requestId 已存在時不丟例外、回傳空（唯一限制是最後一道防線）。 */
    @Query(value = """
            insert into leave_request (employee_id, request_id, leave_type, work_date, start_time, end_time, minutes, reason,
                                       status, approver_id, approver_source, version, created_at, updated_at)
            values (:employeeId, :requestId, :leaveType, :workDate, :startTime, :endTime, :minutes, :reason,
                    'PENDING', :approverId, :approverSource, 0, :now, :now)
            on conflict (employee_id, request_id) do nothing
            returning id
            """, nativeQuery = true)
    Optional<Long> insertIfNew(@Param("employeeId") Long employeeId, @Param("requestId") UUID requestId,
                               @Param("leaveType") String leaveType, @Param("workDate") LocalDate workDate,
                               @Param("startTime") LocalTime startTime, @Param("endTime") LocalTime endTime,
                               @Param("minutes") int minutes, @Param("reason") String reason,
                               @Param("approverId") Long approverId, @Param("approverSource") String approverSource,
                               @Param("now") Instant now);

    /** 結案（核准、退回、撤回）：只有「版本相符且仍是待審」才會更新。回傳 0＝已被改過或已結案。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update leave_request
               set status = :toStatus, decided_by = :actorId, decided_at = :now, decision_reason = :reason,
                   version = version + 1, updated_at = :now
             where id = :id and version = :expectedVersion and status = 'PENDING'
            """, nativeQuery = true)
    int decideIfPending(@Param("id") Long id, @Param("expectedVersion") long expectedVersion, @Param("toStatus") String toStatus,
                        @Param("actorId") Long actorId, @Param("reason") String reason, @Param("now") Instant now);

    /** 改派：狀態不變、版本 +1。只有「版本相符且仍是待審」才會更新。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update leave_request
               set approver_id = :approverId, approver_source = 'REASSIGNED', version = version + 1, updated_at = :now
             where id = :id and version = :expectedVersion and status = 'PENDING'
            """, nativeQuery = true)
    int reassignIfPending(@Param("id") Long id, @Param("expectedVersion") long expectedVersion,
                          @Param("approverId") Long approverId, @Param("now") Instant now);
}
