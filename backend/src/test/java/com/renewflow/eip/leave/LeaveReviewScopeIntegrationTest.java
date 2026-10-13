package com.renewflow.eip.leave;

import com.renewflow.eip.support.TestBrowser;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 複審R1：同一核心檢查先在47f7fdf失敗，再用修正版真HTTP／PostgreSQL通過。 */
class LeaveReviewScopeIntegrationTest extends LeaveTestBase {
    long revision(TestBrowser a,long source,String op,String date,String reason) {
        var d=a.get(ME+"/"+source).json();var doc=d.get("document");
        var result=a.post(ME+"/"+source+"/revisions",json("requestId",str(UUID.randomUUID().toString()),"operation",str(op),
            "expectedSourceVersion",String.valueOf(d.get("request").get("version").asLong()),"expectedEffectiveRequestId",
            doc.get("effective").isNull()?"null":String.valueOf(doc.get("effective").get("id").asLong()),
            "expectedEffectiveVersion",String.valueOf(doc.get("effectiveVersion").asLong()),"leaveType",str("PERSONAL"),"workDate",str(date),
            "startTime",str("10:00"),"endTime",str("16:00"),"reason",str(reason)));
        assertThat(result.status()).as(result.body()).isEqualTo(201);return result.json().get("request").get("id").asLong();
    }
    void approveEvidence(TestBrowser b,long id) {
        var d=b.get(APPROVALS+"/"+id).json();assertThat(b.post(APPROVALS+"/"+id+"/approve",json("expectedVersion",String.valueOf(d.get("request").get("version").asLong()),
            "evidenceToken",str(d.get("evidence").get("token").asString()))).status()).isEqualTo(200);
    }
    String allBusiness() {return snapshot()+"|"+jdbc.queryForList("select row_to_json(d)::text from leave_document d order by id",String.class)
        +"|"+jdbc.queryForList("select row_to_json(a)::text from attendance a order by id",String.class)
        +"|"+jdbc.queryForList("select row_to_json(e)::text from attendance_clock_event e order by id",String.class);}
    @Test void oldPendingEntryCannotReadNewApproverVersionOrHistory() {
        var a=loginAs("alice");var b=loginAs("bob");var e=loginAs("erin");var admin=loginAs("carol");
        long root=submitOk(a,DAY,"09:00","17:00");setManager(alice.getId(),erin.getId());
        long next=revision(a,root,"REVISE","2026-10-06","R1_SECRET_FUTURE_PENDING");String before=allBusiness();
        var old=b.get(APPROVALS+"/"+root);assertThat(old.status()).isEqualTo(200);var doc=old.json().get("document");
        assertThat(doc.get("versions").size()).isEqualTo(1);assertThat(old.body()).doesNotContain("R1_SECRET_FUTURE_PENDING").doesNotContain("2026-10-06");
        assertThat(doc.get("pending").isNull()).isTrue();assertThat(b.get(APPROVALS+"/"+next).status()).isEqualTo(404);
        assertThat(e.get(APPROVALS+"/"+next).json().get("document").get("versions").size()).isEqualTo(2);
        assertThat(a.get(ME+"/"+root).body()).contains("R1_SECRET_FUTURE_PENDING");
        assertThat(admin.get(ADMIN_REQUESTS+"/"+root).body()).contains("R1_SECRET_FUTURE_PENDING");assertThat(allBusiness()).isEqualTo(before);
    }
    @Test void approvedOldEntryCannotReadFutureDateDecisionOrEffectiveState() {
        var a=loginAs("alice");var b=loginAs("bob");var e=loginAs("erin");
        long root=submitOk(a,DAY,"09:00","17:00");
        jdbc.update("insert into attendance(employee_id,work_date,clock_in_at,clock_out_at,source) values (?,date '2026-10-05',timestamptz '2026-10-05 01:00+00',timestamptz '2026-10-05 04:00+00','MANUAL')",alice.getId());
        approveEvidence(b,root);setManager(alice.getId(),erin.getId());
        jdbc.update("insert into attendance(employee_id,work_date,clock_in_at,clock_out_at,source) values (?,date '2026-10-06',timestamptz '2026-10-06 01:00+00',timestamptz '2026-10-06 04:00+00','MANUAL')",alice.getId());
        long next=revision(a,root,"CHANGE","2026-10-06","R1_SECRET_NEW_DATE");
        var review=e.get(APPROVALS+"/"+next);assertThat(review.body()).contains("2026-10-05").contains("2026-10-06");approveEvidence(e,next);
        String before=allBusiness();var old=b.get(APPROVALS+"/"+root);assertThat(old.status()).isEqualTo(200);
        assertThat(old.body()).doesNotContain("R1_SECRET_NEW_DATE").doesNotContain("2026-10-06");
        var doc=old.json().get("document");assertThat(doc.get("versions").size()).isEqualTo(1);
        assertThat(doc.get("effective").get("id").asLong()).isEqualTo(root);assertThat(doc.get("effectiveVersion").asLong()).isEqualTo(1);
        assertThat(old.json().get("request").get("revision").get("effectiveVersion").asLong()).isEqualTo(1);
        assertThat(old.json().get("evidence").get("dates").size()).isEqualTo(1);assertThat(old.json().get("history").size()).isEqualTo(2);
        for(var h:old.json().get("history")) {assertThat(h.get("leaveRequestId").asLong()).isEqualTo(root);
            if(!h.get("decision").isNull()) assertThat(h.get("decision").get("afterEffectiveRequestId").asLong()).isEqualTo(root);}
        assertThat(b.get(APPROVALS+"/"+next).status()).isEqualTo(404);
        assertThat(a.get(ME+"/"+root).body()).contains("R1_SECRET_NEW_DATE");assertThat(allBusiness()).isEqualTo(before);
    }
    @Test void reassignRemovesOldReaderAndWriterWithoutChangingBusinessOnRead() {
        var a=loginAs("alice");var b=loginAs("bob");long root=submitOk(a,DAY,"09:00","17:00");
        assertThat(reassign(loginAs("carol"),root,erin.getId(),"R1改派").status()).isEqualTo(200);String before=allBusiness();
        assertThat(b.get(APPROVALS+"/"+root).status()).isEqualTo(404);assertThat(b.post(APPROVALS+"/"+root+"/approve",versionBody(1)).status()).isEqualTo(404);
        assertThat(loginAs("erin").get(APPROVALS+"/"+root).status()).isEqualTo(200);assertThat(allBusiness()).isEqualTo(before);
    }
    @Test void adminApprovalEntryDoesNotInheritManagementScope() {
        var a=loginAs("alice");var c=loginAs("carol");
        assertThat(putSetting(loginAs("dave"),alice.getId(),carol.getId(),"R1指定ADMIN").status()).isEqualTo(200);
        long root=submitOk(a,DAY,"09:00","17:00");approveEvidence(c,root);
        assertThat(putSetting(loginAs("dave"),alice.getId(),erin.getId(),"R1新指派").status()).isEqualTo(200);
        long next=revision(a,root,"CHANGE","2026-10-06","R1_SECRET_ADMIN_FUTURE");approveEvidence(loginAs("erin"),next);String before=allBusiness();
        var old=c.get(APPROVALS+"/"+root);assertThat(old.status()).isEqualTo(200);assertThat(old.body()).doesNotContain("R1_SECRET_ADMIN_FUTURE").doesNotContain("2026-10-06");
        assertThat(old.json().get("document").get("versions").size()).isEqualTo(1);assertThat(c.get(APPROVALS+"/"+next).status()).isEqualTo(404);
        var full=c.get(ADMIN_REQUESTS+"/"+root);assertThat(full.status()).isEqualTo(200);assertThat(full.body()).contains("R1_SECRET_ADMIN_FUTURE");
        assertThat(full.json().get("document").get("versions").size()).isEqualTo(2);assertThat(allBusiness()).isEqualTo(before);
    }
}
