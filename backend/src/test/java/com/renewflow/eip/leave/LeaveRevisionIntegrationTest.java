package com.renewflow.eip.leave;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** L51：真 HTTP、共同鎖、PostgreSQL 與可控 Clock，全部使用隔離庫。 */
class LeaveRevisionIntegrationTest extends LeaveTestBase {
    @MockitoSpyBean LeaveAuditLog revisionAudit;
    @MockitoSpyBean LeaveDocumentRepository documents;
    String revisionBody(TestBrowser owner,long source,String op,String date,String start,String end,String reason,UUID key) {
        var d=owner.get(ME+"/"+source).json(); var doc=d.get("document");
        return json("requestId",str(key.toString()),"operation",str(op),"expectedSourceVersion",String.valueOf(d.get("request").get("version").asLong()),
            "expectedEffectiveRequestId",doc.get("effective").isNull()?"null":String.valueOf(doc.get("effective").get("id").asLong()),
            "expectedEffectiveVersion",String.valueOf(doc.get("effectiveVersion").asLong()),"leaveType",str("PERSONAL"),"workDate",str(date),
            "startTime",str(start),"endTime",str(end),"reason",str(reason));
    }
    Reply revise(TestBrowser owner,long source,String op,String start,String end) {
        return owner.post(ME+"/"+source+"/revisions",revisionBody(owner,source,op,DAY,start,end,"新內容 "+op,UUID.randomUUID()));
    }
    void expectConflict(Reply r,String code) { assertThat(r.status()).as(r.body()).isEqualTo(409);assertThat(r.code()).isEqualTo(code); }
    long id(Reply r) { assertThat(r.status()).as(r.body()).isEqualTo(201);return r.json().get("request").get("id").asLong(); }
    Reply decision(TestBrowser reviewer,long id,boolean approved,String reason) {
        var d=reviewer.get(APPROVALS+"/"+id).json();
        return reviewer.post(APPROVALS+"/"+id+(approved?"/approve":"/reject"),json("expectedVersion",String.valueOf(d.get("request").get("version").asLong()),
            "evidenceToken",str(d.get("evidence").get("token").asString()),"reason",reason==null?"null":str(reason)));
    }
    String completeSnapshot() {
        return snapshot()+"|"+jdbc.queryForList("select row_to_json(d)::text from leave_document d order by id",String.class)
            +"|"+jdbc.queryForList("select row_to_json(a)::text from attendance a order by id",String.class)
            +"|"+jdbc.queryForList("select row_to_json(e)::text from attendance_clock_event e order by id",String.class);
    }
    @Test void revisionEndpointRequiresSessionAndCsrfWithoutAnyWrites() {
        var a=loginAs("alice");long root=submitOk(a,DAY,"09:00","17:00");
        String body=revisionBody(a,root,"REVISE",DAY,"10:00","16:00","權限",UUID.randomUUID());
        String before=completeSnapshot();var anonymous=new TestBrowser(port);anonymous.get("/api/auth/me");
        assertThat(anonymous.post(ME+"/"+root+"/revisions",body).status()).isEqualTo(401);
        assertThat(a.postWithCsrf(ME+"/"+root+"/revisions",body,null).status()).isEqualTo(403);
        assertThat(a.postWithCsrf(ME+"/"+root+"/revisions",body,"invalid").status()).isEqualTo(403);
        assertThat(completeSnapshot()).isEqualTo(before);
    }
    @Test void revisionWaitingForOrganizationLockRechecksDisabledActor() throws Exception {
        var a=loginAs("alice");long root=submitOk(a,DAY,"09:00","17:00");
        String body=revisionBody(a,root,"REVISE",DAY,"10:00","16:00","等待鎖",UUID.randomUUID());String before=completeSnapshot();
        try(var connection=jdbc.getDataSource().getConnection();var pool=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) {statement.execute("select pg_advisory_xact_lock(7050001)");}
            var response=pool.submit(() -> a.post(ME+"/"+root+"/revisions",body));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);boolean waiting=false;
            while(System.nanoTime()<deadline) {
                waiting=jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event='advisory'",Integer.class)>0;
                if(waiting) break;Thread.sleep(20);
            }
            // 先釋放阻擋連線，再做斷言，失敗時也不讓 executor close 永久等待。
            try(var statement=connection.prepareStatement("update employee set enabled=false where id=?")) {statement.setLong(1,alice.getId());statement.executeUpdate();}
            connection.commit();
            assertThat(waiting).isTrue();assertThat(response.get(10,java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(401);
            assertThat(completeSnapshot()).isEqualTo(before);
        }
    }
    @Test void terminalApprovedAndWithdrawnReplaysDoNotResolveApproverAgain() {
        var a=loginAs("alice");var b=loginAs("bob");
        for(boolean approve:new boolean[]{true,false}) {
            String day=approve?"2026-10-06":"2026-10-07";setManager(alice.getId(),bob.getId());
            long root=submitOk(a,day,"09:00","17:00");approve(b,root);
            String body=revisionBody(a,root,"CHANGE",day,"09:00","14:00","終態確認",UUID.randomUUID());long next=id(a.post(ME+"/"+root+"/revisions",body));
            if(approve) assertThat(decision(b,next,true,null).status()).isEqualTo(200);else assertThat(withdraw(a,next).status()).isEqualTo(200);
            setManager(alice.getId(),null);String before=completeSnapshot();var replay=a.post(ME+"/"+root+"/revisions",body);
            assertThat(replay.status()).isEqualTo(200);assertThat(replay.json().get("request").get("id").asLong()).isEqualTo(next);
            assertThat(completeSnapshot()).isEqualTo(before);
        }
    }
    @Test void changeApprovalVersusReassignPreservesSubmittedApprover() throws Exception {
        var a=loginAs("alice");var b=loginAs("bob");var c=loginAs("carol");long root=submitOk(a,DAY,"09:00","17:00");approve(b,root);
        long next=id(revise(a,root,"CHANGE","09:00","14:00"));String token=b.get(APPROVALS+"/"+next).json().get("evidence").get("token").asString();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var decision=pool.submit(() -> {gate.await();return b.post(APPROVALS+"/"+next+"/approve",json("expectedVersion","0","evidenceToken",str(token)));});
            var reassign=pool.submit(() -> {gate.await();return reassign(c,next,erin.getId(),"並行改派新流程");});gate.countDown();
            var dr=decision.get();var rr=reassign.get();assertThat((dr.status()==200?1:0)+(rr.status()==200?1:0)).isEqualTo(1);
            assertThat(dr.status()==200?rr.status():dr.status()).isIn(404,409);
            assertThat(jdbc.queryForObject("select submitted_approver_id from leave_request where id=?",Long.class,next)).isEqualTo(bob.getId());
            var doc=a.get(ME+"/"+next).json().get("document");assertThat(doc.get("effective").get("id").asLong()).isEqualTo(dr.status()==200?next:root);
        }
    }
    @Test void pendingRevisionPreservesContentAndResolvesNewApprover() {
        var a=loginAs("alice"); long root=submitOk(a,DAY,"09:00","17:00"); setManager(alice.getId(),erin.getId());
        long next=id(revise(a,root,"REVISE","10:00","16:00"));
        assertThat(status(root)).isEqualTo("WITHDRAWN");assertThat(status(next)).isEqualTo("PENDING");
        assertThat(approverOf(root)).isEqualTo(bob.getId());assertThat(approverOf(next)).isEqualTo(erin.getId());
        assertThat(jdbc.queryForObject("select start_time::text from leave_request where id=?",String.class,root)).startsWith("09:00");
        var detail=a.get(ME+"/"+next).json();assertThat(detail.get("document").get("versions").size()).isEqualTo(2);
        var history=detail.get("history");assertThat(history.size()).isEqualTo(3);
        assertThat(history.get(0).get("leaveRequestId").asLong()).isEqualTo(root);
        assertThat(history.get(2).get("leaveRequestId").asLong()).isEqualTo(next);
        assertThat(detail.get("document").get("effective").isNull()).isTrue();
        assertThat(approve(loginAs("bob"),root).status()).isEqualTo(409);
        assertThat(decision(loginAs("erin"),next,true,null).status()).isEqualTo(200);
        assertThat(a.get(ME+"/"+next).json().get("document").get("effectiveVersion").asLong()).isEqualTo(1);
    }
    @Test void rejectedResubmissionKeepsReasonAndRejectsOtherSource() {
        var a=loginAs("alice");long root=submitOk(a,DAY,"09:00","17:00");reject(loginAs("bob"),root,"請補充原因");
        String body=revisionBody(a,root,"REVISE",DAY,"10:00","16:00","補充內容",UUID.randomUUID());
        String before=completeSnapshot();assertThat(loginAs("erin").post(ME+"/"+root+"/revisions",body).status()).isEqualTo(404);
        assertThat(completeSnapshot()).isEqualTo(before);long next=id(a.post(ME+"/"+root+"/revisions",body));
        assertThat(status(root)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select decision_reason from leave_request where id=?",String.class,root)).isEqualTo("請補充原因");
        assertThat(revise(a,root,"REVISE","11:00","16:00").status()).isEqualTo(409);assertThat(status(next)).isEqualTo("PENDING");
    }
    @Test void invalidOrNoApproverModificationKeepsOldPending() {
        var a=loginAs("alice");long root=submitOk(a,DAY,"09:00","17:00");String before=completeSnapshot();
        assertThat(revise(a,root,"REVISE","17:00","09:00").status()).isEqualTo(400);assertThat(completeSnapshot()).isEqualTo(before);
        setManager(alice.getId(),null);assertThat(revise(a,root,"REVISE","10:00","16:00").code()).isEqualTo("NO_VALID_APPROVER");
        assertThat(completeSnapshot()).isEqualTo(before);
    }
    @Test void approvedChangeAppliesOnlyAfterApprovalAndDoesNotDoubleCount() {
        var a=loginAs("alice");var b=loginAs("bob");long root=submitOk(a,DAY,"09:00","17:00");approve(b,root);
        long next=id(revise(a,root,"CHANGE","09:00","14:00"));
        var pending=a.get(ME+"/"+next).json().get("document");assertThat(pending.get("effective").get("id").asLong()).isEqualTo(root);
        assertThat(a.get(ME).json().get("effectiveTotalMinutes").asLong()).isEqualTo(480);
        assertThat(decision(b,next,true,null).status()).isEqualTo(200);
        var done=a.get(ME+"/"+next).json().get("document");assertThat(done.get("effective").get("id").asLong()).isEqualTo(next);
        assertThat(done.get("effectiveVersion").asLong()).isEqualTo(2);assertThat(status(root)).isEqualTo("APPROVED");
        assertThat(a.get(ME).json().get("effectiveTotalMinutes").asLong()).isEqualTo(300);
        assertThat(submit(a,DAY,"14:00","17:00").status()).isEqualTo(201);
    }
    @Test void withdrawOrRejectChangePreservesEffectiveAndReleasesOnlyReservation() {
        var a=loginAs("alice");var b=loginAs("bob");long root=submitOk(a,DAY,"09:00","12:00");approve(b,root);
        long next=id(revise(a,root,"CHANGE","09:00","14:00"));assertThat(submit(a,DAY,"12:00","14:00").code()).isEqualTo("LEAVE_OVERLAP");
        assertThat(withdraw(a,next).status()).isEqualTo(200);
        assertThat(a.get(ME+"/"+root).json().get("document").get("effectiveVersion").asLong()).isEqualTo(1);
        next=id(revise(a,root,"CHANGE","09:00","14:00"));assertThat(decision(b,next,false,"維持原核准").status()).isEqualTo(200);
        assertThat(submit(a,DAY,"12:00","14:00").status()).isEqualTo(201);
        assertThat(a.get(ME+"/"+root).json().get("document").get("effective").get("id").asLong()).isEqualTo(root);
    }
    @Test void fullCancellationKeepsOriginalAndOnlyReleasesAfterApproval() {
        var a=loginAs("alice");var b=loginAs("bob");long root=submitOk(a,DAY,"09:00","17:00");approve(b,root);
        long cancel=id(revise(a,root,"CANCEL_ALL","09:00","17:00"));
        expectConflict(submit(a,DAY,"09:00","17:00"),"LEAVE_OVERLAP");assertThat(decision(b,cancel,true,null).status()).isEqualTo(200);
        var doc=a.get(ME+"/"+cancel).json().get("document");assertThat(doc.get("effective").isNull()).isTrue();assertThat(doc.get("canceled").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select minutes from leave_request where id=?",Integer.class,cancel)).isEqualTo(480);
        assertThat(status(root)).isEqualTo("APPROVED");assertThat(submit(a,DAY,"09:00","17:00").status()).isEqualTo(201);
        assertThat(revise(a,cancel,"CHANGE","09:00","14:00").code()).isEqualTo("SOURCE_INACTIVE");
    }
    @Test void partialCancellationRequiresOneEdgeTrueSubsetAndSupportsPastDates() {
        var a=loginAs("alice");var b=loginAs("bob");long root=submitOk(a,DAY,"09:00","17:00");approve(b,root);
        for(String[] times:new String[][]{{"09:00","17:00"},{"08:00","17:00"},{"10:00","16:00"},{"14:00","14:00"}}) {
            String before=completeSnapshot();assertThat(revise(a,root,"CANCEL_PART",times[0],times[1]).status()).isEqualTo(400);assertThat(completeSnapshot()).isEqualTo(before);
        }
        assertThat(a.post(ME+"/"+root+"/revisions",revisionBody(a,root,"CANCEL_PART","2026-10-06","09:00","14:00","錯日期",UUID.randomUUID())).status()).isEqualTo(400);
        long part=id(revise(a,root,"CANCEL_PART","09:00","14:00"));decision(b,part,true,null);
        long next=id(revise(a,part,"CANCEL_PART","10:00","14:00"));decision(b,next,true,null);
        assertThat(a.get(ME).json().get("effectiveTotalMinutes").asLong()).isEqualTo(240);
        clock.set(Instant.parse("2024-01-01T01:00:00Z"));long old=submitOk(a,"2024-01-02","09:00","17:00");approve(b,old);
        clock.set(Instant.parse("2026-10-01T01:00:00Z"));
        long past=id(a.post(ME+"/"+old+"/revisions",revisionBody(a,old,"CANCEL_ALL","2024-01-02","09:00","17:00","過去日期銷假",UUID.randomUUID())));
        assertThat(decision(b,past,true,null).status()).isEqualTo(200);
    }
    @Test void dedupAfterTerminalAndSettingsChangesKeepsOriginalWorkflow() {
        var a=loginAs("alice");var b=loginAs("bob");long root=submitOk(a,DAY,"09:00","17:00");approve(b,root);
        String body=revisionBody(a,root,"CHANGE",DAY,"09:00","14:00","同鍵",UUID.randomUUID());
        long next=id(a.post(ME+"/"+root+"/revisions",body));decision(b,next,false,"退回");setManager(alice.getId(),null);
        int rows=leaveRows(),history=leaveHistoryRows();Reply replay=a.post(ME+"/"+root+"/revisions",body);
        assertThat(replay.status()).isEqualTo(200);assertThat(replay.json().get("request").get("id").asLong()).isEqualTo(next);
        assertThat(leaveRows()).isEqualTo(rows);assertThat(leaveHistoryRows()).isEqualTo(history);
        assertThat(a.post(ME+"/"+root+"/revisions",body.replace("09:00","10:00")).code()).isEqualTo("REQUEST_ID_CONFLICT");
        assertThat(a.post(ME+"/"+root+"/revisions",body.replace("CHANGE","CANCEL_PART")).code()).isEqualTo("REQUEST_ID_CONFLICT");
        assertThat(a.post(ME+"/"+next+"/revisions",body).code()).isEqualTo("REQUEST_ID_CONFLICT");
    }
    @Test void staleEffectiveVersionAndUnauthorizedReviewHaveNoSideEffects() {
        var a=loginAs("alice");var b=loginAs("bob");long root=submitOk(a,DAY,"09:00","17:00");approve(b,root);
        String stale=revisionBody(a,root,"CHANGE",DAY,"09:00","14:00","過時",UUID.randomUUID());
        jdbc.update("update leave_document set effective_version=effective_version+1 where id=?",root);
        String before=completeSnapshot();expectConflict(a.post(ME+"/"+root+"/revisions",stale),"EFFECTIVE_CHANGED");assertThat(completeSnapshot()).isEqualTo(before);
        long next=id(revise(a,root,"CHANGE","09:00","14:00"));before=completeSnapshot();
        assertThat(loginAs("carol").get(APPROVALS+"/"+next).status()).isEqualTo(404);
        assertThat(loginAs("erin").post(APPROVALS+"/"+next+"/approve",versionBody(0)).status()).isEqualTo(404);
        assertThat(a.get(APPROVALS+"/"+next).status()).isEqualTo(404);assertThat(completeSnapshot()).isEqualTo(before);
    }
    @Test void evidenceTokenAndHistoryAreBoundedAndImmutable() {
        var a=loginAs("alice");var b=loginAs("bob");long root=submitOk(a,DAY,"09:00","17:00");approve(b,root);
        jdbc.update("insert into attendance(employee_id,work_date,clock_in_at,clock_out_at,source) values (?,date '2026-10-05',timestamptz '2026-10-05 01:00+00',timestamptz '2026-10-05 04:00+00','MANUAL')",alice.getId());
        jdbc.update("insert into attendance(employee_id,work_date,clock_in_at) values (?,date '2026-10-05',timestamptz '2026-10-05 06:00+00')",alice.getId());
        long next=id(revise(a,root,"CHANGE","09:00","14:00"));var viewed=b.get(APPROVALS+"/"+next);
        assertThat(viewed.body()).doesNotContain("latitude").doesNotContain("longitude");assertThat(viewed.json().get("evidence").get("records").size()).isEqualTo(2);
        String token=viewed.json().get("evidence").get("token").asString();jdbc.update("update attendance set version=version+1,corrected_at=now() where employee_id=?",alice.getId());
        String before=completeSnapshot();
        assertThat(b.post(APPROVALS+"/"+next+"/approve",json("expectedVersion","0","evidenceToken",str(token))).code()).isEqualTo("ATTENDANCE_EVIDENCE_CHANGED");
        assertThat(completeSnapshot()).isEqualTo(before);assertThat(decision(b,next,true,null).status()).isEqualTo(200);
        String saved=jdbc.queryForObject("select decision_snapshot from leave_history where leave_request_id=? and action='APPROVED'",String.class,next);
        jdbc.update("update attendance set version=version+1,voided_at=now(),voided_by=? where employee_id=?",carol.getId(),alice.getId());
        assertThat(jdbc.queryForObject("select decision_snapshot from leave_history where leave_request_id=? and action='APPROVED'",String.class,next)).isEqualTo(saved);
        assertThat(loginAs("erin").get(APPROVALS+"/"+next).status()).isEqualTo(404);
    }
    @Test void sameKeyConcurrentAndChangeVersusCancellationCreateOneWorkflow() throws Exception {
        var a=loginAs("alice");long root=submitOk(a,DAY,"09:00","17:00");approve(loginAs("bob"),root);
        String body=revisionBody(a,root,"CHANGE",DAY,"09:00","14:00","並行同鍵",UUID.randomUUID());var other=loginAs("alice");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var x=pool.submit(()->{gate.await();return a.post(ME+"/"+root+"/revisions",body);});
            var y=pool.submit(()->{gate.await();return other.post(ME+"/"+root+"/revisions",body);});gate.countDown();
            var results=java.util.List.of(x.get(),y.get());assertThat(results.stream().map(Reply::status)).containsExactlyInAnyOrder(201,200);
            assertThat(results.get(0).json().get("request").get("id").asLong()).isEqualTo(results.get(1).json().get("request").get("id").asLong());
        }
        assertThat(leaveRows()).isEqualTo(2);assertThat(jdbc.queryForObject("select count(*) from leave_request where status='PENDING'",Integer.class)).isEqualTo(1);
        long pending=jdbc.queryForObject("select id from leave_request where status='PENDING'",Long.class);withdraw(a,pending);
        String change=revisionBody(a,root,"CHANGE",DAY,"09:00","14:00","變更",UUID.randomUUID());
        String cancel=revisionBody(a,root,"CANCEL_ALL",DAY,"09:00","17:00","銷假",UUID.randomUUID());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var x=pool.submit(()->{gate.await();return a.post(ME+"/"+root+"/revisions",change);});
            var y=pool.submit(()->{gate.await();return other.post(ME+"/"+root+"/revisions",cancel);});gate.countDown();
            assertThat(java.util.List.of(x.get().status(),y.get().status())).containsExactlyInAnyOrder(201,409);
        }
    }
    @Test void modificationAndApprovalRaceNeverApproveOldContentOntoNewRevision() throws Exception {
        var a=loginAs("alice");var b=loginAs("bob");long root=submitOk(a,DAY,"09:00","17:00");
        String body=revisionBody(a,root,"REVISE",DAY,"10:00","16:00","競爭",UUID.randomUUID());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var x=pool.submit(()->{gate.await();return a.post(ME+"/"+root+"/revisions",body);});
            var y=pool.submit(()->{gate.await();return b.post(APPROVALS+"/"+root+"/approve",versionBody(0));});gate.countDown();
            var revised=x.get();var approved=y.get();
            if(revised.status()==201) {assertThat(approved.status()).isEqualTo(409);assertThat(a.get(ME+"/"+root).json().get("document").get("effective").isNull()).isTrue();}
            else {assertThat(revised.status()).isEqualTo(409);assertThat(approved.status()).isEqualTo(200);}
        }
    }
    @Test void submissionAuditFailureRollsBackReplacementAndDedup() {
        var a=loginAs("alice");long root=submitOk(a,DAY,"09:00","17:00");
        String body=revisionBody(a,root,"REVISE",DAY,"10:00","16:00","回滾",UUID.randomUUID());String before=completeSnapshot();
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("injected after real history write");}).when(revisionAudit)
            .request(any(),eq(LeaveEnums.HistoryAction.SUBMITTED),any(),any(),any(),any(),any(),any(),any());
        assertThat(a.post(ME+"/"+root+"/revisions",body).status()).isEqualTo(500);assertThat(completeSnapshot()).isEqualTo(before);
        doCallRealMethod().when(revisionAudit).request(any(),eq(LeaveEnums.HistoryAction.SUBMITTED),any(),any(),any(),any(),any(),any(),any());
        assertThat(a.post(ME+"/"+root+"/revisions",body).status()).isEqualTo(201);
    }
    @Test void effectiveSwitchFailureRollsBackApprovalAndHistory() {
        var a=loginAs("alice");var b=loginAs("bob");long root=submitOk(a,DAY,"09:00","17:00");approve(b,root);
        long next=id(revise(a,root,"CHANGE","09:00","14:00"));String before=completeSnapshot();
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("injected after effective switch");}).when(documents)
            .activate(any(),anyLong(),any(),any(),anyBoolean());
        assertThat(decision(b,next,true,null).status()).isEqualTo(500);assertThat(completeSnapshot()).isEqualTo(before);
        doCallRealMethod().when(documents).activate(any(),anyLong(),any(),any(),anyBoolean());assertThat(decision(b,next,true,null).status()).isEqualTo(200);
    }
    @Test void changeOverlapExcludesSelfButChecksOtherPendingAndEffectiveDocuments() {
        var a=loginAs("alice");var b=loginAs("bob");long root=submitOk(a,DAY,"09:00","12:00");approve(b,root);
        long other=submitOk(a,DAY,"12:00","14:00");String before=completeSnapshot();
        expectConflict(revise(a,root,"CHANGE","09:00","14:00"),"LEAVE_OVERLAP");assertThat(completeSnapshot()).isEqualTo(before);
        approve(b,other);before=completeSnapshot();expectConflict(revise(a,root,"CHANGE","09:00","14:00"),"LEAVE_OVERLAP");
        assertThat(completeSnapshot()).isEqualTo(before);
        long adjacent=id(revise(a,root,"CHANGE","09:00","12:00"));assertThat(decision(b,adjacent,true,null).status()).isEqualTo(200);
    }
    @Test void invalidDesignationNeverFallsBackAndOldPendingSurvives() {
        var a=loginAs("alice");long root=submitOk(a,DAY,"09:00","17:00");
        assertThat(putSetting(loginAs("carol"),alice.getId(),erin.getId(),"指定審核人").status()).isEqualTo(200);
        jdbc.update("update employee set enabled=false where id=?",erin.getId());String before=completeSnapshot();
        assertThat(revise(a,root,"REVISE","10:00","16:00").code()).isEqualTo("NO_VALID_APPROVER");
        assertThat(completeSnapshot()).isEqualTo(before);assertThat(status(root)).isEqualTo("PENDING");assertThat(approverOf(root)).isEqualTo(bob.getId());
    }
    @Test void duplicateApprovalsUpdateEffectiveOnlyOnce() throws Exception {
        var a=loginAs("alice");var b=loginAs("bob");long root=submitOk(a,DAY,"09:00","17:00");approve(b,root);
        long next=id(revise(a,root,"CHANGE","09:00","14:00"));var d=b.get(APPROVALS+"/"+next).json();
        String body=json("expectedVersion","0","evidenceToken",str(d.get("evidence").get("token").asString()));var second=loginAs("bob");
        int history=leaveHistoryRows();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var x=pool.submit(()->{gate.await();return b.post(APPROVALS+"/"+next+"/approve",body);});
            var y=pool.submit(()->{gate.await();return second.post(APPROVALS+"/"+next+"/approve",body);});gate.countDown();
            assertThat(java.util.List.of(x.get().status(),y.get().status())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(leaveHistoryRows()).isEqualTo(history+1);
        assertThat(a.get(ME+"/"+next).json().get("document").get("effectiveVersion").asLong()).isEqualTo(2);
    }
    @Test void approvalAuditFailureRollsBackEffectiveAndFrozenEvidence() {
        var a=loginAs("alice");var b=loginAs("bob");long root=submitOk(a,DAY,"09:00","17:00");approve(b,root);
        long next=id(revise(a,root,"CHANGE","09:00","14:00"));String before=completeSnapshot();
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("injected decision history failure");}).when(revisionAudit)
            .decision(any(),any(),any(),any(),any(),any(),any());
        assertThat(decision(b,next,true,null).status()).isEqualTo(500);assertThat(completeSnapshot()).isEqualTo(before);
        doCallRealMethod().when(revisionAudit).decision(any(),any(),any(),any(),any(),any(),any());assertThat(decision(b,next,true,null).status()).isEqualTo(200);
    }
}
