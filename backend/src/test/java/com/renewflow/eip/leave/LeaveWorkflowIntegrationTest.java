package com.renewflow.eip.leave;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** T05.2真HTTP／PostgreSQL；失敗／預覽比較完整表，並行以共同鎖或閘門控制。 */
class LeaveWorkflowIntegrationTest extends LeaveTestBase {
    static final String WF="/api/admin/leave/workflow";
    String draftBody(UUID key,Long current,Long row,String name,String rule,Long approver) {
        return json("requestId",str(key.toString()),"expectedPublishedId",String.valueOf(current),"expectedVersion",String.valueOf(row),
            "values",json("name",str(name),"description",str("測試流程說明"),"source",str(rule),"designatedApproverId",String.valueOf(approver)));
    }
    Long current() { return jdbc.queryForObject("select current_version_id from leave_workflow_state where id=1",Long.class); }
    long draft(TestBrowser admin,String name,String rule,Long approver) {
        var r=admin.post(WF+"/drafts",draftBody(UUID.randomUUID(),current(),null,name,rule,approver));
        assertThat(r.status()).as(r.body()).isEqualTo(200);return r.json().get("version").get("id").asLong();
    }
    String publishBody(UUID key,long row) { return json("requestId",str(key.toString()),"expectedVersion",String.valueOf(row),"reason",str("確認單關卡流程")); }
    Reply publish(TestBrowser admin,long id) {
        long row=jdbc.queryForObject("select row_version from leave_workflow_version where id=?",Long.class,id);
        return admin.post(WF+"/drafts/"+id+"/publish",publishBody(UUID.randomUUID(),row));
    }
    long workflow(TestBrowser admin,String name,String rule,Long target) { long id=draft(admin,name,rule,target);assertThat(publish(admin,id).status()).isEqualTo(200);return id; }
    String all() {
        var tables=List.of("employee","department","organization_history","leave_request","leave_document","leave_history","leave_approver_setting","attendance","attendance_history","attendance_clock_event","leave_workflow_version","leave_workflow_state","leave_workflow_history");
        return tables.stream().map(t->t+jdbc.queryForList("select row_to_json(x)::text from "+t+" x order by row_to_json(x)::text",String.class)).toList().toString();
    }
    tools.jackson.databind.JsonNode snap(TestBrowser owner,long id) { return owner.get(ME+"/"+id).json().get("request").get("approvalSnapshot"); }
    String revision(TestBrowser owner,long root,String op,String start,String end,UUID key) {
        var d=owner.get(ME+"/"+root).json();var doc=d.get("document");
        return json("requestId",str(key.toString()),"operation",str(op),"expectedSourceVersion",d.get("request").get("version").toString(),
            "expectedEffectiveRequestId",doc.get("effective").isNull()?"null":doc.get("effective").get("id").toString(),"expectedEffectiveVersion",doc.get("effectiveVersion").toString(),
            "leaveType",str("PERSONAL"),"workDate",str(DAY),"startTime",str(start),"endTime",str(end),"reason",str("流程版本測試"));
    }
    Reply decision(TestBrowser reviewer,long id,boolean approve) {
        var detail=reviewer.get(APPROVALS+"/"+id).json();
        return reviewer.post(APPROVALS+"/"+id+(approve?"/approve":"/reject"),json("expectedVersion",detail.get("request").get("version").toString(),
            "evidenceToken",str(detail.get("evidence").get("token").asString()),"reason",str("測試審核")));
    }

    @Test void permissionsSessionAndCsrfAreEnforcedWithoutWrites() {
        var c=loginAs("carol");String before=all(),body=draftBody(UUID.randomUUID(),null,null,"測試","MANAGER",null);
        for(String user:List.of("alice","bob")) {
            var b=loginAs(user);assertThat(b.get(WF).status()).isEqualTo(403);assertThat(b.post(WF+"/drafts",body).status()).isEqualTo(403);
            assertThat(b.get(WF+"/preview?employeeId="+alice.getId()).status()).isEqualTo(403);
        }
        var anonymous=new TestBrowser(port);anonymous.get("/api/auth/me");assertThat(anonymous.get(WF).status()).isEqualTo(401);
        assertThat(c.postWithCsrf(WF+"/drafts",body,null).status()).isEqualTo(403);
        assertThat(c.get(WF).status()).isEqualTo(200);assertThat(all()).isEqualTo(before);
    }
    @Test void noPublishedKeepsLegacyPriorityAndDraftDoesNotAffectSubmit() {
        var a=loginAs("alice");var c=loginAs("carol");
        draft(c,"僅草稿","DESIGNATED",erin.getId());String before=all();
        var preview=c.get(WF+"/preview?employeeId="+alice.getId()).json();assertThat(preview.get("approver").get("id").asLong()).isEqualTo(bob.getId());
        assertThat(all()).isEqualTo(before);
        long id=submitOk(a,DAY,"09:00","10:00");assertThat(snap(a,id).get("source").asString()).isEqualTo("DEFAULT_MANAGER");assertThat(snap(a,id).get("workflowVersionId").isNull()).isTrue();
        assertThat(putSetting(c,alice.getId(),erin.getId(),"個人指定").status()).isEqualTo(200);
        long next=submitOk(a,DAY,"10:00","11:00");assertThat(approverOf(next)).isEqualTo(erin.getId());assertThat(snap(a,next).get("source").asString()).isEqualTo("PERSONAL");
    }
    @Test void publishingOnlyChangesNewSubmissionsAndPreservesSnapshotDisplayNames() {
        var c=loginAs("carol");var a=loginAs("alice");long v1=workflow(c,"流程一","MANAGER",null);
        long first=submitOk(a,DAY,"09:00","10:00");assertThat(snap(a,first).isObject()).as("新提交必須保存流程快照").isTrue();var before=snap(a,first).toString();
        long v2=workflow(c,"流程二","DESIGNATED",erin.getId());long second=submitOk(a,DAY,"10:00","11:00");
        assertThat(snap(a,first).get("workflowVersionId").asLong()).isEqualTo(v1);assertThat(approverOf(first)).isEqualTo(bob.getId());
        assertThat(snap(a,second).get("workflowVersionId").asLong()).isEqualTo(v2);assertThat(approverOf(second)).isEqualTo(erin.getId());
        jdbc.update("update employee set display_name='變更後名稱' where id=?",bob.getId());
        assertThat(snap(a,first).toString()).isEqualTo(before);assertThat(current()).isEqualTo(v2);
    }
    @Test void personalOverrideInvalidTargetAndSelfDoNotFallback() {
        var c=loginAs("carol");var a=loginAs("alice");workflow(c,"有效共用","MANAGER",null);
        putSetting(c,alice.getId(),erin.getId(),"優先");jdbc.update("update employee set enabled=false where id=?",erin.getId());
        String before=all();assertThat(submit(a,DAY,"09:00","10:00").code()).isEqualTo("NO_VALID_APPROVER");
        var p=c.get(WF+"/preview?employeeId="+alice.getId()).json();assertThat(p.get("personalOverride").asBoolean()).isTrue();assertThat(p.get("problem").isNull()).isFalse();assertThat(all()).isEqualTo(before);
        putSetting(c,alice.getId(),null,"移除失效個人設定");
        long self=workflow(c,"指定管理員","DESIGNATED",carol.getId());
        assertThat(submit(c,DAY,"09:00","10:00").code()).isEqualTo("NO_VALID_APPROVER");assertThat(current()).isEqualTo(self);
        setManager(alice.getId(),null);workflow(c,"改回主管","MANAGER",null);
        assertThat(submit(a,DAY,"09:00","10:00").code()).isEqualTo("NO_VALID_APPROVER");
    }
    @Test void previewUsesSameResolutionAndRevalidatesAtSubmit() {
        var c=loginAs("carol");long d=draft(c,"預覽草稿","DESIGNATED",erin.getId());String before=all();
        var p=c.get(WF+"/preview?employeeId="+alice.getId()+"&draftId="+d).json();assertThat(p.get("approver").get("id").asLong()).isEqualTo(erin.getId());assertThat(all()).isEqualTo(before);
        assertThat(publish(c,d).status()).isEqualTo(200);jdbc.update("update employee set role='EMPLOYEE' where id=?",erin.getId());
        assertThat(submit(loginAs("alice"),DAY,"09:00","10:00").code()).isEqualTo("NO_VALID_APPROVER");
    }
    @Test void editsPublishAndOperationRetriesHaveStableResults() throws Exception {
        var c=loginAs("carol");var dave=loginAs("dave");long d=draft(c,"原草稿","MANAGER",null);
        var replies=runSimultaneously(List.<Callable<Reply>>of(
            ()->c.put(WF+"/drafts/"+d,draftBody(UUID.randomUUID(),null,0L,"甲","MANAGER",null)),
            ()->dave.put(WF+"/drafts/"+d,draftBody(UUID.randomUUID(),null,0L,"乙","MANAGER",null))));
        assertThat(replies.stream().map(Reply::status).toList()).containsExactlyInAnyOrder(200,409);
        UUID key=UUID.randomUUID();String body=publishBody(key,1);
        var published=runSimultaneously(List.<Callable<Reply>>of(()->c.post(WF+"/drafts/"+d+"/publish",body),()->c.post(WF+"/drafts/"+d+"/publish",body)));
        assertThat(published).allSatisfy(r->assertThat(r.status()).as(r.body()).isEqualTo(200));
        assertThat(jdbc.queryForObject("select count(*) from leave_workflow_history where action='PUBLISHED'",Integer.class)).isEqualTo(1);
        assertThat(c.get(WF+"/operations/"+key).json().get("version").get("id").asLong()).isEqualTo(d);
        assertThat(dave.post(WF+"/drafts/"+d+"/publish",body).code()).isEqualTo("WORKFLOW_REQUEST_CONFLICT");
        assertThat(c.post(WF+"/drafts/"+d+"/publish",publishBody(UUID.randomUUID(),1)).code()).isEqualTo("WORKFLOW_NOT_DRAFT");
    }
    @Test void distinctConcurrentPublicationsHaveOneWinner() throws Exception {
        var c=loginAs("carol");var admin2=loginAs("dave");long d=draft(c,"同時發布","MANAGER",null);
        var replies=runSimultaneously(List.<Callable<Reply>>of(()->publish(c,d),()->publish(admin2,d)));
        assertThat(replies.stream().map(Reply::status).toList()).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.queryForObject("select count(*) from leave_workflow_history where action='PUBLISHED'",Integer.class)).isEqualTo(1);
    }
    @Test void requestReplayKeepsOriginalEvenAfterPublishingAndDisablingOldReviewer() {
        var c=loginAs("carol");var a=loginAs("alice");workflow(c,"原流程","DESIGNATED",erin.getId());
        String body=leaveBody(UUID.randomUUID(),"PERSONAL",DAY,"09:00","10:00","回應遺失");var first=a.post(ME,body);var original=first.json().get("request").get("approvalSnapshot");
        workflow(c,"後流程","MANAGER",null);jdbc.update("update employee set enabled=false where id=?",erin.getId());String before=all();
        var replay=a.post(ME,body);assertThat(replay.status()).isEqualTo(200);assertThat(replay.json().get("request").get("id")).isEqualTo(first.json().get("request").get("id"));
        assertThat(replay.json().get("request").get("approvalSnapshot")).isEqualTo(original);assertThat(all()).isEqualTo(before);
        assertThat(a.post(ME,body.replace("回應遺失","不同內容")).code()).isEqualTo("REQUEST_ID_CONFLICT");
    }
    @Test void revisionsReparseButReassignAndHistoricalReadsPreserveOriginalScope() {
        var c=loginAs("carol");var a=loginAs("alice");workflow(c,"原流程","MANAGER",null);long root=submitOk(a,DAY,"09:00","17:00");String original=snap(a,root).toString();
        workflow(c,"機密後續流程","DESIGNATED",erin.getId());String body=revision(a,root,"REVISE","10:00","16:00",UUID.randomUUID());var revision=a.post(ME+"/"+root+"/revisions",body);
        assertThat(revision.status()).as(revision.body()).isEqualTo(201);long next=revision.json().get("request").get("id").asLong();assertThat(approverOf(next)).isEqualTo(erin.getId());
        var old=loginAs("bob").get(APPROVALS+"/"+root);assertThat(old.body()).doesNotContain("機密後續流程");assertThat(old.json().get("document").get("versions").size()).isEqualTo(1);
        String snapshot=snap(a,next).toString();assertThat(reassign(c,next,bob.getId(),"合法改派").status()).isEqualTo(200);
        assertThat(snap(a,next).toString()).isEqualTo(snapshot);assertThat(snap(a,root).toString()).isEqualTo(original);assertThat(loginAs("erin").get(APPROVALS+"/"+next).status()).isEqualTo(404);
        assertThat(a.post(ME+"/"+root+"/revisions",body).json().get("request").get("approvalSnapshot").toString()).isEqualTo(snapshot);
    }
    @Test void changesAndBothCancellationsUseNewSnapshotAndKeepEffectiveUntilApproved() {
        var c=loginAs("carol");var a=loginAs("alice");var b=loginAs("bob");workflow(c,"原主管","MANAGER",null);
        long root=submitOk(a,DAY,"09:00","17:00");assertThat(decision(b,root,true).status()).isEqualTo(200);
        workflow(c,"變更後流程","DESIGNATED",erin.getId());
        for(String op:List.of("CHANGE","CANCEL_PART","CANCEL_ALL")) {
            var r=a.post(ME+"/"+root+"/revisions",revision(a,root,op,"09:00",op.equals("CANCEL_ALL")?"17:00":"14:00",UUID.randomUUID()));
            assertThat(r.status()).as(r.body()).isEqualTo(201);long id=r.json().get("request").get("id").asLong();assertThat(snap(a,id).get("workflowVersionId").asLong()).isEqualTo(current());
            assertThat(withdraw(a,id).status()).isEqualTo(200);assertThat(a.get(ME+"/"+root).json().get("document").get("effective").get("id").asLong()).isEqualTo(root);
        }
    }
    @Test void publishedAndSnapshotsAreDatabaseImmutableAndHistoryAppendOnly() {
        var c=loginAs("carol");long v=workflow(c,"不可變","MANAGER",null);long id=submitOk(loginAs("alice"),DAY,"09:00","10:00");
        assertThatThrownBy(()->jdbc.update("update leave_workflow_version set name='竄改' where id=?",v)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("delete from leave_workflow_history")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("update leave_request set approval_snapshot=null where id=?",id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("delete from leave_workflow_version where id=?",v)).isInstanceOf(RuntimeException.class);
    }
    @Test void workflowHistoryFailureRollsBackPublicationAndCanRetrySameKey() {
        var c=loginAs("carol");long d=draft(c,"回滾","MANAGER",null);String before=all();String body=publishBody(UUID.randomUUID(),0);
        jdbc.execute("create function test_workflow_fail() returns trigger language plpgsql as $$ begin raise exception 'injected history failure'; end $$");
        jdbc.execute("create trigger test_workflow_fail before insert on leave_workflow_history for each row execute function test_workflow_fail()");
        try {assertThat(c.post(WF+"/drafts/"+d+"/publish",body).status()).isEqualTo(500);assertThat(all()).isEqualTo(before);}
        finally {jdbc.execute("drop trigger test_workflow_fail on leave_workflow_history");jdbc.execute("drop function test_workflow_fail()");}
        assertThat(c.post(WF+"/drafts/"+d+"/publish",body).status()).isEqualTo(200);
    }
    @Test void publishingWaitsForOrganizationAndRechecksTargetAfterRoleChange() throws Exception {
        var c=loginAs("carol");long d=draft(c,"並行資格","DESIGNATED",erin.getId());String body=publishBody(UUID.randomUUID(),0);
        try(var connection=jdbc.getDataSource().getConnection();var pool=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);try(var st=connection.createStatement()){st.execute("select pg_advisory_xact_lock(7050001)");}
            var response=pool.submit(()->c.post(WF+"/drafts/"+d+"/publish",body));boolean waited=false;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime()<deadline && !response.isDone()) { if(jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event='advisory'",Integer.class)>0){waited=true;break;}Thread.sleep(20); }
            try(var st=connection.prepareStatement("update employee set role='EMPLOYEE' where id=?")){st.setLong(1,erin.getId());st.executeUpdate();}connection.commit();
            var reply=response.get(10,TimeUnit.SECONDS);assertThat(reply.status()).as(reply.body()).isEqualTo(409);assertThat(reply.code()).isEqualTo("APPROVER_INVALID");assertThat(waited).as("發布必須等待共同鎖").isTrue();assertThat(current()).isNull();
        }
    }
    @Test void concurrentPublishAndSubmitUseOneCompleteVersion() throws Exception {
        var c=loginAs("carol");var a=loginAs("alice");long v1=workflow(c,"舊","MANAGER",null);long d=draft(c,"新","DESIGNATED",erin.getId());
        var results=runSimultaneously(List.<Callable<Reply>>of(()->publish(c,d),()->submit(a,DAY,"09:00","10:00")));
        assertThat(results.get(0).status()).isEqualTo(200);var request=results.get(1).json().get("request");var s=request.get("approvalSnapshot");
        long chosen=s.get("workflowVersionId").asLong();assertThat(chosen).isIn(v1,d);
        assertThat(s.get("approver").get("id").asLong()).isEqualTo(chosen==v1?bob.getId():erin.getId());assertThat(request.get("approver").get("id")).isEqualTo(s.get("approver").get("id"));
    }
    @Test void invalidDraftTargetsAndStaleBaseCannotBePublished() {
        var c=loginAs("carol");String before=all();
        for(Long target:List.of(alice.getId(),-999L)) {
            assertThat(c.post(WF+"/drafts",draftBody(UUID.randomUUID(),null,null,"非法指定","DESIGNATED",target)).code()).isEqualTo("APPROVER_INVALID");
            assertThat(all()).isEqualTo(before);
        }
        long v1=workflow(c,"第一版","MANAGER",null);long v2=workflow(c,"第二版","MANAGER",null);long d=draft(c,"基礎過期","MANAGER",null);
        jdbc.update("update leave_workflow_version set base_published_id=? where id=?",v1,d);
        before=all();assertThat(publish(c,d).code()).isEqualTo("WORKFLOW_CHANGED");assertThat(all()).isEqualTo(before);assertThat(current()).isEqualTo(v2);
        assertThat(c.post(WF+"/drafts/"+d+"/publish",json("requestId",str(UUID.randomUUID().toString()),"expectedVersion","0","reason",str("  "))).code()).isEqualTo("REASON_REQUIRED");
    }
    @Test void rejectedResubmissionUsesCurrentVersionAndOldSnapshotRemains() {
        var c=loginAs("carol");var a=loginAs("alice");workflow(c,"退回前","MANAGER",null);long root=submitOk(a,DAY,"09:00","17:00");
        String original=snap(a,root).toString();assertThat(decision(loginAs("bob"),root,false).status()).isEqualTo(200);
        workflow(c,"重送時","DESIGNATED",erin.getId());String body=revision(a,root,"REVISE","10:00","16:00",UUID.randomUUID());
        var submitted=a.post(ME+"/"+root+"/revisions",body);assertThat(submitted.status()).as(submitted.body()).isEqualTo(201);
        assertThat(submitted.json().get("request").get("approvalSnapshot").get("workflowVersionId").asLong()).isEqualTo(current());
        assertThat(snap(a,root).toString()).isEqualTo(original);assertThat(status(root)).isEqualTo("REJECTED");
    }
    @Test void operationReplayPrecedesCurrentDraftValidationAndUsesOriginalFrozenResult() {
        var c=loginAs("carol");UUID key=UUID.randomUUID();String original=draftBody(key,null,null,"建立結果遺失","MANAGER",null);
        var first=c.post(WF+"/drafts",original);long id=first.json().get("version").get("id").asLong();
        publish(c,id);workflow(c,"後來發布","MANAGER",null);String before=all();
        var replay=c.post(WF+"/drafts",original);assertThat(replay.status()).isEqualTo(200);assertThat(replay.json().get("version")).isEqualTo(first.json().get("version"));
        assertThat(replay.json().get("replayed").asBoolean()).isTrue();assertThat(all()).isEqualTo(before);
        assertThat(c.post(WF+"/drafts",original.replace("建立結果遺失","不同內容")).code()).isEqualTo("WORKFLOW_REQUEST_CONFLICT");
        assertThat(c.get(WF+"/operations/"+UUID.randomUUID()).status()).isEqualTo(404);
    }
}
