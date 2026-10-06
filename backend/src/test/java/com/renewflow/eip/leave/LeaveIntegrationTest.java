package com.renewflow.eip.leave;

import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.leave.LeaveDtos.ApproveRequest;
import com.renewflow.eip.leave.LeaveDtos.SubmitLeaveRequest;
import com.renewflow.eip.leave.LeaveEnums.LeaveType;
import com.renewflow.eip.shared.web.ApiException;
import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T05 請假：真的 Tomcat＋真的 PostgreSQL 測試庫＋完整 Security。 */
class LeaveIntegrationTest extends LeaveTestBase {

    @Autowired
    LeaveRequestService leaveRequestService;

    // ================================================================ 正常流程

    @Test
    @DisplayName("L-01 送出 → 直屬主管核准 → 員工看到結果；分鐘數由後端計算；歷程有送出與核准")
    void submitAndApprove() {
        TestBrowser aliceB = loginAs("alice");
        Reply created = aliceB.post(ME, leaveBody(UUID.randomUUID(), "SICK", DAY, "09:00", "12:30", "  身體不適  "));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        JsonNode r = created.json().get("request");
        assertThat(created.json().get("replayed").asBoolean()).isFalse();
        assertThat(r.get("status").asString()).isEqualTo("PENDING");
        assertThat(r.get("minutes").asInt()).isEqualTo(210);
        assertThat(r.get("reason").asString()).isEqualTo("身體不適");
        assertThat(r.get("startTime").asString()).isEqualTo("09:00");
        assertThat(r.get("approver").get("employeeNo").asString()).isEqualTo("T0002");
        assertThat(r.get("approverSource").asString()).isEqualTo("MANAGER");
        long id = r.get("id").asLong();

        TestBrowser bobB = loginAs("bob");
        JsonNode pending = bobB.get(APPROVALS + "?status=PENDING").json();
        assertThat(pending.get("total").asLong()).isEqualTo(1);
        Reply approved = approve(bobB, id);
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(approved.json().get("request").get("decidedBy").get("displayName").asString()).isEqualTo("鮑伯");

        JsonNode mine = aliceB.get(ME + "/" + id).json();
        assertThat(mine.get("request").get("status").asString()).isEqualTo("APPROVED");
        assertThat(mine.get("request").get("version").asLong()).isEqualTo(1);
        List<String> actions = mine.get("history").valueStream().map(h -> h.get("action").asString()).toList();
        assertThat(actions).containsExactly("SUBMITTED", "APPROVED");
        assertThat(mine.get("history").get(1).get("actor").get("displayName").asString()).isEqualTo("鮑伯");
    }

    @Test
    @DisplayName("L-02 退回需原因、員工看到退回原因；撤回待審；終態不能再轉移（核准已撤回、撤回已核准、退回已退回）")
    void rejectWithdrawAndFinalStates() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser bobB = loginAs("bob");
        long a = submitOk(aliceB, DAY, "09:00", "10:00");
        long b = submitOk(aliceB, DAY, "10:00", "11:00");
        long c = submitOk(aliceB, DAY, "11:00", "12:00");

        assertThat(reject(bobB, a, "   ").code()).isEqualTo("REASON_REQUIRED");
        assertThat(status(a)).isEqualTo("PENDING");
        Reply rejected = reject(bobB, a, "當天有客戶會議");
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(aliceB.get(ME + "/" + a).json().get("request").get("decisionReason").asString()).isEqualTo("當天有客戶會議");

        assertThat(withdraw(aliceB, b).status()).isEqualTo(200);
        assertThat(status(b)).isEqualTo("WITHDRAWN");
        assertThat(approve(bobB, c).status()).isEqualTo(200);

        String before = snapshot();
        Reply approveWithdrawn = approve(bobB, b);
        assertThat(approveWithdrawn.status()).isEqualTo(409);
        assertThat(approveWithdrawn.code()).isEqualTo("LEAVE_NOT_PENDING");
        assertThat(approveWithdrawn.json().get("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(withdraw(aliceB, c).code()).as("已核准不能撤回").isEqualTo("LEAVE_NOT_PENDING");
        assertThat(reject(bobB, a, "再退一次").code()).isEqualTo("LEAVE_NOT_PENDING");
        assertThat(snapshot()).as("被拒絕的轉移沒有改動任何資料或歷程").isEqualTo(before);
    }

    // ================================================================ 輸入規則

    @Test
    @DisplayName("L-03 輸入：半小時刻度、結束晚於開始、不跨午夜、原因 1～500 字、日期 ±365 天、假別；前端送的分鐘數與狀態被忽略")
    void inputRules() {
        TestBrowser aliceB = loginAs("alice");
        assertThat(submit(aliceB, DAY, "09:15", "10:00").code()).isEqualTo("INVALID_LEAVE");
        assertThat(submit(aliceB, DAY, "09:00", "09:00").code()).isEqualTo("INVALID_LEAVE");
        assertThat(submit(aliceB, DAY, "22:00", "01:00").code()).as("跨午夜").isEqualTo("INVALID_LEAVE");
        assertThat(submit(aliceB, DAY, "09:00:30", "10:00").status()).isEqualTo(400);
        assertThat(aliceB.post(ME, leaveBody(UUID.randomUUID(), "PERSONAL", DAY, "09:00", "10:00", "   ")).code()).isEqualTo("REASON_REQUIRED");
        assertThat(aliceB.post(ME, leaveBody(UUID.randomUUID(), "PERSONAL", DAY, "09:00", "10:00", "長".repeat(501))).code()).isEqualTo("INVALID_LEAVE");
        assertThat(aliceB.post(ME, leaveBody(UUID.randomUUID(), "HOLIDAY", DAY, "09:00", "10:00", "x")).status()).isEqualTo(400);
        assertThat(aliceB.post(ME, "{\"leaveType\":\"SICK\",\"workDate\":\"2026-10-05\",\"startTime\":\"09:00\",\"endTime\":\"10:00\",\"reason\":\"x\"}").code())
                .as("缺 requestId").isEqualTo("VALIDATION_FAILED");
        assertThat(submit(aliceB, "2027-10-02", "09:00", "10:00").code()).as("超過一年後").isEqualTo("INVALID_LEAVE");
        assertThat(submit(aliceB, "2025-09-30", "09:00", "10:00").code()).as("超過一年前").isEqualTo("INVALID_LEAVE");
        assertThat(leaveRows()).isZero();

        assertThat(submitOk(aliceB, "2025-10-01", "09:00", "10:00")).as("剛好一年前（補申請）").isPositive();
        assertThat(submitOk(aliceB, "2026-09-20", "09:00", "10:00")).as("過去日期").isPositive();
        Reply sneaky = aliceB.post(ME, "{\"requestId\":\"" + UUID.randomUUID() + "\",\"leaveType\":\"ANNUAL\",\"workDate\":\"2026-11-02\","
                + "\"startTime\":\"13:30\",\"endTime\":\"23:30\",\"reason\":\"特休\",\"minutes\":1,\"status\":\"APPROVED\",\"employeeId\":" + bob.getId() + "}");
        assertThat(sneaky.status()).as(sneaky.body()).isEqualTo(201);
        JsonNode r = sneaky.json().get("request");
        assertThat(r.get("minutes").asInt()).isEqualTo(600);
        assertThat(r.get("status").asString()).isEqualTo("PENDING");
        assertThat(r.get("applicant").get("employeeNo").asString()).isEqualTo("T0001");
    }

    // ================================================================ 重疊

    @Test
    @DisplayName("L-04 重疊：相鄰可以；重疊拒絕且不改任何資料；退回／撤回後釋放時段；不同日期、不同員工互不影響")
    void overlapRules() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser bobB = loginAs("bob");
        long first = submitOk(aliceB, DAY, "09:00", "12:00");
        assertThat(submitOk(aliceB, DAY, "12:00", "13:00")).as("12:00 結束、12:00 開始").isPositive();
        String before = snapshot();
        for (String[] t : new String[][]{{"11:30", "12:30"}, {"08:00", "09:30"}, {"10:00", "11:00"}, {"08:00", "14:00"}}) {
            Reply r = submit(aliceB, DAY, t[0], t[1]);
            assertThat(r.status()).as("%s-%s", t[0], t[1]).isEqualTo(409);
            assertThat(r.code()).isEqualTo("LEAVE_OVERLAP");
        }
        assertThat(snapshot()).isEqualTo(before);

        assertThat(approve(bobB, first).status()).isEqualTo(200);
        assertThat(submit(aliceB, DAY, "10:00", "11:00").code()).as("已核准仍占用").isEqualTo("LEAVE_OVERLAP");
        long second = jdbc.queryForObject("select id from leave_request where start_time = '12:00'", Long.class);
        assertThat(withdraw(aliceB, second).status()).isEqualTo(200);
        assertThat(submitOk(aliceB, DAY, "12:00", "13:00")).as("撤回後釋放").isPositive();
        long third = submitOk(aliceB, DAY, "14:00", "15:00");
        assertThat(reject(bobB, third, "改期").status()).isEqualTo(200);
        assertThat(submitOk(aliceB, DAY, "14:00", "15:00")).as("退回後釋放").isPositive();
        assertThat(submitOk(aliceB, "2026-10-06", "09:00", "12:00")).as("不同日期").isPositive();
        setManager(bob.getId(), carol.getId());
        assertThat(submitOk(loginAs("bob"), DAY, "09:00", "12:00")).as("不同員工").isPositive();
    }

    // ================================================================ 資料範圍與自審

    @Test
    @DisplayName("L-05 資料範圍：他人申請 404；非指派主管不能讀或核准（404）；EMPLOYEE 沒有審核清單（403）；ADMIN 不是審核人也不能核准")
    void dataScope() {
        TestBrowser aliceB = loginAs("alice");
        long id = submitOk(aliceB, DAY, "09:00", "10:00");
        String before = snapshot();

        TestBrowser erinB = loginAs("erin");
        assertThat(erinB.get(ME + "/" + id).status()).as("不是本人").isEqualTo(404);
        assertThat(erinB.get(APPROVALS + "/" + id).code()).as("不是指派給 erin").isEqualTo("LEAVE_NOT_FOUND");
        assertThat(erinB.post(APPROVALS + "/" + id + "/approve", versionBody(0)).status()).isEqualTo(404);
        assertThat(erinB.get(APPROVALS).json().get("total").asLong()).isZero();
        assertThat(erinB.get(APPROVALS + "/999999").code()).as("不存在與無權限相同").isEqualTo("LEAVE_NOT_FOUND");

        TestBrowser carolB = loginAs("carol");
        assertThat(carolB.post(APPROVALS + "/" + id + "/approve", versionBody(0)).status()).as("ADMIN 不是審核人").isEqualTo(404);
        assertThat(carolB.post(APPROVALS + "/" + id + "/reject", versionReason(0, "x")).status()).isEqualTo(404);
        assertThat(carolB.get(ADMIN_REQUESTS + "/" + id).status()).as("ADMIN 可以查看").isEqualTo(200);

        TestBrowser aliceAgain = loginAs("alice");
        assertThat(aliceAgain.get(APPROVALS).status()).as("EMPLOYEE 沒有審核清單").isEqualTo(403);
        assertThat(aliceAgain.get(ADMIN_REQUESTS).status()).isEqualTo(403);
        assertThat(loginAs("bob").get(ADMIN_REQUESTS).status()).as("MANAGER 沒有管理 API").isEqualTo(403);
        assertThat(loginAs("bob").get("/api/admin/employees").status()).as("審核人不因此取得員工管理權限").isEqualTo(403);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("L-06 禁止自審：指定自己為審核人 400；改派給申請人 400；ADMIN 不能改派自己的申請（403）；資料庫 CHECK 最後一道")
    void noSelfApproval() {
        TestBrowser carolB = loginAs("carol");
        assertThat(putSetting(carolB, carol.getId(), carol.getId(), "自己").code()).isEqualTo("APPROVER_IS_APPLICANT");
        assertThat(putSetting(carolB, carol.getId(), dave.getId(), "Carol 由 Dave 審核").status()).isEqualTo(200);
        long carolLeave = submitOk(carolB, DAY, "09:00", "10:00");
        assertThat(approverOf(carolLeave)).isEqualTo(dave.getId());

        Reply selfReassign = reassign(carolB, carolLeave, erin.getId(), "自己改派");
        assertThat(selfReassign.status()).isEqualTo(403);
        assertThat(selfReassign.code()).isEqualTo("SELF_REASSIGN_FORBIDDEN");
        TestBrowser daveB = loginAs("dave");
        assertThat(reassign(daveB, carolLeave, carol.getId(), "給申請人").code()).isEqualTo("APPROVER_IS_APPLICANT");
        assertThat(reassign(daveB, carolLeave, erin.getId(), "Dave 休假").status()).as("另一位 ADMIN 可以改派").isEqualTo(200);
        assertThat(approverOf(carolLeave)).isEqualTo(erin.getId());

        assertThatThrownBy(() -> jdbc.update("update leave_request set approver_id = employee_id where id = ?", carolLeave))
                .isInstanceOf(DataAccessException.class);
    }

    // ================================================================ 審核人決定

    @Test
    @DisplayName("L-07 指定優先、移除後採直屬主管（只影響之後）、都沒有 → NO_VALID_APPROVER；指定審核人無效 → 拒絕（不自動改用主管）")
    void approverResolution() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        assertThat(putSetting(carolB, alice.getId(), erin.getId(), "暫由 Erin 審核").status()).isEqualTo(200);
        JsonNode preview = aliceB.get(ME + "/approver-preview").json();
        assertThat(preview.get("source").asString()).isEqualTo("DESIGNATED");
        assertThat(preview.get("approver").get("employeeNo").asString()).isEqualTo("T0005");
        long designated = submitOk(aliceB, DAY, "09:00", "10:00");
        assertThat(approverOf(designated)).isEqualTo(erin.getId());

        assertThat(putSetting(carolB, alice.getId(), null, "恢復主管審核").status()).isEqualTo(200);
        long fallback = submitOk(aliceB, DAY, "10:00", "11:00");
        assertThat(approverOf(fallback)).isEqualTo(bob.getId());
        assertThat(approverOf(designated)).as("既有申請不改").isEqualTo(erin.getId());

        // carol 沒有主管、沒有指定
        Reply none = submit(carolB, DAY, "09:00", "10:00");
        assertThat(none.status()).isEqualTo(409);
        assertThat(none.code()).isEqualTo("NO_VALID_APPROVER");
        assertThat(carolB.get(ME + "/approver-preview").json().get("source").asString()).isEqualTo("NONE");

        // 指定審核人之後被停用 → 拒絕送出（不偷偷改用 bob）
        assertThat(putSetting(carolB, alice.getId(), erin.getId(), "再指定 Erin").status()).isEqualTo(200);
        jdbc.update("update employee set enabled = false where id = ?", erin.getId());
        Reply invalid = submit(aliceB, DAY, "13:00", "14:00");
        assertThat(invalid.code()).isEqualTo("NO_VALID_APPROVER");
        assertThat(invalid.body()).contains("指定的請假審核人目前無效");
        assertThat(leaveRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("L-08 指定請假審核人不修改主管關係：carol（ADMIN、無主管）→ 指定 erin，erin 的主管是 carol，組織沒有循環")
    void designatedApproverDoesNotTouchManager() {
        setManager(erin.getId(), carol.getId());
        TestBrowser carolB = loginAs("carol");
        assertThat(putSetting(carolB, carol.getId(), erin.getId(), "Carol 沒有主管，由 Erin 審核").status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select manager_id from employee where id = ?", Long.class, carol.getId())).as("carol 仍沒有主管").isNull();
        assertThat(jdbc.queryForObject("select manager_id from employee where id = ?", Long.class, erin.getId())).isEqualTo(carol.getId());
        long id = submitOk(carolB, DAY, "09:00", "10:00");
        assertThat(approve(loginAs("erin"), id).status()).isEqualTo(200);
        JsonNode setting = carolB.get(SETTINGS + "/" + carol.getId()).json();
        assertThat(setting.get("manager").isNull()).isTrue();
        assertThat(setting.get("designatedApprover").get("employeeNo").asString()).isEqualTo("T0005");
        assertThat(setting.get("effective").get("source").asString()).isEqualTo("DESIGNATED");
        assertThat(setting.get("version").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("L-09 送出後改主管或改指定設定：既有待審申請不偷偷改派，仍由原審核人處理")
    void snapshotIsStable() {
        TestBrowser aliceB = loginAs("alice");
        long id = submitOk(aliceB, DAY, "09:00", "10:00");
        setManager(alice.getId(), erin.getId());
        assertThat(putSetting(loginAs("carol"), alice.getId(), dave.getId(), "之後由 Dave").status()).isEqualTo(200);
        assertThat(approverOf(id)).isEqualTo(bob.getId());
        assertThat(loginAs("erin").get(APPROVALS + "/" + id).status()).isEqualTo(404);
        assertThat(approve(loginAs("bob"), id).status()).isEqualTo(200);
    }

    // ================================================================ 改派

    @Test
    @DisplayName("L-10 改派：原審核人失去讀取與處理權（404）、新審核人可處理；原因必填；版本＋1；歷程記錄前後；已結案不能改派")
    void reassignment() {
        TestBrowser aliceB = loginAs("alice");
        long id = submitOk(aliceB, DAY, "09:00", "10:00");
        TestBrowser carolB = loginAs("carol");
        assertThat(reassign(carolB, id, erin.getId(), " ").code()).isEqualTo("REASON_REQUIRED");
        assertThat(reassign(carolB, id, bob.getId(), "同一人").code()).isEqualTo("NO_CHANGE");
        assertThat(reassign(carolB, id, alice.getId(), "申請人").code()).isEqualTo("APPROVER_IS_APPLICANT");
        jdbc.update("update employee set role = 'EMPLOYEE' where id = ?", dave.getId());
        assertThat(reassign(carolB, id, dave.getId(), "員工").code()).isEqualTo("APPROVER_INVALID");
        assertThat(reassign(carolB, id, 999999L, "不存在").code()).isEqualTo("EMPLOYEE_NOT_FOUND");
        Reply stale = carolB.post(ADMIN_REQUESTS + "/" + id + "/reassign", json("approverId", String.valueOf(erin.getId()), "expectedVersion", "5", "reason", str("x")));
        assertThat(stale.code()).isEqualTo("LEAVE_CHANGED");
        assertThat(version(id)).isZero();

        Reply ok = reassign(carolB, id, erin.getId(), "Bob 休假中");
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(ok.json().get("request").get("version").asLong()).isEqualTo(1);
        assertThat(ok.json().get("request").get("approverSource").asString()).isEqualTo("REASSIGNED");
        assertThat(ok.json().get("request").get("status").asString()).isEqualTo("PENDING");
        JsonNode h = ok.json().get("history").get(1);
        assertThat(h.get("action").asString()).isEqualTo("REASSIGNED");
        assertThat(h.get("beforeApprover").get("employeeNo").asString()).isEqualTo("T0002");
        assertThat(h.get("afterApprover").get("employeeNo").asString()).isEqualTo("T0005");
        assertThat(h.get("actor").get("employeeNo").asString()).isEqualTo("T0003");
        assertThat(h.get("reason").asString()).isEqualTo("Bob 休假中");

        TestBrowser bobB = loginAs("bob");
        assertThat(bobB.get(APPROVALS + "/" + id).status()).as("原審核人看不到").isEqualTo(404);
        assertThat(bobB.post(APPROVALS + "/" + id + "/approve", versionBody(1)).status()).isEqualTo(404);
        assertThat(bobB.get(APPROVALS).json().get("total").asLong()).isZero();
        assertThat(approve(loginAs("erin"), id).status()).isEqualTo(200);
        assertThat(reassign(carolB, id, bob.getId(), "已結案").code()).isEqualTo("LEAVE_NOT_PENDING");
    }

    @Test
    @DisplayName("L-11 審核人被降級：舊 Session 401；重新登入為 EMPLOYEE 後沒有審核清單、單筆 404；由 ADMIN 改派後可繼續")
    void demotedApprover() {
        TestBrowser aliceB = loginAs("alice");
        long id = submitOk(aliceB, DAY, "09:00", "10:00");
        TestBrowser bobB = loginAs("bob");
        jdbc.update("update employee set manager_id = null where id = ?", alice.getId());
        jdbc.update("update employee set role = 'EMPLOYEE' where id = ?", bob.getId());
        assertThat(bobB.post(APPROVALS + "/" + id + "/approve", versionBody(0)).status()).isEqualTo(401);
        TestBrowser bobAgain = loginAs("bob");
        assertThat(bobAgain.get(APPROVALS).status()).isEqualTo(403);
        assertThat(bobAgain.post(APPROVALS + "/" + id + "/approve", versionBody(0)).status()).isEqualTo(404);
        assertThat(status(id)).isEqualTo("PENDING");
        JsonNode adminView = loginAs("carol").get(ADMIN_REQUESTS + "/" + id).json().get("request");
        assertThat(adminView.get("approver").get("valid").asBoolean()).as("管理員看得到審核人已無效").isFalse();
        assertThat(reassign(loginAs("carol"), id, erin.getId(), "Bob 已轉任一般員工").status()).isEqualTo(200);
        assertThat(approve(loginAs("erin"), id).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("L-12 取鎖後重新確認操作者：審核人請求通過 Filter 後才被降級 → 401，申請與歷程不變")
    void actorRecheckedAfterLock() {
        long id = submitOk(loginAs("alice"), DAY, "09:00", "10:00");
        EmployeeUserDetails bobSnapshot = new EmployeeUserDetails(bob); // 已通過 Filter 時的快照（MANAGER）
        jdbc.update("update employee set role = 'EMPLOYEE' where id = ?", bob.getId());
        String before = snapshot();
        assertThatThrownBy(() -> leaveRequestService.approve(bobSnapshot, id, new ApproveRequest(0L)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(401);
                    assertThat(ex.getCode()).isEqualTo("UNAUTHENTICATED");
                });
        jdbc.update("update employee set enabled = false where id = ?", alice.getId());
        EmployeeUserDetails aliceSnapshot = new EmployeeUserDetails(alice);
        assertThatThrownBy(() -> leaveRequestService.submit(aliceSnapshot, new SubmitLeaveRequest(UUID.randomUUID(), LeaveType.SICK,
                LocalDate.parse(DAY), LocalTime.of(13, 0), LocalTime.of(14, 0), "x")))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("UNAUTHENTICATED"));
        assertThat(snapshot()).isEqualTo(before);
    }

    // ================================================================ requestId

    @Test
    @DisplayName("L-13 requestId：相同內容重送 → 200 回傳原申請、只有一筆一個歷程；不同內容 409 不改原申請；不同員工各自獨立；撤回後重送不重建；改主管後重送沿用原審核人")
    void requestIdSemantics() {
        TestBrowser aliceB = loginAs("alice");
        UUID rid = UUID.randomUUID();
        String body = leaveBody(rid, "SICK", DAY, "09:00", "10:00", "看診");
        long id = aliceB.post(ME, body).json().get("request").get("id").asLong();

        Reply again = aliceB.post(ME, leaveBody(rid, "SICK", DAY, "09:00", "10:00", "  看診 "));
        assertThat(again.status()).as("正規化後相同").isEqualTo(200);
        assertThat(again.json().get("replayed").asBoolean()).isTrue();
        assertThat(again.json().get("request").get("id").asLong()).isEqualTo(id);
        assertThat(leaveRows()).isEqualTo(1);
        assertThat(leaveHistoryRows()).isEqualTo(1);

        String before = snapshot();
        Reply different = aliceB.post(ME, leaveBody(rid, "SICK", DAY, "09:00", "11:00", "看診"));
        assertThat(different.status()).isEqualTo(409);
        assertThat(different.code()).isEqualTo("REQUEST_ID_CONFLICT");
        assertThat(aliceB.post(ME, leaveBody(rid, "PERSONAL", DAY, "09:00", "10:00", "看診")).code()).isEqualTo("REQUEST_ID_CONFLICT");
        assertThat(snapshot()).isEqualTo(before);

        // 另一位員工用同一個 requestId：各自獨立，看不到 alice 的
        Reply bobSame = loginAs("bob").post(ME, leaveBody(rid, "SICK", DAY, "09:00", "10:00", "看診"));
        assertThat(bobSame.status()).as("bob 沒有主管 → 不是重送，而是新的判斷").isEqualTo(409);
        assertThat(bobSame.code()).isEqualTo("NO_VALID_APPROVER");
        setManager(bob.getId(), carol.getId());
        Reply bobNew = loginAs("bob").post(ME, leaveBody(rid, "SICK", DAY, "09:00", "10:00", "看診"));
        assertThat(bobNew.status()).isEqualTo(201);
        assertThat(bobNew.json().get("request").get("id").asLong()).isNotEqualTo(id);

        // 改主管後重送：沿用原審核人；撤回後重送：回傳已撤回的原申請，不重建
        setManager(alice.getId(), erin.getId());
        assertThat(aliceB.post(ME, body).json().get("request").get("approver").get("employeeNo").asString()).isEqualTo("T0002");
        assertThat(withdraw(aliceB, id).status()).isEqualTo(200);
        Reply afterWithdraw = aliceB.post(ME, body);
        assertThat(afterWithdraw.status()).isEqualTo(200);
        assertThat(afterWithdraw.json().get("request").get("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("select count(*) from leave_request where employee_id = ?", Integer.class, alice.getId())).isEqualTo(1);
    }

    // ================================================================ 版本與歷程

    @Test
    @DisplayName("L-14 過期版本：核准／退回／撤回 → 409 LEAVE_CHANGED，資料與歷程不變")
    void staleVersion() {
        TestBrowser aliceB = loginAs("alice");
        long id = submitOk(aliceB, DAY, "09:00", "10:00");
        assertThat(reassign(loginAs("carol"), id, erin.getId(), "換人").status()).isEqualTo(200);
        assertThat(reassign(loginAs("carol"), id, bob.getId(), "換回").status()).isEqualTo(200);
        String before = snapshot();
        TestBrowser bobB = loginAs("bob");
        assertThat(bobB.post(APPROVALS + "/" + id + "/approve", versionBody(0)).code()).isEqualTo("LEAVE_CHANGED");
        assertThat(bobB.post(APPROVALS + "/" + id + "/reject", versionReason(1, "x")).code()).isEqualTo("LEAVE_CHANGED");
        assertThat(aliceB.post(ME + "/" + id + "/withdraw", versionReason(0, null)).code()).isEqualTo("LEAVE_CHANGED");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(approve(bobB, id).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("L-15 歷程只新增：UPDATE／DELETE 被資料庫拒絕；資料庫 CHECK 擋不合法時段與狀態")
    void historyAppendOnlyAndChecks() {
        long id = submitOk(loginAs("alice"), DAY, "09:00", "10:00");
        assertThatThrownBy(() -> jdbc.update("update leave_history set reason = 'x'")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from leave_history")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update leave_request set start_time = '09:15' where id = ?", id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update leave_request set minutes = 30 where id = ?", id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update leave_request set status = 'APPROVED' where id = ?", id))
                .as("結案必須有決定者").isInstanceOf(DataAccessException.class);
        assertThat(leaveHistoryRows()).isEqualTo(1);
    }

    // ================================================================ 指定設定與清單

    @Test
    @DisplayName("L-16 指定審核人設定：原因必填、版本衝突 409、無效審核人 409、相同 400、歷程有前後值；只有 ADMIN")
    void approverSettings() {
        TestBrowser carolB = loginAs("carol");
        assertThat(putSetting(carolB, alice.getId(), erin.getId(), " ").code()).isEqualTo("REASON_REQUIRED");
        assertThat(putSetting(carolB, alice.getId(), null, "沒有設定時移除").code()).isEqualTo("NO_CHANGE");
        jdbc.update("update employee set enabled = false where id = ?", dave.getId());
        assertThat(putSetting(carolB, alice.getId(), dave.getId(), "停用者").code()).isEqualTo("APPROVER_INVALID");
        assertThat(putSetting(carolB, 999999L, erin.getId(), "不存在").code()).isEqualTo("EMPLOYEE_NOT_FOUND");
        assertThat(putSetting(carolB, alice.getId(), erin.getId(), "第一次").status()).isEqualTo(200);
        Reply stale = carolB.put(SETTINGS + "/" + alice.getId(), json("approverId", String.valueOf(bob.getId()), "expectedVersion", "0", "reason", str("過期")));
        assertThat(stale.code()).isEqualTo("APPROVER_SETTING_CHANGED");
        assertThat(putSetting(carolB, alice.getId(), erin.getId(), "相同").code()).isEqualTo("NO_CHANGE");
        assertThat(putSetting(carolB, alice.getId(), null, "移除").status()).isEqualTo(200);
        JsonNode history = carolB.get(SETTINGS + "/" + alice.getId() + "/history").json().get("history");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("beforeApprover").get("employeeNo").asString()).isEqualTo("T0005");
        assertThat(history.get(0).get("afterApprover").isNull()).isTrue();
        assertThat(history.get(1).get("reason").asString()).isEqualTo("第一次");
        assertThat(carolB.get(SETTINGS).json().get("settings")).as("移除後不在已指定清單").isEmpty();
        assertThat(loginAs("bob").put(SETTINGS + "/" + alice.getId(), json("approverId", "null", "expectedVersion", "2", "reason", str("x"))).status())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("L-17 清單：狀態篩選、日期範圍（同時提供、最多 366 天）、分頁上限 50、依日期新到舊；ADMIN 可依員工篩選")
    void listing() {
        TestBrowser aliceB = loginAs("alice");
        for (int day = 1; day <= 25; day++) {
            submitOk(aliceB, "2026-10-%02d".formatted(day), "09:00", "10:00");
        }
        long rejected = jdbc.queryForObject("select id from leave_request where work_date = '2026-10-03'", Long.class);
        assertThat(reject(loginAs("bob"), rejected, "不行").status()).isEqualTo(200);

        JsonNode page = aliceB.get(ME).json();
        assertThat(page.get("total").asLong()).isEqualTo(25);
        assertThat(page.get("items")).hasSize(20);
        assertThat(page.get("items").get(0).get("workDate").asString()).isEqualTo("2026-10-25");
        assertThat(aliceB.get(ME + "?size=500&page=0").json().get("size").asInt()).isEqualTo(50);
        assertThat(aliceB.get(ME + "?status=REJECTED").json().get("total").asLong()).isEqualTo(1);
        assertThat(aliceB.get(ME + "?from=2026-10-01&to=2026-10-10").json().get("total").asLong()).isEqualTo(10);
        assertThat(aliceB.get(ME + "?from=2026-10-01").code()).isEqualTo("INVALID_DATE_RANGE");
        assertThat(aliceB.get(ME + "?from=2026-10-10&to=2026-10-01").code()).isEqualTo("INVALID_DATE_RANGE");
        assertThat(aliceB.get(ME + "?from=2025-10-01&to=2026-10-02").code()).isEqualTo("INVALID_DATE_RANGE");
        assertThat(aliceB.get(ME + "?status=NOPE").status()).isEqualTo(400);
        assertThat(loginAs("bob").get(APPROVALS + "?status=PENDING").json().get("total").asLong()).isEqualTo(24);

        setManager(erin.getId(), bob.getId());
        submitOk(loginAs("erin"), DAY, "09:00", "10:00");
        TestBrowser carolB = loginAs("carol");
        assertThat(carolB.get(ADMIN_REQUESTS).json().get("total").asLong()).isEqualTo(26);
        JsonNode erinOnly = carolB.get(ADMIN_REQUESTS + "?employeeId=" + erin.getId()).json();
        assertThat(erinOnly.get("total").asLong()).isEqualTo(1);
        assertThat(erinOnly.get("items").get(0).get("applicant").get("employeeNo").asString()).isEqualTo("T0005");
    }
}
