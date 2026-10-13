package com.renewflow.eip.leave;
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
class LeaveRevisionSchemaTest extends LeaveTestBase {
    @Autowired DataSource dataSource;
    @Autowired Environment env;
    @Test
    @DisplayName("L51-25 V8→V9：15表舊欄位逐列保留，舊核准正確有效、來源／版本外鍵與不可變內容")
    void v9PreservesLegacyColumnsAndInitializesEffectiveDocuments() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            TestDatabaseGuard.assertSafeToClean(TestDatabaseGuard.actualDatabase(connection),
                    env.getRequiredProperty("renewflow.test.database"), env.getRequiredProperty("renewflow.test.dev-database"));
        }
        String schema = "migration_probe_leave_v9";
        Flyway v8Only = Flyway.configure().dataSource(dataSource).schemas(schema).createSchemas(true)
                .cleanDisabled(false).locations("classpath:db/migration").target("8").load();
        Flyway v9 = Flyway.configure().dataSource(dataSource).schemas(schema)
                .cleanDisabled(false).locations("classpath:db/migration").target("9").load();
        String s = schema + ".";
        List<String> tables = List.of("employee", "department", "organization_history", "attendance", "attendance_request",
                "attendance_history", "geofence_setting", "geofence_setting_history", "attendance_clock_event", "project", "timesheet_entry", "leave_request", "leave_history", "leave_approver_setting", "attendance_clock_submission");
        try {
            v8Only.clean();
            v8Only.migrate();
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
            jdbc.update("insert into " + s + "attendance_clock_submission(employee_id,request_id,action,fingerprint,attendance_id,response_json) select employee_id,gen_random_uuid(),'CLOCK_IN',repeat('a',64),id,'{}' from " + s + "attendance where voided_at is null");
            jdbc.update("insert into " + s + "leave_request(employee_id,request_id,leave_type,work_date,start_time,end_time,minutes,reason,status,approver_id,approver_source,decided_by,decided_at,decision_reason,version,created_at,updated_at) select e.id,gen_random_uuid(),'PERSONAL',date '2026-10-03','09:00','17:00',480,'舊核准','APPROVED',b.id,'REASSIGNED',b.id,now(),null,4,now(),now() from " + s + "employee e," + s + "employee b where e.username='staff' and b.username='boss'");
            jdbc.update("insert into " + s + "leave_request(employee_id,request_id,leave_type,work_date,start_time,end_time,minutes,reason,status,approver_id,approver_source,decided_by,decided_at,decision_reason,created_at,updated_at) select e.id,gen_random_uuid(),'PERSONAL',date '2026-10-04','09:00','17:00',480,'舊退回','REJECTED',m.id,'MANAGER',m.id,now(),'保留退回原因',now(),now() from " + s + "employee e," + s + "employee m where e.username='staff' and m.username='lead'");
            jdbc.update("insert into " + s + "leave_request(employee_id,request_id,leave_type,work_date,start_time,end_time,minutes,reason,status,approver_id,approver_source,decided_by,decided_at,created_at,updated_at) select e.id,gen_random_uuid(),'PERSONAL',date '2026-10-05','09:00','17:00',480,'舊撤回','WITHDRAWN',m.id,'MANAGER',e.id,now(),now(),now() from " + s + "employee e," + s + "employee m where e.username='staff' and m.username='lead'");
            jdbc.update("insert into " + s + "leave_history(leave_request_id,action,to_status,after_approver_id,actor_id,occurred_at) select r.id,'SUBMITTED','PENDING',m.id,r.employee_id,now() from " + s + "leave_request r," + s + "employee m where r.status<>'PENDING' and m.username='lead'");
            jdbc.update("insert into " + s + "leave_history(leave_request_id,action,from_status,to_status,before_approver_id,after_approver_id,actor_id,reason,occurred_at) select r.id,'REASSIGNED','PENDING','PENDING',m.id,r.approver_id,r.approver_id,'歷史改派',now() from " + s + "leave_request r," + s + "employee m where r.status='APPROVED' and m.username='lead'");
            jdbc.update("insert into " + s + "leave_history(leave_request_id,action,from_status,to_status,actor_id,reason,occurred_at) select id,status,'PENDING',status,decided_by,decision_reason,now() from " + s + "leave_request where status in ('APPROVED','REJECTED','WITHDRAWN')");
            var before = tables.stream().map(t -> fingerprint(s+t)).toList();
            var counts = tables.stream().map(t -> jdbc.queryForObject("select count(*) from " + s + t, Integer.class)).toList();
            assertThat(counts).as("所有既有模組表均有代表資料").allMatch(n -> n > 0);
            v9.migrate();
            var after = tables.stream().map(t -> fingerprint(s+t)).toList();
            assertThat(after).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "leave_document",Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "leave_document where effective_request_id is not null and effective_version=1",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "leave_request r join " + s + "employee e on e.id=r.submitted_approver_id where r.status='APPROVED' and r.submitted_approver_source='LEGACY_UNKNOWN' and e.username='lead'",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "leave_history where decision_snapshot is not null",Integer.class)).isZero();
            assertThat(jdbc.queryForList("select version from " + s + "flyway_schema_history where success and version is not null order by installed_rank",String.class)).containsExactly("1","2","3","4","5","6","7","8","9");
            // 固定本次連線 search_path，驗證 probe 的 trigger／舊核准可延續；最後回滾，只比較原資料。
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                boolean auto=connection.getAutoCommit();connection.setAutoCommit(false);
                try(var statement=connection.createStatement()) {
                    statement.execute("set local search_path to "+schema+",public");
                    long original;
                    try(var rs=statement.executeQuery("select id from leave_request where status='APPROVED'")) {rs.next();original=rs.getLong(1);}
                    long next;
                    try(var rs=statement.executeQuery("insert into leave_request(employee_id,request_id,leave_type,work_date,start_time,end_time,minutes,reason,status,approver_id,approver_source,created_at,updated_at,document_id,revision_no,operation,source_request_id,source_version,base_effective_request_id,base_effective_version,fingerprint) select employee_id,gen_random_uuid(),leave_type,work_date,'09:00','14:00',300,'舊核准後變更','PENDING',approver_id,'DESIGNATED',now(),now(),document_id,2,'CHANGE',id,version,id,1,repeat('b',64) from leave_request where id="+original+" returning id")) {rs.next();next=rs.getLong(1);}
                    statement.executeUpdate("update leave_request set status='APPROVED',decided_by=approver_id,decided_at=now(),version=version+1 where id="+next);
                    assertThat(statement.executeUpdate("update leave_document set effective_request_id="+next+",effective_version=effective_version+1 where id="+original+" and effective_version=1")).isEqualTo(1);
                    try(var rs=statement.executeQuery("select effective_request_id,effective_version from leave_document where id="+original)) {rs.next();assertThat(rs.getLong(1)).isEqualTo(next);assertThat(rs.getLong(2)).isEqualTo(2);}
                    var point=connection.setSavepoint();
                    org.assertj.core.api.Assertions.assertThatThrownBy(()->statement.executeUpdate("update leave_request set reason='不能覆蓋原內容' where id="+original)).isInstanceOf(java.sql.SQLException.class);
                    connection.rollback(point);
                    point=connection.setSavepoint();
                    statement.executeUpdate("insert into leave_request(employee_id,request_id,leave_type,work_date,start_time,end_time,minutes,reason,status,approver_id,approver_source,created_at,updated_at,document_id,revision_no,operation,source_request_id,source_version,base_effective_request_id,base_effective_version,fingerprint) select employee_id,gen_random_uuid(),leave_type,work_date,'09:00','14:00',300,'第二個待審','PENDING',approver_id,'MANAGER',now(),now(),document_id,3,'CHANGE',id,version,id,2,repeat('c',64) from leave_request where id="+next);
                    // 同業務單再插入待審必須被唯一索引拒絕。
                    org.assertj.core.api.Assertions.assertThatThrownBy(()->statement.executeUpdate("insert into leave_request(employee_id,request_id,leave_type,work_date,start_time,end_time,minutes,reason,status,approver_id,approver_source,created_at,updated_at,document_id,revision_no,operation,source_request_id,source_version,base_effective_request_id,base_effective_version,fingerprint) select employee_id,gen_random_uuid(),leave_type,work_date,'09:00','14:00',300,'重複待審','PENDING',approver_id,'MANAGER',now(),now(),document_id,4,'CHANGE',id,version,id,2,repeat('d',64) from leave_request where id="+next)).isInstanceOf(java.sql.SQLException.class);
                    connection.rollback(point);
                } finally {connection.rollback();connection.setAutoCommit(auto);}
                return null;
            });
            StringBuilder evidence = new StringBuilder("V8→V9：隔離庫／獨立 schema，舊欄位投影逐表雜湊（不含明文資料）\n");
            for (int i=0;i<tables.size();i++) evidence.append(tables.get(i)).append(" | rows=").append(counts.get(i)).append(" | before=").append(before.get(i)).append(" | after=").append(after.get(i)).append("\n");
            try { java.nio.file.Files.writeString(java.nio.file.Path.of("target/t05-1-migration-evidence.txt"),evidence); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
        } finally { jdbc.execute("drop schema if exists " + schema + " cascade"); }
    }
    private final java.util.Map<String,String> projections=new java.util.HashMap<>();
    private String fingerprint(String table) {
        String projection=projections.computeIfAbsent(table,t -> {
            int dot=t.indexOf('.');
            return jdbc.queryForList("select column_name from information_schema.columns where table_schema=? and table_name=? order by ordinal_position",String.class,t.substring(0,dot),t.substring(dot+1))
                .stream().map(c -> "\""+c+"\"").collect(java.util.stream.Collectors.joining(","));
        });
        return jdbc.queryForObject("select md5(coalesce(string_agg(row_to_json(t)::text,'|' order by row_to_json(t)::text),'')) from (select "+projection+" from "+table+") t",String.class);
    }
}
