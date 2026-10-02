package com.renewflow.eip.attendance;

import com.renewflow.eip.support.TestDatabaseGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V4 migration：乾淨庫的預設值，以及從已有資料的 V3 升級時，原資料完全保留。 */
class GeofenceSchemaTest extends GeofenceTestBase {

    @Autowired
    DataSource dataSource;
    @Autowired
    Environment env;

    @Test
    @DisplayName("M-01 乾淨測試庫：V4 建立預設設定——關閉、公司中心未設定、半徑 100、上下班都限制、版本 0、沒有修改人")
    void defaultSettingRow() {
        Map<String, Object> row = jdbc.queryForMap("select * from geofence_setting");
        assertThat(((Number) row.get("id")).intValue()).isEqualTo(1);
        assertThat(row.get("enabled")).isEqualTo(false);
        assertThat(row.get("center_latitude")).isNull();
        assertThat(row.get("center_longitude")).isNull();
        assertThat(row.get("radius_meters")).isEqualTo(100);
        assertThat(row.get("scope")).isEqualTo("BOTH");
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(row.get("updated_by")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from geofence_setting", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("M-02 升級：在已有 V3 資料（員工、有效與作廢出勤、申請、歷程）的資料庫套用 V4 → 原資料一字不差，新表可用、預設關閉")
    void v4UpgradesExistingV3Database() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            TestDatabaseGuard.assertSafeToClean(TestDatabaseGuard.actualDatabase(connection),
                    env.getRequiredProperty("renewflow.test.database"), env.getRequiredProperty("renewflow.test.dev-database"));
        }
        String schema = "migration_probe_v4";
        Flyway v3Only = Flyway.configure().dataSource(dataSource).schemas(schema).createSchemas(true)
                .cleanDisabled(false).locations("classpath:db/migration").target("3").load();
        Flyway latest = Flyway.configure().dataSource(dataSource).schemas(schema)
                .cleanDisabled(false).locations("classpath:db/migration").target("4").load();
        try {
            v3Only.clean();
            v3Only.migrate();
            String s = schema + ".";
            jdbc.update("insert into " + s + "employee (employee_no, username, display_name, password_hash, role) values"
                    + " ('U0001', 'legacy', 'Legacy user', 'hash-placeholder', 'EMPLOYEE'),"
                    + " ('U0002', 'boss', 'Legacy admin', 'hash-placeholder', 'ADMIN')");
            // 作廢的舊紀錄＋同日的有效紀錄（與使用者開發庫的形態相同）
            jdbc.update("insert into " + s + "attendance (employee_id, work_date, clock_in_at, clock_out_at, version, voided_at, voided_by)"
                    + " select e.id, date '2026-10-01', timestamptz '2026-10-01 04:26:23.631575+00', timestamptz '2026-10-01 04:26:25+00', 1,"
                    + " timestamptz '2026-10-01 05:00:00+00', b.id from " + s + "employee e, " + s + "employee b where e.username = 'legacy' and b.username = 'boss'");
            jdbc.update("insert into " + s + "attendance (employee_id, work_date, clock_in_at) select id, date '2026-10-01', timestamptz '2026-10-01 06:00:00+00' from "
                    + s + "employee where username = 'legacy'");
            jdbc.update("insert into " + s + "attendance_request (employee_id, request_type, status, work_date, reason, reviewer_id, reviewed_at)"
                    + " select e.id, 'RESET_TODAY', 'APPROVED', date '2026-10-01', '誤按', b.id, now() from " + s + "employee e, " + s + "employee b"
                    + " where e.username = 'legacy' and b.username = 'boss'");
            jdbc.update("insert into " + s + "attendance_history (employee_id, work_date, operation, actor_id, occurred_at)"
                    + " select id, date '2026-10-01', 'REQUEST_SUBMITTED', id, now() from " + s + "employee where username = 'legacy'");
            String attendanceBefore = jdbc.queryForList("select row_to_json(a)::text from " + s + "attendance a order by id", String.class).toString();
            String requestBefore = jdbc.queryForList("select row_to_json(r)::text from " + s + "attendance_request r order by id", String.class).toString();
            String historyBefore = jdbc.queryForList("select row_to_json(h)::text from " + s + "attendance_history h order by id", String.class).toString();
            String employeeBefore = jdbc.queryForList("select row_to_json(e)::text from " + s + "employee e order by id", String.class).toString();

            latest.migrate(); // V3 → V4

            assertThat(jdbc.queryForList("select row_to_json(a)::text from " + s + "attendance a order by id", String.class).toString()).isEqualTo(attendanceBefore);
            assertThat(jdbc.queryForList("select row_to_json(r)::text from " + s + "attendance_request r order by id", String.class).toString()).isEqualTo(requestBefore);
            assertThat(jdbc.queryForList("select row_to_json(h)::text from " + s + "attendance_history h order by id", String.class).toString()).isEqualTo(historyBefore);
            assertThat(jdbc.queryForList("select row_to_json(e)::text from " + s + "employee e order by id", String.class).toString()).isEqualTo(employeeBefore);
            assertThat(jdbc.queryForList("select version from " + s + "flyway_schema_history where success and version is not null order by installed_rank",
                    String.class)).containsExactly("1", "2", "3", "4");
            Map<String, Object> setting = jdbc.queryForMap("select enabled, center_latitude, radius_meters, scope, version from " + s + "geofence_setting");
            assertThat(setting.get("enabled")).isEqualTo(false);
            assertThat(setting.get("center_latitude")).isNull();
            assertThat(setting.get("radius_meters")).isEqualTo(100);
            assertThat(setting.get("scope")).isEqualTo("BOTH");
            assertThat(setting.get("version")).isEqualTo(0L);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "attendance_clock_event", Integer.class)).as("升級不替舊紀錄製造證據").isZero();
            // 新表可用：可以替既有的有效紀錄寫一筆打卡事件
            jdbc.update("insert into " + s + "attendance_clock_event (employee_id, attendance_id, action, accepted_at, location_required,"
                    + " setting_version, setting_enabled, setting_scope, setting_radius_meters)"
                    + " select employee_id, id, 'CLOCK_OUT', now(), false, 0, false, 'BOTH', 100 from " + s + "attendance where voided_at is null");
            assertThat(jdbc.queryForObject("select count(*) from " + s + "attendance_clock_event", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    @DisplayName("M-03 只新增資料表：V4 沒有在既有的 attendance／attendance_request／attendance_history 加欄位")
    void v4DoesNotAlterExistingTables() {
        List<String> attendanceColumns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_schema = current_schema() and table_name = 'attendance' order by ordinal_position",
                String.class);
        assertThat(attendanceColumns).containsExactly("id", "employee_id", "work_date", "clock_in_at", "clock_out_at", "created_at", "updated_at",
                "version", "voided_at", "voided_by", "source", "corrected_at");
    }
}
