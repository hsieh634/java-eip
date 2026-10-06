package com.renewflow.eip.attendance;

import com.renewflow.eip.support.TestDatabaseGuard;
import com.renewflow.eip.identity.Employee;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V2 migration：資料庫層的約束、乾淨庫建立、以及套用到「已有 V1 與資料」的資料庫。 */
class AttendanceSchemaTest extends AttendanceTestBase {

    @Autowired
    DataSource dataSource;
    @Autowired
    Environment env;

    private OffsetDateTime at(String instant) {
        return OffsetDateTime.ofInstant(Instant.parse(instant), ZoneOffset.UTC);
    }

    private void insertAttendance(Employee employee, String date, String in, String out) {
        jdbc.update("insert into attendance (employee_id, work_date, clock_in_at, clock_out_at) values (?, ?, ?, ?)",
                employee.getId(), LocalDate.parse(date), at(in), out == null ? null : at(out));
    }

    @Test
    @DisplayName("A-26／C-26 乾淨測試庫由 migration 建立：V1～V6 都成功；attendance 的欄位與約束存在")
    void cleanDatabaseHasV2() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7");

        List<String> constraints = jdbc.queryForList(
                "select conname from pg_constraint where conrelid = 'attendance'::regclass", String.class);
        assertThat(constraints).contains("ck_attendance_clock_out_after_in", "ck_attendance_source", "ck_attendance_void_pair")
                .as("V3 移除了無條件的唯一限制").doesNotContain("uq_attendance_employee_work_date");
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'uq_attendance_active_employee_work_date'", String.class))
                .as("每人每日最多一筆有效紀錄的部分唯一索引").containsIgnoringCase("UNIQUE").containsIgnoringCase("voided_at IS NULL");
        List<String> fkDeleteRules = jdbc.queryForList("""
                select rc.delete_rule from information_schema.referential_constraints rc
                  join information_schema.table_constraints tc on tc.constraint_name = rc.constraint_name
                 where tc.table_name = 'attendance' and tc.constraint_type = 'FOREIGN KEY'
                """, String.class);
        assertThat(fkDeleteRules).as("employee_id 與 voided_by 兩個外鍵").hasSize(2).containsOnly("RESTRICT");
    }

    @Test
    @DisplayName("A-27 資料庫約束：同員工同日期重複 → 拒絕")
    void uniqueEmployeeAndWorkDate() {
        insertAttendance(alice, "2026-10-01", "2026-10-01T01:00:00Z", null);

        assertThatThrownBy(() -> insertAttendance(alice, "2026-10-01", "2026-10-01T02:00:00Z", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 不同日期、不同員工不受影響
        insertAttendance(alice, "2026-10-02", "2026-10-02T01:00:00Z", null);
        insertAttendance(bob, "2026-10-01", "2026-10-01T01:00:00Z", null);
        assertThat(attendanceRows()).isEqualTo(3);
    }

    @Test
    @DisplayName("A-27 資料庫約束：下班時間早於上班時間 → 拒絕；相等允許")
    void clockOutMustNotBeBeforeClockIn() {
        assertThatThrownBy(() -> insertAttendance(alice, "2026-10-01", "2026-10-01T10:00:00Z", "2026-10-01T09:59:59Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertAttendance(alice, "2026-10-02", "2026-10-02T10:00:00Z", "2026-10-02T10:00:00Z");
        assertThat(attendanceRowsOf(alice)).isEqualTo(1);
    }

    @Test
    @DisplayName("資料庫約束：員工不存在的出勤 → 外鍵拒絕；沒有出勤的員工可以刪除")
    void foreignKey() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into attendance (employee_id, work_date, clock_in_at) values (?, ?, ?)",
                -1L, LocalDate.parse("2026-10-01"), at("2026-10-01T01:00:00Z")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("delete from employee where id = ?", bob.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("A-26 升級：在只有 V1、且已有員工資料的資料庫上套用 V2 → 新表建立、既有資料保留")
    void v2UpgradesExistingV1Database() throws SQLException {
        // 只對「測試庫」內的獨立 schema 操作，且先通過與 clean 相同的資料庫名稱防護
        try (Connection connection = dataSource.getConnection()) {
            TestDatabaseGuard.assertSafeToClean(TestDatabaseGuard.actualDatabase(connection),
                    env.getRequiredProperty("renewflow.test.database"),
                    env.getRequiredProperty("renewflow.test.dev-database"));
        }
        String schema = "migration_probe";
        Flyway v1Only = Flyway.configure().dataSource(dataSource).schemas(schema).createSchemas(true)
                .cleanDisabled(false).locations("classpath:db/migration").target("1").load();
        Flyway latest = Flyway.configure().dataSource(dataSource).schemas(schema)
                .cleanDisabled(false).locations("classpath:db/migration").load();
        try {
            v1Only.clean();
            v1Only.migrate();
            assertThat(tableExists(schema, "attendance")).as("只套用到 V1").isFalse();

            jdbc.update("insert into " + schema + ".employee (employee_no, username, display_name, password_hash, role)"
                    + " values ('U0001', 'legacy', 'Legacy user', 'hash-placeholder', 'EMPLOYEE')");

            latest.migrate(); // 升級到 V2

            assertThat(tableExists(schema, "attendance")).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".employee where username = 'legacy'", Integer.class))
                    .as("既有員工資料保留").isEqualTo(1);
            assertThat(jdbc.queryForList("select version from " + schema + ".flyway_schema_history where success and version is not null order by installed_rank",
                    String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7");
            // 升級後新表可用，且外鍵指向既有員工
            jdbc.update("insert into " + schema + ".attendance (employee_id, work_date, clock_in_at)"
                    + " select id, date '2026-10-01', now() from " + schema + ".employee where username = 'legacy'");
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".attendance", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private boolean tableExists(String schema, String table) {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = ? and table_name = ?",
                Integer.class, schema, table);
        return count != null && count > 0;
    }
}
