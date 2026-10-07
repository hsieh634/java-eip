package com.renewflow.eip.attendance;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T03.1 的並行情境：多個請求用閘門同時放行，直接對真實 PostgreSQL 競爭。
 * 驗證的是「不變條件」——不論誰先到，都不會有兩筆有效紀錄、不會覆蓋較新的資料、同一申請只被處理一次。
 */
class AttendanceCorrectionConcurrencyIntegrationTest extends CorrectionsTestBase {

    private static <T> List<T> runSimultaneously(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<TestBrowser> browsers(String username, int count) {
        List<TestBrowser> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            TestBrowser browser = loginAs(username);
            browser.get(TODAY); // 預先建立連線，減少起跑落差
            list.add(browser);
        }
        return list;
    }

    @Test
    @DisplayName("C-03 8 個並行提出申請（同一日期）→ 恰 1 個 201、其餘 409 DUPLICATE_PENDING_REQUEST；資料庫只有一筆待審")
    void concurrentSubmitsCreateOnePendingRequest() throws Exception {
        TestBrowser first = loginAs("alice");
        punch(first, CLOCK_IN, "2026-10-01T01:00:00Z");
        List<TestBrowser> aliceBrowsers = browsers("alice", 8);
        List<Callable<Reply>> tasks = new ArrayList<>();
        for (TestBrowser b : aliceBrowsers) {
            tasks.add(() -> submitReset(b, "並行提出"));
        }

        List<Reply> replies = runSimultaneously(tasks);

        assertThat(replies.stream().filter(r -> r.status() == 201).count()).isEqualTo(1);
        assertThat(replies.stream().filter(r -> r.status() == 409 && r.code().equals("DUPLICATE_PENDING_REQUEST")).count()).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from attendance_request where status = 'PENDING'", Integer.class)).isEqualTo(1);
        assertThat(historyRows()).as("只有成功的那一筆寫了歷程").isEqualTo(1);
    }

    @Test
    @DisplayName("C-15 8 個並行核准同一筆申請 → 恰 1 個 200、其餘 409 REQUEST_NOT_PENDING；只作廢一次、歷程只有一筆核准")
    void concurrentApprovalsApplyOnce() throws Exception {
        TestBrowser aliceB = loginAs("alice");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        long id = idOf(submitReset(aliceB, "並行核准"));
        List<TestBrowser> admins = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            admins.add(loginAs("carol"));
            admins.add(loginAs("dave"));
        }
        List<Callable<Reply>> tasks = new ArrayList<>();
        for (TestBrowser admin : admins) {
            tasks.add(() -> approve(admin, id));
        }

        List<Reply> replies = runSimultaneously(tasks);

        assertThat(replies.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
        assertThat(replies.stream().filter(r -> r.status() == 409 && r.code().equals("REQUEST_NOT_PENDING")).count()).isEqualTo(7);
        assertThat(requestStatus(id)).isEqualTo("APPROVED");
        assertThat(voidedRows(alice)).isEqualTo(1);
        assertThat(activeRows(alice)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from attendance_history where operation = 'REQUEST_APPROVED'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("C-15 核准重新打卡 與 員工下班 並行（6 輪）：恰好是「核准成功＋下班被拒」或「核准過時＋下班成功」其一，絕不會兩者都成功或都失敗")
    void approveResetRacesWithClockOut() throws Exception {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        for (int round = 1; round <= 6; round++) {
            String day = "2026-10-0" + round;
            clock.set(utc(day + "T01:00:00Z"));
            assertThat(aliceB.post(CLOCK_IN, null).status()).isEqualTo(200);
            long id = idOf(submitReset(aliceB, "競爭 " + round));
            clock.setAutoAdvance(utc(day + "T05:00:00Z"), Duration.ofSeconds(1));

            List<Reply> replies = runSimultaneously(List.of(
                    () -> approve(carolB, id),
                    () -> aliceB.post(CLOCK_OUT, null)));
            Reply approval = replies.get(0);
            Reply clockOut = replies.get(1);

            boolean approvedFirst = approval.status() == 200 && clockOut.status() == 409 && clockOut.code().equals("CLOCK_IN_REQUIRED");
            boolean clockedOutFirst = approval.status() == 409 && approval.code().equals("REQUEST_STALE") && clockOut.status() == 200;
            assertThat(approvedFirst || clockedOutFirst)
                    .as("round %d：approve=%d %s, clockOut=%d %s", round, approval.status(), approval.body(), clockOut.status(), clockOut.body())
                    .isTrue();
            // 不變條件：該日最多一筆有效紀錄；核准成功時沒有有效紀錄、否則有效紀錄必有下班時間
            int active = jdbc.queryForObject("select count(*) from attendance where employee_id = ? and work_date = ?::date and voided_at is null", Integer.class, alice.getId(), day);
            if (approvedFirst) {
                assertThat(active).isZero();
                assertThat(requestStatus(id)).isEqualTo("APPROVED");
            } else {
                assertThat(active).isEqualTo(1);
                assertThat(activeColumn(alice, day, "clock_out_at")).isNotNull().isNotEqualTo("<none>");
                assertThat(requestStatus(id)).isEqualTo("PENDING");
                assertThat(aliceB.post(REQUESTS + "/" + id + "/cancel", null).status()).isEqualTo(200); // 清掉待審，下一輪才能再提出
            }
        }
    }

    @Test
    @DisplayName("C-15 核准更正時間與員工下班並行（6 輪）：下班先成功則申請過時；核准先成功則原下班版本過時，重查後可下班")
    void approveCorrectionRacesWithClockOut() throws Exception {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        for (int round = 1; round <= 6; round++) {
            String day = "2026-10-0" + round;
            clock.set(utc(day + "T01:00:00Z"));
            assertThat(aliceB.post(CLOCK_IN, null).status()).isEqualTo(200);
            long id = idOf(submitCorrection(aliceB, day, "08:30:00", null, "競爭 " + round)); // 希望上班 00:30Z、無下班
            clock.setAutoAdvance(utc(day + "T05:00:00Z"), Duration.ofSeconds(1));

            List<Reply> replies = runSimultaneously(List.of(
                    () -> approve(carolB, id),
                    () -> aliceB.post(CLOCK_OUT, null)));
            Reply approval = replies.get(0);
            Reply clockOut = replies.get(1);

            // 舊下班快照若在核准後才套用，必須拒絕，不能覆蓋已更正版本。
            assertThat(clockOut.status() == 200 || (clockOut.status() == 409 && clockOut.code().equals("RECORD_CHANGED"))).isTrue();
            assertThat(approval.status() == 200 || (approval.status() == 409 && approval.code().equals("REQUEST_STALE"))).isTrue();
            if (clockOut.status() == 200) assertThat(activeColumn(alice, day, "clock_out_at")).isNotNull().isNotEqualTo("<none>");
            else {
                assertThat(approval.status()).isEqualTo(200);
                assertThat(activeColumn(alice, day, "clock_out_at")).isNull();
                assertThat(aliceB.post(CLOCK_OUT, null).status()).as("重新查詢新版本後可正常下班").isEqualTo(200);
            }
            boolean timeWasCorrected = activeColumn(alice, day, "clock_in_at").startsWith(day + " 00:30:00");
            assertThat(timeWasCorrected).as("round %d：上班時間被更正 ⇔ 核准成功", round).isEqualTo(approval.status() == 200);
            assertThat(jdbc.queryForObject("select count(*) from attendance where employee_id = ? and work_date = ?::date and voided_at is null", Integer.class, alice.getId(), day)).isEqualTo(1);
            if (approval.status() != 200) {
                assertThat(aliceB.post(REQUESTS + "/" + id + "/cancel", null).status()).isEqualTo(200);
            }
        }
    }

    @Test
    @DisplayName("C-20 6 個並行的直接更正（同一個 expectedVersion）→ 恰 1 個 200、其餘 409 RECORD_CHANGED；只寫一筆歷程")
    void concurrentDirectCorrectionsAcceptOnlyOne() throws Exception {
        TestBrowser aliceB = loginAs("alice");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        clock.set(utc("2026-10-01T10:00:00Z"));
        long version = activeVersion(alice, "2026-10-01");
        long recordId = activeId(alice, "2026-10-01");
        List<TestBrowser> admins = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            admins.add(loginAs("carol"));
            admins.add(loginAs("dave"));
        }
        List<Callable<Reply>> tasks = new ArrayList<>();
        int n = 0;
        for (TestBrowser admin : admins) {
            String minute = String.format("08:%02d:00", 10 + n++); // 每個請求希望的時間不同
            String body = json("employeeId", String.valueOf(alice.getId()), "workDate", str("2026-10-01"),
                    "attendanceId", String.valueOf(recordId), "expectedVersion", String.valueOf(version), "clockInTime", str(minute), "clockOutTime", "null", "reason", str("並行更正"));
            tasks.add(() -> admin.post(ADMIN + "/records/correct", body));
        }

        List<Reply> replies = runSimultaneously(tasks);

        assertThat(replies.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
        assertThat(replies.stream().filter(r -> r.status() == 409 && r.code().equals("RECORD_CHANGED")).count()).isEqualTo(5);
        assertThat(activeVersion(alice, "2026-10-01")).isEqualTo(version + 1);
        assertThat(historyRows()).isEqualTo(1);
        assertThat(activeRows(alice)).isEqualTo(1);
    }
}
