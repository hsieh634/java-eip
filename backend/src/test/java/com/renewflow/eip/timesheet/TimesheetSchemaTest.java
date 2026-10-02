package com.renewflow.eip.timesheet;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V5 migration：資料庫層的限制，以及從已有資料的 V4 升級時原資料完全保留。 */
class TimesheetSchemaTest extends TimesheetTestBase {

    @Autowired
    DataSource dataSource;
    @Autowired
    Environment env;

    private void insertEntry(long employeeId, long projectId, int minutes, String requestId) {
        jdbc.update("insert into timesheet_entry (employee_id, project_id, work_date, description, minutes, request_id, created_at, updated_at)"
                + " values (?, ?, date '2026-10-01', '直接寫入', ?, ?::uuid, now(), now())", employeeId, projectId, minutes, requestId);
    }

    @Test
    @DisplayName("S-01 資料庫限制：分鐘數範圍與 15 倍數、內容不可空白、代碼格式與唯一、員工＋requestId 唯一、有工時的專案與員工不能刪除；不建立預設專案")
    void constraints() {
        assertThat(jdbc.queryForObject("select count(*) from project", Integer.class)).as("migration 不建立預設專案").isZero();
        long p = createProject("TP-WEB", "網站");
        String rid = "00000000-0000-0000-0000-000000000001";
        insertEntry(alice.getId(), p, 15, rid);
        for (int bad : List.of(0, 10, 1455, -15)) {
            assertThatThrownBy(() -> insertEntry(alice.getId(), p, bad, java.util.UUID.randomUUID().toString()))
                    .as("minutes " + bad).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> insertEntry(alice.getId(), p, 30, rid)).as("同員工同 requestId").isInstanceOf(DataIntegrityViolationException.class);
        insertEntry(bob.getId(), p, 30, rid); // 不同員工可以
        assertThatThrownBy(() -> jdbc.update("insert into timesheet_entry (employee_id, project_id, work_date, description, minutes, request_id, created_at, updated_at)"
                + " values (?, ?, date '2026-10-01', '   ', 30, gen_random_uuid(), now(), now())", alice.getId(), p)).isInstanceOf(DataIntegrityViolationException.class);
        for (String code : List.of("tp-low", "A", "HAS SPACE", "X".repeat(33))) {
            assertThatThrownBy(() -> jdbc.update("insert into project (code, name, created_at, updated_at) values (?, 'x', now(), now())", code))
                    .as("code " + code).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.update("insert into project (code, name, created_at, updated_at) values ('TP-WEB', 'dup', now(), now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from project where id = ?", p)).as("有工時的專案不能刪").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from employee where id = ?", alice.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("S-02 升級：在已有 V4 資料（員工、有效／作廢出勤、申請、歷程、已修改的定位設定與歷程、打卡事件）的資料庫套用 V5 → 原資料逐列相同、定位設定不變、沒有預設專案")
    void v5UpgradesExistingV4Database() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            TestDatabaseGuard.assertSafeToClean(TestDatabaseGuard.actualDatabase(connection),
                    env.getRequiredProperty("renewflow.test.database"), env.getRequiredProperty("renewflow.test.dev-database"));
        }
        String schema = "migration_probe_v5";
        Flyway v4Only = Flyway.configure().dataSource(dataSource).schemas(schema).createSchemas(true)
                .cleanDisabled(false).locations("classpath:db/migration").target("4").load();
        Flyway v5 = Flyway.configure().dataSource(dataSource).schemas(schema)
                .cleanDisabled(false).locations("classpath:db/migration").target("5").load();
        String s = schema + ".";
        List<String> tables = List.of("employee", "attendance", "attendance_request", "attendance_history",
                "geofence_setting", "geofence_setting_history", "attendance_clock_event");
        try {
            v4Only.clean();
            v4Only.migrate();
            jdbc.update("insert into " + s + "employee (employee_no, username, display_name, password_hash, role) values"
                    + " ('U0001', 'legacy', 'Legacy user', 'hash-placeholder', 'EMPLOYEE'), ('U0002', 'boss', 'Legacy admin', 'hash-placeholder', 'ADMIN')");
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
            jdbc.update("update " + s + "geofence_setting set enabled = true, center_latitude = 25.0, center_longitude = 121.5, radius_meters = 150, version = 1,"
                    + " updated_by = (select id from " + s + "employee where username = 'boss'), updated_at = now()");
            jdbc.update("insert into " + s + "geofence_setting_history (setting_version, before_enabled, before_radius_meters, before_scope, after_enabled,"
                    + " after_center_latitude, after_center_longitude, after_radius_meters, after_scope, reason, actor_id, occurred_at)"
                    + " select 1, false, 100, 'BOTH', true, 25.0, 121.5, 150, 'BOTH', '啟用', id, now() from " + s + "employee where username = 'boss'");
            jdbc.update("insert into " + s + "attendance_clock_event (employee_id, attendance_id, action, accepted_at, location_required, setting_version,"
                    + " setting_enabled, setting_scope, setting_center_latitude, setting_center_longitude, setting_radius_meters, latitude, longitude,"
                    + " accuracy_meters, captured_at, distance_meters)"
                    + " select employee_id, id, 'CLOCK_IN', now(), true, 1, true, 'BOTH', 25.0, 121.5, 150, 25.0001, 121.5, 8, now(), 11.1 from "
                    + s + "attendance where voided_at is null");
            List<String> before = tables.stream().map(t -> fingerprint(s + t)).toList();

            v5.migrate(); // V4 → V5

            assertThat(tables.stream().map(t -> fingerprint(s + t)).toList()).as("既有資料逐列相同").isEqualTo(before);
            assertThat(jdbc.queryForList("select version from " + s + "flyway_schema_history where success and version is not null order by installed_rank",
                    String.class)).containsExactly("1", "2", "3", "4", "5");
            assertThat(jdbc.queryForObject("select enabled from " + s + "geofence_setting", Boolean.class)).as("定位設定不變").isTrue();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "project", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "timesheet_entry", Integer.class)).isZero();
            // 新表可用，外鍵指向既有員工
            jdbc.update("insert into " + s + "project (code, name, created_at, updated_at) values ('UP', '升級後', now(), now())");
            jdbc.update("insert into " + s + "timesheet_entry (employee_id, project_id, work_date, description, minutes, request_id, created_at, updated_at)"
                    + " select e.id, p.id, date '2026-10-01', '升級後可用', 60, gen_random_uuid(), now(), now() from " + s + "employee e, " + s + "project p where e.username = 'legacy'");
            assertThat(jdbc.queryForObject("select count(*) from " + s + "timesheet_entry", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private String fingerprint(String table) {
        return jdbc.queryForObject("select coalesce(md5(string_agg(row_to_json(t)::text, '|' order by t.id)), 'empty') from " + table + " t", String.class);
    }
}
