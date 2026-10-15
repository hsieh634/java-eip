package com.renewflow.eip.leave;

import com.renewflow.eip.support.TestDatabaseGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class LeaveWorkflowSchemaTest extends LeaveTestBase {
    @Autowired DataSource dataSource;
    @Autowired Environment env;
    @Test void v9ToV10PreservesAllOldColumnsAndDoesNotInventWorkflowSnapshots() throws Exception {
        try(var connection=dataSource.getConnection()) {
            TestDatabaseGuard.assertSafeToClean(TestDatabaseGuard.actualDatabase(connection),env.getRequiredProperty("renewflow.test.database"),env.getRequiredProperty("renewflow.test.dev-database"));
            String schema="workflow_v10_probe_"+UUID.randomUUID().toString().replace("-","").substring(0,8);
            assertThat(jdbc.queryForObject("select count(*) from information_schema.schemata where schema_name=?",Integer.class,schema)).isZero();
            var source=new SingleConnectionDataSource(connection,true);
            var v9=Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).createSchemas(true).cleanDisabled(false).target("9").load();
            var v10=Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).cleanDisabled(false).load();
            String oldSchema=connection.getSchema();
            try {
                v9.migrate();connection.setSchema(schema);var probe=new JdbcTemplate(source);
                probe.update("insert into employee(employee_no,username,display_name,password_hash,role,enabled,created_at,updated_at) values ('M1','manager','舊主管','old-hash','MANAGER',true,now(),now()),('E1','staff','舊員工','old-hash2','EMPLOYEE',true,now(),now())");
                probe.update("update employee set manager_id=(select id from employee where username='manager') where username='staff'");
                probe.update("insert into leave_request(employee_id,request_id,leave_type,work_date,start_time,end_time,minutes,reason,status,approver_id,approver_source,created_at,updated_at) select e.id,gen_random_uuid(),'PERSONAL',date '2026-10-05','09:00','17:00',480,'舊制提交','PENDING',m.id,'MANAGER',now(),now() from employee e,employee m where e.username='staff' and m.username='manager'");
                probe.update("insert into leave_history(leave_request_id,action,to_status,actor_id,occurred_at) select id,'SUBMITTED','PENDING',employee_id,now() from leave_request");
                var tables=List.of("employee","department","organization_history","attendance","attendance_request","attendance_history","geofence_setting","geofence_setting_history","attendance_clock_event","project","timesheet_entry","leave_request","leave_history","leave_approver_setting","attendance_clock_submission","leave_document");
                Map<String,String> columns=new LinkedHashMap<>(),before=new LinkedHashMap<>();
                for(String t:tables) {
                    String cols=String.join(",",probe.queryForList("select column_name from information_schema.columns where table_schema=? and table_name=? order by ordinal_position",String.class,schema,t));
                    columns.put(t,cols);before.put(t,probe.queryForList("select row_to_json(x)::text from (select "+cols+" from "+t+") x order by row_to_json(x)::text",String.class).toString());
                }
                v10.migrate();connection.setSchema(schema);
                for(String t:tables) assertThat(probe.queryForList("select row_to_json(x)::text from (select "+columns.get(t)+" from "+t+") x order by row_to_json(x)::text",String.class).toString()).as(t).isEqualTo(before.get(t));
                assertThat(probe.queryForObject("select count(*) from leave_request where approval_snapshot is not null or workflow_version_id is not null",Integer.class)).isZero();
                assertThat(probe.queryForObject("select count(*) from leave_workflow_version",Integer.class)).isZero();
                assertThat(probe.queryForObject("select current_version_id from leave_workflow_state where id=1",Long.class)).isNull();
                assertThat(probe.queryForObject("select max(version::int) from flyway_schema_history where success",Integer.class)).isEqualTo(10);
            } finally {connection.setSchema(oldSchema);v10.clean();}
        }
    }
}
