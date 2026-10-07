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
 * 所有方法都以 employee_id 限定範圍，且「今日／下班／提醒」一律只看有效（未作廢）的紀錄。
 * 寫入全部是原生的條件式 SQL，每次修改都同步 version = version + 1。
 */
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    /** 上班：沒有有效未完成組才新增。部分唯一索引為最後防線，跨列重疊由同交易員工鎖內檢查。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into attendance (employee_id, work_date, clock_in_at, created_at, updated_at)
            values (:employeeId, :workDate, :now, :now, :now)
            on conflict (employee_id, work_date) where voided_at is null and clock_out_at is null do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("employeeId") Long employeeId,
                       @Param("workDate") LocalDate workDate,
                       @Param("now") Instant now);

    /**
     * 下班：只在「有效、已上班且尚未下班」時填入時間。回傳 1＝成功；0＝沒有符合的紀錄。
     * 條件在同一個 UPDATE 內判斷，並行時第二個會等第一個提交再以最新資料重新檢查而更新 0 筆；
     * 同時 version + 1，讓依舊版本提出的申請在核准時被判定為過時。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update attendance set clock_out_at = :now, updated_at = :now, version = version + 1
             where employee_id = :employeeId and work_date = :workDate
               and id = :id and version = :version
               and voided_at is null and clock_out_at is null and clock_in_at <= :now
            """, nativeQuery = true)
    int clockOutIfOpen(@Param("employeeId") Long employeeId,
                       @Param("workDate") LocalDate workDate,
                       @Param("now") Instant now,
                       @Param("id") Long id,
                       @Param("version") long version);

    /** 作廢（重新打卡）：只在「版本相符且仍有效」時成功；原時間保留。回傳 0＝資料已被改過。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update attendance set voided_at = :now, voided_by = :voidedBy, updated_at = :now, version = version + 1
             where id = :id and version = :version and voided_at is null
            """, nativeQuery = true)
    int voidIfCurrent(@Param("id") Long id,
                      @Param("version") long version,
                      @Param("voidedBy") Long voidedBy,
                      @Param("now") Instant now);

    /** 更正時間：只在「版本相符且仍有效」時成功。回傳 0＝資料已被改過（不覆蓋較新的資料）。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update attendance set clock_in_at = :clockIn, clock_out_at = :clockOut,
                   corrected_at = :now, updated_at = :now, version = version + 1
             where id = :id and version = :version and voided_at is null
            """, nativeQuery = true)
    int correctIfCurrent(@Param("id") Long id,
                         @Param("version") long version,
                         @Param("clockIn") Instant clockIn,
                         @Param("clockOut") Instant clockOut,
                         @Param("now") Instant now);

    /** 補登：建立新的 MANUAL 時段。服務先在員工鎖內檢查其他有效時段；未完成唯一索引仍作最後防線。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into attendance (employee_id, work_date, clock_in_at, clock_out_at, source, corrected_at, created_at, updated_at)
            values (:employeeId, :workDate, :clockIn, :clockOut, 'MANUAL', :now, :now, :now)
            on conflict (employee_id, work_date) where voided_at is null and clock_out_at is null do nothing
            """, nativeQuery = true)
    int insertManualIfAbsent(@Param("employeeId") Long employeeId,
                             @Param("workDate") LocalDate workDate,
                             @Param("clockIn") Instant clockIn,
                             @Param("clockOut") Instant clockOut,
                             @Param("now") Instant now);

    /** 該日的所有有效時段，依上班時間排序。 */
    List<Attendance> findAllByEmployeeIdAndWorkDateAndVoidedAtIsNullOrderByClockInAtAscIdAsc(Long employeeId, LocalDate workDate);

    Optional<Attendance> findFirstByEmployeeIdAndWorkDateAndClockOutAtIsNullAndVoidedAtIsNull(Long employeeId, LocalDate workDate);

    Optional<Attendance> findFirstByEmployeeIdAndWorkDateAndVoidedAtIsNullOrderByIdDesc(Long employeeId, LocalDate workDate);

    /** 今日之前最近一筆「有效、有上班、沒下班」的紀錄（用來提醒使用者）；作廢紀錄不計。 */
    Optional<Attendance> findFirstByEmployeeIdAndWorkDateBeforeAndClockOutAtIsNullAndVoidedAtIsNullOrderByWorkDateDesc(
            Long employeeId, LocalDate before);

    /** 區間內的紀錄（含已作廢），日期新到舊；同日期以 id 新到舊（有效的在前面）。 */
    List<Attendance> findByEmployeeIdAndWorkDateBetweenOrderByWorkDateDescIdDesc(
            Long employeeId, LocalDate from, LocalDate to);
}
