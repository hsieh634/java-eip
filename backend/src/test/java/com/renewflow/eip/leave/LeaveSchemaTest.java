package com.renewflow.eip.leave;

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

import static org.assertj.core.api.Assertions.assertThat;

/** V7 migration：乾淨資料庫的結構，以及從已有資料的 V6 升級時原資料完全保留。 */
class LeaveSchemaTest extends LeaveTestBase {

    @Autowired
    DataSource dataSource;
    @Autowired
    Environment env;

    @Test
    @DisplayName("LS-01 乾淨資料庫：V1～V7；三張新表存在且為空（不建立預設申請或設定）；限制存在")
    void cleanDatabaseHasV7() {
        assertThat(jdbc.queryForList("select version from flyway_schema_history where success order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
        for (String t : List.of("leave_request", "leave_approver_setting", "leave_history")) {
            assertThat(jdbc.queryForObject("select count(*) from " + t, Integer.class)).as(t).isZero();
        }
        assertThat(jdbc.queryForList("select conname from pg_constraint where conrelid = 'leave_request'::regclass", String.class))
                .contains("uq_leave_request_employee_request", "ck_leave_request_not_self_approver", "ck_leave_request_grid", "ck_leave_request_decision");
    }

    @Test
    @DisplayName("LS-02 升級：在已有 V6 資料（部門、主管、組織歷程、出勤、申請、定位、打卡事件、專案、工時）的資料庫套用 V7 → 既有表逐列相同、新表為空")
    void v7UpgradesExistingV6Database() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            TestDatabaseGuard.assertSafeToClean(TestDatabaseGuard.actualDatabase(connection),
                    env.getRequiredProperty("renewflow.test.database"), env.getRequiredProperty("renewflow.test.dev-database"));
        }
        String schema = "migration_probe_v7";
        Flyway v6Only = Flyway.configure().dataSource(dataSource).schemas(schema).createSchemas(true)
                .cleanDisabled(false).locations("classpath:db/migration").target("6").load();
        Flyway v7 = Flyway.configure().dataSource(dataSource).schemas(schema)
                .cleanDisabled(false).locations("classpath:db/migration").target("7").load();
        String s = schema + ".";
        List<String> tables = List.of("employee", "department", "organization_history", "attendance", "attendance_request",
                "attendance_history", "geofence_setting", "geofence_setting_history", "attendance_clock_event", "project", "timesheet_entry");
        try {
            v6Only.clean();
            v6Only.migrate();
            jdbc.update("insert into " + s + "department (code, name, created_at, updated_at) values ('PROJECT', '專案服務', now(), now())");
            jdbc.update("insert into " + s + "employee (employee_no, username, display_name, password_hash, role, enabled, created_at, updated_at) values"
                    + " ('U0001', 'boss', 'Boss', 'hash-1', 'ADMIN', true, now(), now()),"
                    + " ('U0002', 'lead', 'Lead', 'hash-2', 'MANAGER', true, now(), now()),"
                    + " ('U0003', 'staff', 'Staff', 'hash-3', 'EMPLOYEE', true, now(), now())");
            jdbc.update("update " + s + "employee set department_id = (select id from " + s + "department), version = 1,"
                    + " manager_id = (select id from " + s + "employee where username = 'lead') where username = 'staff'");
            jdbc.update("insert into " + s + "organization_history (target_type, employee_id, action, changes, actor_id, source, occurred_at)"
                    + " select 'EMPLOYEE', e.id, 'EMPLOYEE_UPDATED', '[]', b.id, 'ADMIN', now() from " + s + "employee e, " + s + "employee b"
                    + " where e.username = 'staff' and b.username = 'boss'");
            jdbc.update("insert into " + s + "attendance (employee_id, work_date, clock_in_at, clock_out_at) select id, date '2026-10-01',"
                    + " timestamptz '2026-10-01 01:00:00+00', timestamptz '2026-10-01 10:00:00+00' from " + s + "employee where username = 'staff'");
            jdbc.update("insert into " + s + "attendance_request (employee_id, request_type, status, work_date, reason, reviewer_id, reviewed_at)"
                    + " select e.id, 'RESET_TODAY', 'APPROVED', date '2026-10-01', '誤按', b.id, now() from " + s + "employee e, " + s + "employee b"
                    + " where e.username = 'staff' and b.username = 'boss'");
            jdbc.update("insert into " + s + "attendance_history (employee_id, work_date, operation, actor_id, occurred_at)"
                    + " select id, date '2026-10-01', 'REQUEST_SUBMITTED', id, now() from " + s + "employee where username = 'staff'");
            jdbc.update("insert into " + s + "attendance_clock_event (employee_id, attendance_id, action, accepted_at, location_required, setting_version,"
                    + " setting_enabled, setting_scope, setting_radius_meters) select employee_id, id, 'CLOCK_IN', now(), false, 0, false, 'BOTH', 100 from " + s + "attendance");
            jdbc.update("insert into " + s + "project (code, name, created_at, updated_at) values ('LEGACY', '舊專案', now(), now())");
            jdbc.update("insert into " + s + "timesheet_entry (employee_id, project_id, work_date, description, minutes, request_id, created_at, updated_at)"
                    + " select e.id, p.id, date '2026-10-01', '升級前', 60, gen_random_uuid(), now(), now() from " + s + "employee e, " + s + "project p where e.username = 'staff'");
            List<String> before = tables.stream().map(t -> fingerprint(s + t)).toList();

            v7.migrate(); // V6 → V7

            assertThat(tables.stream().map(t -> fingerprint(s + t)).toList()).as("既有表逐列相同").isEqualTo(before);
            assertThat(jdbc.queryForList("select version from " + s + "flyway_schema_history where success and version is not null order by installed_rank",
                    String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7");
            for (String t : List.of("leave_request", "leave_approver_setting", "leave_history")) {
                assertThat(jdbc.queryForObject("select count(*) from " + s + t, Integer.class)).as(t).isZero();
            }
            // 新表可用：外鍵指向既有員工
            jdbc.update("insert into " + s + "leave_request (employee_id, request_id, leave_type, work_date, start_time, end_time, minutes, reason, status,"
                    + " approver_id, approver_source, created_at, updated_at) select e.id, gen_random_uuid(), 'SICK', date '2026-10-02', '09:00', '10:30', 90,"
                    + " '升級後', 'PENDING', m.id, 'MANAGER', now(), now() from " + s + "employee e, " + s + "employee m where e.username = 'staff' and m.username = 'lead'");
            assertThat(jdbc.queryForObject("select count(*) from " + s + "leave_request", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private String fingerprint(String table) {
        return jdbc.queryForObject("select coalesce(md5(string_agg(row_to_json(t)::text, '|' order by t.id)), 'empty') from " + table + " t", String.class);
    }
}
