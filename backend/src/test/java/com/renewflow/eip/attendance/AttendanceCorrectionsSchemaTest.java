package com.renewflow.eip.attendance;

import com.renewflow.eip.support.TestDatabaseGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V3 migration 在資料庫層的保證：部分唯一索引、約束、歷程只新增，以及從既有 V2 升級。 */
class AttendanceCorrectionsSchemaTest extends CorrectionsTestBase {

    @Autowired
    DataSource dataSource;
    @Autowired
    Environment env;

    private OffsetDateTime at(String instant) {
        return OffsetDateTime.ofInstant(Instant.parse(instant), ZoneOffset.UTC);
    }

    private void insertAttendance(long employeeId, String date, String voidedAt, Long voidedBy) {
        jdbc.update("insert into attendance (employee_id, work_date, clock_in_at, voided_at, voided_by) values (?, ?, ?, ?, ?)",
                employeeId, LocalDate.parse(date), at("2026-10-01T01:00:00Z"), voidedAt == null ? null : at(voidedAt), voidedBy);
    }

    @Test
    @DisplayName("C-27 部分唯一索引：同員工同日只能有一筆「有效」紀錄，但可以有多筆作廢紀錄")
    void onlyOneActiveRecordButManyVoided() {
        insertAttendance(alice.getId(), "2026-10-01", "2026-10-01T02:00:00Z", carol.getId());
        insertAttendance(alice.getId(), "2026-10-01", "2026-10-01T03:00:00Z", carol.getId());
        insertAttendance(alice.getId(), "2026-10-01", null, null);

        assertThatThrownBy(() -> insertAttendance(alice.getId(), "2026-10-01", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from attendance where employee_id = ?", Integer.class, alice.getId())).isEqualTo(3);
    }

    @Autowired
    AttendanceRepository attendanceRepository;
    @Autowired
    org.springframework.transaction.support.TransactionTemplate transactions;

    @Test
    @DisplayName("C-27 INSERT ... ON CONFLICT 的目標與部分索引相符：只有作廢紀錄時可再新增（1）；已有有效紀錄時回傳 0 而不是丟例外")
    void onConflictTargetMatchesPartialIndex() {
        insertAttendance(alice.getId(), "2026-10-01", "2026-10-01T02:00:00Z", carol.getId());

        int first = transactions.execute(status -> attendanceRepository.insertIfAbsent(alice.getId(), LocalDate.parse("2026-10-01"), Instant.parse("2026-10-01T06:00:00Z")));
        int second = transactions.execute(status -> attendanceRepository.insertIfAbsent(alice.getId(), LocalDate.parse("2026-10-01"), Instant.parse("2026-10-01T06:01:00Z")));
        int manual = transactions.execute(status -> attendanceRepository.insertManualIfAbsent(alice.getId(), LocalDate.parse("2026-10-01"),
                Instant.parse("2026-10-01T01:00:00Z"), null, Instant.parse("2026-10-01T06:02:00Z")));

        assertThat(first).as("只有作廢紀錄 → 新增成功").isEqualTo(1);
        assertThat(second).as("已有有效紀錄 → 不新增、不丟例外").isZero();
        assertThat(manual).as("補登同樣尊重部分唯一索引").isZero();
        assertThat(activeRows(alice)).isEqualTo(1);
        assertThat(voidedRows(alice)).isEqualTo(1);
    }

    @Test
    @DisplayName("C-27 作廢標記必須成對（voided_at 與 voided_by）")
    void voidMarkersMustBePaired() {
        assertThatThrownBy(() -> insertAttendance(alice.getId(), "2026-10-01", "2026-10-01T02:00:00Z", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAttendance(alice.getId(), "2026-10-02", null, carol.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertRequest(String status, String type, String reason, Long reviewerId, String reviewedAt, String comment) {
        jdbc.update("""
                insert into attendance_request (employee_id, request_type, status, work_date, requested_clock_in_at, reason, reviewer_id, reviewed_at, review_comment)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, alice.getId(), type, status, LocalDate.parse("2026-09-30"), at("2026-09-30T01:00:00Z"), reason,
                reviewerId, reviewedAt == null ? null : at(reviewedAt), comment);
    }

    @Test
    @DisplayName("C-27 待審申請的部分唯一索引：同員工同日只能一筆 PENDING；已處理的不佔名額")
    void onlyOnePendingRequestPerEmployeeDay() {
        insertRequest("PENDING", "CORRECT_TIME", "第一筆", null, null, null);
        assertThatThrownBy(() -> insertRequest("PENDING", "CORRECT_TIME", "第二筆", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertRequest("CANCELLED", "CORRECT_TIME", "已撤回的不佔名額", null, null, null);
        insertRequest("APPROVED", "CORRECT_TIME", "已核准的不佔名額", carol.getId(), "2026-10-01T02:00:00Z", null);
        assertThat(requestRows()).isEqualTo(3);
    }

    @Test
    @DisplayName("申請表約束：原因不可空白、狀態與審核資訊一致、退回必有原因、CORRECT_TIME 必有上班時間、類型／狀態合法")
    void requestConstraints() {
        assertThatThrownBy(() -> insertRequest("PENDING", "CORRECT_TIME", "   ", null, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRequest("APPROVED", "CORRECT_TIME", "沒有審核人", null, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRequest("PENDING", "CORRECT_TIME", "待審卻有審核人", carol.getId(), "2026-10-01T02:00:00Z", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRequest("REJECTED", "CORRECT_TIME", "退回沒原因", carol.getId(), "2026-10-01T02:00:00Z", " ")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRequest("BOGUS", "CORRECT_TIME", "狀態不合法", null, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRequest("PENDING", "BOGUS", "類型不合法", null, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into attendance_request (employee_id, request_type, status, work_date, reason) values (?, 'CORRECT_TIME', 'PENDING', date '2026-09-30', 'x')",
                alice.getId())).as("CORRECT_TIME 沒有上班時間").isInstanceOf(DataIntegrityViolationException.class);
        assertThat(requestRows()).isZero();
    }

    private void insertHistory() {
        jdbc.update("insert into attendance_history (employee_id, work_date, operation, actor_id, occurred_at) values (?, date '2026-10-01', 'DIRECT_CORRECTION', ?, now())",
                alice.getId(), carol.getId());
    }

    @Test
    @DisplayName("C-24 歷程只能新增：資料庫拒絕 UPDATE 與 DELETE（連直接下 SQL 也一樣）；operation 必須合法")
    void historyIsAppendOnly() {
        insertHistory();

        assertThatThrownBy(() -> jdbc.update("update attendance_history set reason = 'tampered'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from attendance_history"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(historyRows()).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("insert into attendance_history (employee_id, work_date, operation, actor_id, occurred_at) values (?, date '2026-10-01', 'BOGUS', ?, now())",
                alice.getId(), carol.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("有歷程的員工無法被刪除（外鍵 RESTRICT）；歷程不會被連帶清掉")
    void employeesWithHistoryCannotBeDeleted() {
        insertHistory();

        assertThatThrownBy(() -> jdbc.update("delete from employee where id = ?", alice.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from employee where id = ?", carol.getId()))
                .as("操作者也不能被刪").isInstanceOf(DataIntegrityViolationException.class);
        assertThat(historyRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("C-26 升級：在只有 V2、且已有員工與出勤資料的資料庫套用 V3 → 資料原封不動，新欄位為預設值，新表可用")
    void v3UpgradesExistingV2Database() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            TestDatabaseGuard.assertSafeToClean(TestDatabaseGuard.actualDatabase(connection),
                    env.getRequiredProperty("renewflow.test.database"), env.getRequiredProperty("renewflow.test.dev-database"));
        }
        String schema = "migration_probe_v3";
        Flyway v2Only = Flyway.configure().dataSource(dataSource).schemas(schema).createSchemas(true)
                .cleanDisabled(false).locations("classpath:db/migration").target("2").load();
        // 固定到 V3：本測試驗證的是「V2 → V3」這一步（V4 的升級另由 GeofenceSchemaTest 驗證）
        Flyway latest = Flyway.configure().dataSource(dataSource).schemas(schema)
                .cleanDisabled(false).locations("classpath:db/migration").target("3").load();
        try {
            v2Only.clean();
            v2Only.migrate();
            jdbc.update("insert into " + schema + ".employee (employee_no, username, display_name, password_hash, role)"
                    + " values ('U0001', 'legacy', 'Legacy user', 'hash-placeholder', 'EMPLOYEE')");
            jdbc.update("insert into " + schema + ".attendance (employee_id, work_date, clock_in_at, clock_out_at)"
                    + " select id, date '2026-10-01', timestamptz '2026-10-01 01:26:23+00', timestamptz '2026-10-01 01:26:25+00' from " + schema + ".employee");

            latest.migrate(); // V2 → V3

            var row = jdbc.queryForMap("select (clock_in_at at time zone 'UTC')::text as i, (clock_out_at at time zone 'UTC')::text as o, version, source, voided_at, voided_by, corrected_at from "
                    + schema + ".attendance");
            assertThat(row.get("i")).asString().isEqualTo("2026-10-01 01:26:23");
            assertThat(row.get("o")).asString().isEqualTo("2026-10-01 01:26:25");
            assertThat(row.get("version")).isEqualTo(0L);
            assertThat(row.get("source")).isEqualTo("CLOCK");
            assertThat(row.get("voided_at")).isNull();
            assertThat(row.get("voided_by")).isNull();
            assertThat(row.get("corrected_at")).isNull();
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".employee", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForList("select version from " + schema + ".flyway_schema_history where success and version is not null order by installed_rank",
                    String.class)).containsExactly("1", "2", "3");
            // 新表可用
            jdbc.update("insert into " + schema + ".attendance_request (employee_id, request_type, status, work_date, reason)"
                    + " select id, 'RESET_TODAY', 'PENDING', date '2026-10-01', '升級後可用' from " + schema + ".employee");
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".attendance_request", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }
}
