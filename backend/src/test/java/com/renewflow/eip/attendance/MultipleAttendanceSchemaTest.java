package com.renewflow.eip.attendance;
import com.renewflow.eip.support.TestDatabaseGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
class MultipleAttendanceSchemaTest extends CorrectionsTestBase {
    @Autowired DataSource dataSource;
    @Autowired Environment env;
    @Test
    @DisplayName("S01 升級：在已有 V7 資料（組織、出勤、申請、定位、事件、專案、工時、請假）的資料庫套用 V8 → 既有表逐列相同、去重表為空")
    void v8UpgradesExistingV7Database() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            TestDatabaseGuard.assertSafeToClean(TestDatabaseGuard.actualDatabase(connection),
                    env.getRequiredProperty("renewflow.test.database"), env.getRequiredProperty("renewflow.test.dev-database"));
        }
        String schema = "migration_probe_v8";
        Flyway v7Only = Flyway.configure().dataSource(dataSource).schemas(schema).createSchemas(true)
                .cleanDisabled(false).locations("classpath:db/migration").target("7").load();
        Flyway v8 = Flyway.configure().dataSource(dataSource).schemas(schema)
                .cleanDisabled(false).locations("classpath:db/migration").target("8").load();
        String s = schema + ".";
        List<String> tables = List.of("employee", "department", "organization_history", "attendance", "attendance_request",
                "attendance_history", "geofence_setting", "geofence_setting_history", "attendance_clock_event", "project", "timesheet_entry", "leave_request", "leave_history", "leave_approver_setting");
        try {
            v7Only.clean();
            v7Only.migrate();
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
            jdbc.update("update " + s + "attendance set version=4, source='MANUAL', corrected_at=now()");
            jdbc.update("update " + s + "attendance_request set target_attendance_id=(select id from " + s + "attendance),target_version=4");
            jdbc.update("insert into " + s + "attendance(employee_id,work_date,clock_in_at,clock_out_at,voided_at,voided_by,version) select e.id,date '2026-10-01',timestamptz '2026-10-01 00:00:00.123456+00',timestamptz '2026-10-01 00:30:00.123456+00',now(),b.id,7 from " + s + "employee e," + s + "employee b where e.username='staff' and b.username='boss'");
            jdbc.update("insert into " + s + "leave_approver_setting(employee_id,approver_id,version,updated_by,updated_at) select e.id,m.id,2,b.id,now() from " + s + "employee e," + s + "employee m," + s + "employee b where e.username='staff' and m.username='lead' and b.username='boss'");
            jdbc.update("insert into " + s + "leave_request(employee_id,request_id,leave_type,work_date,start_time,end_time,minutes,reason,status,approver_id,approver_source,created_at,updated_at) select e.id,gen_random_uuid(),'SICK',date '2026-10-02','09:00','10:30',90,'舊申請','PENDING',m.id,'MANAGER',now(),now() from " + s + "employee e," + s + "employee m where e.username='staff' and m.username='lead'");
            jdbc.update("insert into " + s + "leave_history(leave_request_id,action,to_status,actor_id,occurred_at) select id,'SUBMITTED','PENDING',employee_id,now() from " + s + "leave_request");
            jdbc.update("insert into " + s + "geofence_setting_history(setting_version,before_enabled,before_radius_meters,before_scope,after_enabled,after_radius_meters,after_scope,reason,actor_id,occurred_at) select 1,false,100,'BOTH',false,120,'BOTH','舊設定',id,now() from " + s + "employee where username='boss'");
            jdbc.update("update " + s + "geofence_setting set version=1,radius_meters=120");
            var before = tables.stream().map(t -> fingerprint(s+t)).toList();
            var counts = tables.stream().map(t -> jdbc.queryForObject("select count(*) from " + s + t, Integer.class)).toList();
            assertThat(counts).as("所有既有模組表均有代表資料").allMatch(n -> n > 0);
            v8.migrate();
            var after = tables.stream().map(t -> fingerprint(s+t)).toList();
            assertThat(after).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "attendance_clock_submission",Integer.class)).isZero();
            assertThat(jdbc.queryForList("select version from " + s + "flyway_schema_history where success and version is not null order by installed_rank",String.class)).containsExactly("1","2","3","4","5","6","7","8");
            StringBuilder evidence = new StringBuilder("V7→V8：隔離庫／獨立 schema，逐表列資料雜湊（不含明文資料）\n");
            for (int i=0;i<tables.size();i++) evidence.append(tables.get(i)).append(" | rows=").append(counts.get(i)).append(" | before=").append(before.get(i)).append(" | after=").append(after.get(i)).append("\n");
            try { java.nio.file.Files.writeString(java.nio.file.Path.of("target/t03-3-migration-evidence.txt"),evidence); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
        } finally { jdbc.execute("drop schema if exists " + schema + " cascade"); }
    }
    private String fingerprint(String table) {
        return jdbc.queryForObject("select coalesce(md5(string_agg(row_to_json(t)::text, '|' order by row_to_json(t)::text)), 'empty') from " + table + " t", String.class);
    }
}
