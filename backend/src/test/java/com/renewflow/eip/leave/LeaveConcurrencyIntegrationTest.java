package com.renewflow.eip.leave;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 請假的並行測試：以閘門同時放行多個真實 HTTP 請求，對真的 PostgreSQL 競爭，重複多輪。
 * 這些情境多半是「不同列」或「先查再寫」：只有組織鎖能讓後到者看到先到者的結果。
 */
class LeaveConcurrencyIntegrationTest extends LeaveTestBase {

    private static final int ROUNDS = 8;

    private int overlappingPairs() {
        return jdbc.queryForObject("""
                select count(*) from leave_request a join leave_request b
                  on a.employee_id = b.employee_id and a.work_date = b.work_date and a.id < b.id
                 and a.status in ('PENDING', 'APPROVED') and b.status in ('PENDING', 'APPROVED')
                 and a.start_time < b.end_time and a.end_time > b.start_time
                """, Integer.class);
    }

    private static long count(List<Reply> replies, int status) {
        return replies.stream().filter(r -> r.status() == status).count();
    }

    @Test
    @DisplayName("LC-01 同時送出 3 筆互相重疊的申請（不同 requestId）：每輪恰好一筆成功，其餘 409 LEAVE_OVERLAP；資料庫沒有重疊")
    void concurrentOverlappingSubmits() throws Exception {
        TestBrowser aliceB = loginAs("alice");
        for (int round = 0; round < ROUNDS; round++) {
            String date = "2026-10-%02d".formatted(round + 2);
            List<Reply> replies = runSimultaneously(List.<Callable<Reply>>of(
                    () -> submit(aliceB, date, "09:00", "12:00"),
                    () -> submit(aliceB, date, "10:00", "11:30"),
                    () -> submit(aliceB, date, "11:00", "13:00")));
            assertThat(count(replies, 201)).as("round %d: %s", round, replies).isEqualTo(1);
            assertThat(replies.stream().filter(r -> r.status() != 201).allMatch(r -> "LEAVE_OVERLAP".equals(r.code()))).isTrue();
            assertThat(overlappingPairs()).as("round %d", round).isZero();
        }
        assertThat(leaveHistoryRows()).isEqualTo(ROUNDS);
    }

    @Test
    @DisplayName("LC-02 同一個 requestId 同時送出 3 次：只有一筆、一個歷程；一個 201、其餘 200 replayed")
    void concurrentDuplicateSubmits() throws Exception {
        TestBrowser aliceB = loginAs("alice");
        for (int round = 0; round < ROUNDS; round++) {
            String body = leaveBody(UUID.randomUUID(), "SICK", "2026-10-%02d".formatted(round + 2), "09:00", "10:00", "看診");
            List<Reply> replies = runSimultaneously(List.<Callable<Reply>>of(
                    () -> aliceB.post(ME, body), () -> aliceB.post(ME, body), () -> aliceB.post(ME, body)));
            assertThat(count(replies, 201)).as("round %d: %s", round, replies).isEqualTo(1);
            assertThat(count(replies, 200)).isEqualTo(2);
            assertThat(replies.stream().map(r -> r.json().get("request").get("id").asLong()).distinct().count()).isEqualTo(1);
        }
        assertThat(leaveRows()).isEqualTo(ROUNDS);
        assertThat(leaveHistoryRows()).isEqualTo(ROUNDS);
    }

    @Test
    @DisplayName("LC-03 同時核准與撤回：每輪恰好一個成功，另一個 409 LEAVE_NOT_PENDING；最終狀態與成功者一致、只有一筆結案歷程")
    void concurrentApproveVsWithdraw() throws Exception {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser bobB = loginAs("bob");
        for (int round = 0; round < ROUNDS; round++) {
            long id = submitOk(aliceB, "2026-10-%02d".formatted(round + 2), "09:00", "10:00");
            List<Reply> replies = runSimultaneously(List.<Callable<Reply>>of(
                    () -> bobB.post(APPROVALS + "/" + id + "/approve", versionBody(0)),
                    () -> aliceB.post(ME + "/" + id + "/withdraw", versionReason(0, null))));
            assertThat(count(replies, 200)).as("round %d: %s", round, replies).isEqualTo(1);
            Reply loser = replies.stream().filter(r -> r.status() != 200).findFirst().orElseThrow();
            assertThat(loser.code()).isIn("LEAVE_NOT_PENDING", "LEAVE_CHANGED");
            String expected = replies.get(0).status() == 200 ? "APPROVED" : "WITHDRAWN";
            assertThat(status(id)).isEqualTo(expected);
            assertThat(jdbc.queryForObject("select count(*) from leave_history where leave_request_id = ?", Integer.class, id)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("LC-04 同時核准與改派：先改派 → 原審核人的核准 404；先核准 → 改派 409；兩者不會都成功")
    void concurrentApproveVsReassign() throws Exception {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser bobB = loginAs("bob");
        TestBrowser carolB = loginAs("carol");
        for (int round = 0; round < ROUNDS; round++) {
            long id = submitOk(aliceB, "2026-10-%02d".formatted(round + 2), "09:00", "10:00");
            String reassignBody = json("approverId", String.valueOf(erin.getId()), "expectedVersion", "0", "reason", str("並行改派"));
            List<Reply> replies = runSimultaneously(List.<Callable<Reply>>of(
                    () -> bobB.post(APPROVALS + "/" + id + "/approve", versionBody(0)),
                    () -> carolB.post(ADMIN_REQUESTS + "/" + id + "/reassign", reassignBody)));
            assertThat(count(replies, 200)).as("round %d: %s", round, replies).isEqualTo(1);
            if (replies.get(0).status() == 200) {
                assertThat(status(id)).isEqualTo("APPROVED");
                assertThat(approverOf(id)).isEqualTo(bob.getId());
                assertThat(replies.get(1).code()).isIn("LEAVE_NOT_PENDING", "LEAVE_CHANGED");
            } else {
                assertThat(status(id)).isEqualTo("PENDING");
                assertThat(approverOf(id)).isEqualTo(erin.getId());
                assertThat(replies.get(0).status()).isEqualTo(404);
            }
        }
    }

    @Test
    @DisplayName("LC-05 同時「審核人被降級（組織 API）」與「審核人核准」：要嘛先核准再降級，要嘛降級後核准得到 401；不會由已降級者核准")
    void concurrentDemoteVsApprove() throws Exception {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("update employee set role = 'MANAGER', enabled = true where id = ?", erin.getId());
            setManager(alice.getId(), erin.getId());
            long id = submitOk(aliceB, "2026-10-%02d".formatted(round + 2), "09:00", "10:00");
            setManager(alice.getId(), null); // 讓 erin 可以被降級（沒有部屬）
            TestBrowser erinB = loginAs("erin");
            long erinVersion = jdbc.queryForObject("select version from employee where id = ?", Long.class, erin.getId());
            String demote = json("displayName", str("艾琳"), "role", str("EMPLOYEE"), "enabled", "true", "departmentId", "null",
                    "managerId", "null", "expectedVersion", String.valueOf(erinVersion), "reason", str("轉任"));
            List<Reply> replies = runSimultaneously(List.<Callable<Reply>>of(
                    () -> erinB.post(APPROVALS + "/" + id + "/approve", versionBody(0)),
                    () -> carolB.put("/api/admin/employees/" + erin.getId(), demote)));
            assertThat(replies.get(1).status()).as("round %d: %s", round, replies).isEqualTo(200);
            if (replies.get(0).status() == 200) {
                // 核准先取得鎖：當時 erin 仍是 MANAGER，之後才被降級
                assertThat(status(id)).isEqualTo("APPROVED");
                assertThat(jdbc.queryForObject("select decided_by from leave_request where id = ?", Long.class, id)).isEqualTo(erin.getId());
            } else {
                assertThat(replies.get(0).status()).isEqualTo(401);
                assertThat(status(id)).isEqualTo("PENDING");
            }
        }
    }
}
