package com.renewflow.eip.timesheet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 所有查詢都以 employee_id 限定範圍（資料所有權）；寫入是原生條件式 SQL。 */
public interface TimesheetEntryRepository extends JpaRepository<TimesheetEntry, Long> {

    /**
     * 寫入本人任何工時之前，先鎖住該員工的那一列（FOR NO KEY UPDATE），持有到交易結束：
     * 同一個人的工時新增／修改因此排隊執行，「讀出當日合計 → 檢查上限 → 寫入」不會被同一人的另一個請求插隊。
     * 不同員工鎖的是不同列，彼此不阻擋。選 NO KEY UPDATE（而不是 FOR UPDATE）是因為它不會擋住
     * 其他表（例如打卡）新增引用這位員工的外鍵檢查（那些只需要 FOR KEY SHARE）。
     */
    @Query(value = "select id from employee where id = :employeeId for no key update", nativeQuery = true)
    Optional<Long> lockEmployee(@Param("employeeId") Long employeeId);

    Optional<TimesheetEntry> findByEmployeeIdAndRequestId(Long employeeId, UUID requestId);

    /** 只找「自己的」紀錄；別人的紀錄與不存在一樣都是空的。 */
    Optional<TimesheetEntry> findByIdAndEmployeeId(Long id, Long employeeId);

    /** 某人某日的合計分鐘數，排除指定的一筆（修改時排除自己的舊值；新增時傳 -1）。 */
    @Query(value = """
            select coalesce(sum(minutes), 0) from timesheet_entry
             where employee_id = :employeeId and work_date = :workDate and id <> :excludeId
            """, nativeQuery = true)
    int sumMinutesOfDay(@Param("employeeId") Long employeeId,
                        @Param("workDate") LocalDate workDate,
                        @Param("excludeId") long excludeId);

    /**
     * 新增：同一員工的 requestId 已存在時不丟例外、回傳空（由 uq_timesheet_employee_request 裁決）。
     * 交易仍然健康，之後可以安全地再查詢。
     */
    @Query(value = """
            insert into timesheet_entry (employee_id, project_id, work_date, description, minutes, request_id, created_at, updated_at)
            values (:employeeId, :projectId, :workDate, :description, :minutes, :requestId, :now, :now)
            on conflict (employee_id, request_id) do nothing
            returning id
            """, nativeQuery = true)
    Optional<Long> insertIfRequestNew(@Param("employeeId") Long employeeId,
                                      @Param("projectId") Long projectId,
                                      @Param("workDate") LocalDate workDate,
                                      @Param("description") String description,
                                      @Param("minutes") int minutes,
                                      @Param("requestId") UUID requestId,
                                      @Param("now") Instant now);

    /** 修改：必須是自己的、版本相符才更新，同時 version + 1。回傳 0＝已被改過（或不是自己的）。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update timesheet_entry
               set project_id = :projectId, work_date = :workDate, description = :description, minutes = :minutes,
                   version = version + 1, updated_at = :now
             where id = :id and employee_id = :employeeId and version = :expectedVersion
            """, nativeQuery = true)
    int updateIfVersion(@Param("id") Long id,
                        @Param("employeeId") Long employeeId,
                        @Param("expectedVersion") long expectedVersion,
                        @Param("projectId") Long projectId,
                        @Param("workDate") LocalDate workDate,
                        @Param("description") String description,
                        @Param("minutes") int minutes,
                        @Param("now") Instant now);

    /** 本人在範圍內的紀錄：日期新到舊，同日期依 id 新到舊。 */
    List<TimesheetEntry> findByEmployeeIdAndWorkDateBetweenOrderByWorkDateDescIdDesc(Long employeeId, LocalDate from, LocalDate to);
}
