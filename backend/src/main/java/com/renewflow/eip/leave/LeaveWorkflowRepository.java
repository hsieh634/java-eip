package com.renewflow.eip.leave;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static com.renewflow.eip.leave.LeaveWorkflowDtos.*;

@Repository
class LeaveWorkflowRepository {
    record StoredOperation(long actorId, String fingerprint, Operation result) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    LeaveWorkflowRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc;this.mapper=mapper; }
    private final RowMapper<Version> versionMapper=(rs,n)->new Version(rs.getLong("id"),rs.getInt("version_no"),rs.getString("status"),
        rs.getLong("row_version"),rs.getObject("base_published_id",Long.class),
        new Values(rs.getString("name"),rs.getString("description"),Rule.valueOf(rs.getString("source")),rs.getObject("designated_approver_id",Long.class)),
        rs.getLong("created_by"),rs.getTimestamp("created_at").toInstant(),rs.getObject("published_by",Long.class),
        rs.getTimestamp("published_at")==null?null:rs.getTimestamp("published_at").toInstant(),rs.getString("publish_reason"));
    Long currentId() { return jdbc.queryForObject("select current_version_id from leave_workflow_state where id=1",Long.class); }
    Version current() { Long id=currentId();return id==null?null:find(id); }
    Version find(Long id) { return jdbc.query("select * from leave_workflow_version where id=?",versionMapper,id).stream().findFirst().orElse(null); }
    Version draft() { return jdbc.query("select * from leave_workflow_version where status='DRAFT'",versionMapper).stream().findFirst().orElse(null); }
    List<Version> versions() { return jdbc.query("select * from leave_workflow_version order by version_no desc",versionMapper); }
    Version create(Values v, Long base, long actor, Instant now) {
        Long id=jdbc.queryForObject("""
            insert into leave_workflow_version(version_no,name,description,source,designated_approver_id,base_published_id,created_by,created_at)
            values((select coalesce(max(version_no),0)+1 from leave_workflow_version),?,?,?,?,?,?,?) returning id
            """,Long.class,v.name(),v.description(),v.source().name(),v.designatedApproverId(),base,actor,Timestamp.from(now));
        return find(id);
    }
    int save(long id,long version,Values v) {
        return jdbc.update("update leave_workflow_version set name=?,description=?,source=?,designated_approver_id=?,row_version=row_version+1 where id=? and row_version=? and status='DRAFT'",
            v.name(),v.description(),v.source().name(),v.designatedApproverId(),id,version);
    }
    int publish(long id,long version,long actor,String reason,Instant now) {
        return jdbc.update("update leave_workflow_version set status='PUBLISHED',row_version=row_version+1,published_by=?,published_at=?,publish_reason=? where id=? and row_version=? and status='DRAFT'",
            actor,Timestamp.from(now),reason,id,version);
    }
    void activate(long id) { jdbc.update("update leave_workflow_state set current_version_id=? where id=1",id); }
    StoredOperation operation(UUID key) {
        return jdbc.query("select * from leave_workflow_history where request_id=?",(rs,n)->new StoredOperation(rs.getLong("actor_id"),rs.getString("fingerprint"),
            new Operation(key,rs.getString("action"),mapper.readValue(rs.getString("after_content"),Version.class),true)),key).stream().findFirst().orElse(null);
    }
    List<History> history() {
        return jdbc.query("select * from leave_workflow_history order by id desc",(rs,n)->new History(rs.getObject("request_id",UUID.class),rs.getString("action"),
            rs.getString("before_content")==null?null:mapper.readValue(rs.getString("before_content"),Version.class),
            mapper.readValue(rs.getString("after_content"),Version.class),rs.getObject("before_published_id",Long.class),rs.getObject("after_published_id",Long.class),
            mapper.readValue(rs.getString("actor_snapshot"),LeaveDtos.PersonRef.class),rs.getString("reason"),rs.getTimestamp("occurred_at").toInstant()));
    }
    void append(UUID key,String action,String fingerprint,Version before,Version after,Long beforeCurrent,Long afterCurrent,
                LeaveDtos.PersonRef actor,String reason,Instant now) {
        jdbc.update("""
            insert into leave_workflow_history(request_id,version_id,action,fingerprint,before_content,after_content,before_published_id,after_published_id,actor_id,actor_snapshot,reason,occurred_at)
            values(?,?,?,?,?,?,?,?,?,?,?,?)
            """,key,after.id(),action,fingerprint,before==null?null:mapper.writeValueAsString(before),mapper.writeValueAsString(after),beforeCurrent,afterCurrent,
            actor.id(),mapper.writeValueAsString(actor),reason,Timestamp.from(now));
    }
}
