package com.renewflow.eip.attendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 所有方法都以 employee_id 限定範圍：沒有任何「只憑 ID 讀別人紀錄」的方法。
 */
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    /**
     * 上班：原子地「不存在才新增」。回傳 1＝新增成功；0＝（員工,日期）已有紀錄。
     *
     * <p>為什麼不是「先查有沒有，再 save」：兩個同時的請求可能都查到「沒有」。
     * 為什麼不是捕捉唯一限制例外：PostgreSQL 在約束錯誤後會把整個交易標為失敗，之後不能再查詢；
     * ON CONFLICT DO NOTHING 不產生錯誤，交易仍然健康，而且只處理這一個預期的衝突
     * （外鍵等其他完整性錯誤仍然會丟出例外，不會被誤判成「重複打卡」）。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into attendance (employee_id, work_date, clock_in_at, created_at, updated_at)
            values (:employeeId, :workDate, :now, :now, :now)
            on conflict (employee_id, work_date) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("employeeId") Long employeeId,
                       @Param("workDate") LocalDate workDate,
                       @Param("now") Instant now);

    /**
     * 下班：只在「已上班且尚未下班」時填入時間。回傳 1＝成功；0＝沒有符合的紀錄。
     * 條件在同一個 UPDATE 內判斷：兩個同時下班時，第二個會等第一個提交，再以最新資料重新檢查
     * clock_out_at is null，結果更新 0 筆，不會覆蓋第一次的下班時間。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update attendance set clock_out_at = :now, updated_at = :now
             where employee_id = :employeeId and work_date = :workDate
               and clock_out_at is null and clock_in_at <= :now
            """, nativeQuery = true)
    int clockOutIfOpen(@Param("employeeId") Long employeeId,
                       @Param("workDate") LocalDate workDate,
                       @Param("now") Instant now);

    Optional<Attendance> findByEmployeeIdAndWorkDate(Long employeeId, LocalDate workDate);

    /** 今日之前最近一筆「有上班、沒下班」的紀錄（用來提醒使用者）。 */
    Optional<Attendance> findFirstByEmployeeIdAndWorkDateBeforeAndClockOutAtIsNullOrderByWorkDateDesc(
            Long employeeId, LocalDate before);

    List<Attendance> findByEmployeeIdAndWorkDateBetweenOrderByWorkDateDesc(
            Long employeeId, LocalDate from, LocalDate to);
}
