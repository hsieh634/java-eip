package com.renewflow.eip.organization;

import com.renewflow.eip.support.TestDatabaseGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V6 migration：乾淨資料庫的結構，以及從已有資料的 V5 升級時原資料完全保留。 */
class OrganizationSchemaTest extends OrganizationTestBase {

    @Autowired
    DataSource dataSource;
    @Autowired
    Environment env;

    /** employee 在 V5 時就有的欄位（舊欄位投影）：升級前後逐列比對這些欄位。 */
    private static final String EMPLOYEE_V5_COLUMNS = "id, employee_no, username, display_name, password_hash, role, enabled, created_at, updated_at";

    @Test
    @DisplayName("OS-01 乾淨資料庫：V6 建立 department、organization_history 與 employee 的組織欄位；不建立任何部門；既有員工未指派、版本 0")
    void cleanDatabaseHasV6() {
        assertThat(jdbc.queryForList("select version from flyway_schema_history where success order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        assertThat(jdbc.queryForObject("select count(*) from department", Integer.class)).as("migration 不建立部門").isZero();
        assertThat(jdbc.queryForObject("select count(*) from employee where department_id is not null or manager_id is not null or version <> 0",
                Integer.class)).isZero();
        assertThat(jdbc.queryForList("select conname from pg_constraint where conrelid = 'employee'::regclass", String.class))
                .contains("ck_employee_not_own_manager");
        assertThatThrownBy(() -> jdbc.update("insert into department (code, name, created_at, updated_at) values ('OK', ' 有空白 ', now(), now())"))
                .as("名稱需已去除空白").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("insert into organization_history (target_type, employee_id, action, changes, source, occurred_at)"
                + " values ('EMPLOYEE', ?, 'EMPLOYEE_UPDATED', '[]', 'ADMIN', now())", alice.getId()))
                .as("ADMIN 來源必須有操作者").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("insert into organization_history (target_type, employee_id, action, changes, source, actor_id, occurred_at)"
                + " values ('EMPLOYEE', ?, 'EMPLOYEE_UPDATED', '[]', 'SYSTEM_DEMO_SEED', ?, now())", alice.getId(), carol.getId()))
                .as("系統來源不能掛操作者").isInstanceOf(DataAccessException.class);
        long deptId = createDept("KEEP", "保留");
        assertThat(form(alice.getId()).department(deptId).submit(loginAs("carol")).status()).isEqualTo(200);
        assertThatThrownBy(() -> jdbc.update("delete from department where id = ?", deptId)).as("有員工的部門不能被刪").isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("OS-02 升級：在已有 V5 資料（員工、出勤、申請、歷程、定位、打卡事件、專案、工時）的資料庫套用 V6 → 舊欄位逐列相同、其他表逐列相同、新欄位為未指派")
    void v6UpgradesExistingV5Database() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            TestDatabaseGuard.assertSafeToClean(TestDatabaseGuard.actualDatabase(connection),
                    env.getRequiredProperty("renewflow.test.database"), env.getRequiredProperty("renewflow.test.dev-database"));
        }
        String schema = "migration_probe_v6";
        Flyway v5Only = Flyway.configure().dataSource(dataSource).schemas(schema).createSchemas(true)
                .cleanDisabled(false).locations("classpath:db/migration").target("5").load();
        Flyway v6 = Flyway.configure().dataSource(dataSource).schemas(schema)
                .cleanDisabled(false).locations("classpath:db/migration").target("6").load();
        String s = schema + ".";
        List<String> otherTables = List.of("attendance", "attendance_request", "attendance_history", "geofence_setting",
                "geofence_setting_history", "attendance_clock_event", "project", "timesheet_entry");
        try {
            v5Only.clean();
            v5Only.migrate();
            jdbc.update("insert into " + s + "employee (employee_no, username, display_name, password_hash, role, enabled, created_at, updated_at) values"
                    + " ('U0001', 'legacy', 'Legacy user', 'hash-placeholder-1', 'EMPLOYEE', true, timestamptz '2026-09-01 01:02:03.456789+00', timestamptz '2026-09-02 01:02:03+00'),"
                    + " ('U0002', 'boss', 'Legacy admin', 'hash-placeholder-2', 'ADMIN', true, now(), now()),"
                    + " ('U0003', 'gone', 'Disabled one', 'hash-placeholder-3', 'MANAGER', false, now(), now())");
            jdbc.update("insert into " + s + "attendance (employee_id, work_date, clock_in_at, clock_out_at) select id, date '2026-10-01',"
                    + " timestamptz '2026-10-01 01:00:00+00', timestamptz '2026-10-01 10:00:00+00' from " + s + "employee where username = 'legacy'");
            jdbc.update("insert into " + s + "attendance_request (employee_id, request_type, status, work_date, reason, reviewer_id, reviewed_at)"
                    + " select e.id, 'RESET_TODAY', 'APPROVED', date '2026-10-01', '誤按', b.id, now() from " + s + "employee e, " + s + "employee b"
                    + " where e.username = 'legacy' and b.username = 'boss'");
            jdbc.update("insert into " + s + "attendance_history (employee_id, work_date, operation, actor_id, occurred_at)"
                    + " select id, date '2026-10-01', 'REQUEST_SUBMITTED', id, now() from " + s + "employee where username = 'legacy'");
            jdbc.update("update " + s + "geofence_setting set enabled = true, center_latitude = 25.0, center_longitude = 121.5, radius_meters = 150, version = 1,"
                    + " updated_by = (select id from " + s + "employee where username = 'boss'), updated_at = now()");
            jdbc.update("insert into " + s + "attendance_clock_event (employee_id, attendance_id, action, accepted_at, location_required, setting_version,"
                    + " setting_enabled, setting_scope, setting_center_latitude, setting_center_longitude, setting_radius_meters, latitude, longitude,"
                    + " accuracy_meters, captured_at, distance_meters)"
                    + " select employee_id, id, 'CLOCK_IN', now(), true, 1, true, 'BOTH', 25.0, 121.5, 150, 25.0001, 121.5, 8, now(), 11.1 from "
                    + s + "attendance");
            jdbc.update("insert into " + s + "project (code, name, created_at, updated_at) values ('LEGACY', '舊專案', now(), now())");
            jdbc.update("insert into " + s + "timesheet_entry (employee_id, project_id, work_date, description, minutes, request_id, created_at, updated_at)"
                    + " select e.id, p.id, date '2026-10-01', '升級前', 60, gen_random_uuid(), now(), now() from " + s + "employee e, " + s + "project p where e.username = 'legacy'");
            String employeesBefore = projection(s);
            List<String> before = otherTables.stream().map(t -> fingerprint(s + t)).toList();

            v6.migrate(); // V5 → V6

            assertThat(projection(s)).as("employee 舊欄位逐列相同（ID、帳號、角色、啟用、密碼雜湊、時間）").isEqualTo(employeesBefore);
            assertThat(otherTables.stream().map(t -> fingerprint(s + t)).toList()).as("其他表逐列相同").isEqualTo(before);
            assertThat(jdbc.queryForList("select version from " + s + "flyway_schema_history where success and version is not null order by installed_rank",
                    String.class)).containsExactly("1", "2", "3", "4", "5", "6");
            assertThat(jdbc.queryForObject("select count(*) from " + s + "employee where department_id is null and manager_id is null and version = 0",
                    Integer.class)).as("全部未指派、版本 0").isEqualTo(3);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "department", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "organization_history", Integer.class)).isZero();
            // 新欄位可用：外鍵指向既有員工
            jdbc.update("insert into " + s + "department (code, name, created_at, updated_at) values ('UP', '升級後', now(), now())");
            jdbc.update("update " + s + "employee set department_id = (select id from " + s + "department), manager_id = (select id from " + s
                    + "employee where username = 'boss') where username = 'legacy'");
            assertThat(jdbc.queryForObject("select count(*) from " + s + "employee where manager_id is not null", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private String projection(String s) {
        return jdbc.queryForObject("select coalesce(md5(string_agg(row_to_json(t)::text, '|' order by t.id)), 'empty') from (select "
                + EMPLOYEE_V5_COLUMNS + " from " + s + "employee) t", String.class);
    }

    private String fingerprint(String table) {
        return jdbc.queryForObject("select coalesce(md5(string_agg(row_to_json(t)::text, '|' order by t.id)), 'empty') from " + table + " t", String.class);
    }
}
