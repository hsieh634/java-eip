package com.renewflow.eip.attendance;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * A-24／A-25：真正的並行。多個執行緒用閘門同時放行，直接對真實的 PostgreSQL 競爭，
 * 驗證唯一限制與原子 SQL 能保證：只有一個成功、其餘得到明確的衝突、資料不被覆蓋。
 * 時鐘設為「每次讀取前進 1 秒」：若有請求覆蓋了別人的下班時間，資料庫的值就會與勝者的回應不同。
 */
class AttendanceConcurrencyIntegrationTest extends AttendanceTestBase {

    private static final int THREADS = 8;

    @Autowired
    AttendanceService attendanceService;

    /** 讓所有任務準備好後同時開始，回傳各任務的結果。 */
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

    private List<TestBrowser> loggedInBrowsers(String username, int count) {
        List<TestBrowser> browsers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            TestBrowser browser = loginAs(username);
            browser.get("/api/attendance/me/today"); // 預先建立連線，減少起跑落差
            browsers.add(browser);
        }
        return browsers;
    }

    @Test
    @DisplayName("A-24 HTTP：8 個並行上班請求（重複 4 輪）→ 每輪恰 1 個 200、其餘 409 ALREADY_CLOCKED_IN、該日只有 1 筆")
    void concurrentClockInOverHttp() throws Exception {
        List<TestBrowser> browsers = loggedInBrowsers("alice", THREADS);

        for (int round = 1; round <= 4; round++) {
            clock.setAutoAdvance(Instant.parse("2026-10-0" + round + "T01:00:00Z"), Duration.ofSeconds(1));
            List<Callable<Reply>> tasks = new ArrayList<>();
            for (TestBrowser browser : browsers) {
                tasks.add(() -> browser.post("/api/attendance/clock-in", null));
            }

            List<Reply> replies = runSimultaneously(tasks);

            long ok = replies.stream().filter(r -> r.status() == 200).count();
            long conflicts = replies.stream().filter(r -> r.status() == 409 && r.code().equals("ALREADY_CLOCKED_IN")).count();
            assertThat(ok).as("round %d 成功數", round).isEqualTo(1);
            assertThat(conflicts).as("round %d 衝突數（其餘狀態不得出現，例如 500）", round).isEqualTo(THREADS - 1);
            assertThat(attendanceRows()).as("round %d 累計筆數", round).isEqualTo(round);
        }
    }

    @Test
    @DisplayName("A-24 服務層：16 個執行緒同時上班（重複 5 輪）→ 恰 1 個成功、其餘皆為 ALREADY_CLOCKED_IN，沒有其他例外")
    void concurrentClockInAtServiceLevel() throws Exception {
        for (int round = 1; round <= 5; round++) {
            clock.setAutoAdvance(Instant.parse("2026-10-0" + round + "T01:00:00Z"), Duration.ofMillis(10));
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                tasks.add(() -> {
                    try {
                        attendanceService.clockIn(alice.getId());
                        return "OK";
                    } catch (AttendanceRuleException e) {
                        return e.getRule().name();
                    } catch (Exception e) {
                        return "UNEXPECTED:" + e;
                    }
                });
            }

            List<String> results = runSimultaneously(tasks);

            assertThat(results.stream().filter("OK"::equals).count()).as("round %d", round).isEqualTo(1);
            assertThat(results.stream().filter("ALREADY_CLOCKED_IN"::equals).count()).as("round %d", round).isEqualTo(15);
            assertThat(results).as("round %d 不得有其他例外", round).allMatch(r -> r.equals("OK") || r.equals("ALREADY_CLOCKED_IN"));
            assertThat(attendanceRows()).isEqualTo(round);
        }
    }

    @Test
    @DisplayName("A-25 HTTP：8 個並行下班請求 → 恰 1 個 200、其餘 409 ALREADY_CLOCKED_OUT；資料庫的下班時間＝勝者回應（未被覆蓋）")
    void concurrentClockOutOverHttp() throws Exception {
        List<TestBrowser> browsers = loggedInBrowsers("alice", THREADS);
        clock.set(Instant.parse("2026-10-01T01:00:00Z"));
        assertThat(browsers.get(0).post("/api/attendance/clock-in", null).status()).isEqualTo(200);

        clock.setAutoAdvance(Instant.parse("2026-10-01T10:00:00Z"), Duration.ofSeconds(1));
        List<Callable<Reply>> tasks = new ArrayList<>();
        for (TestBrowser browser : browsers) {
            tasks.add(() -> browser.post("/api/attendance/clock-out", null));
        }

        List<Reply> replies = runSimultaneously(tasks);

        List<Reply> winners = replies.stream().filter(r -> r.status() == 200).toList();
        long conflicts = replies.stream().filter(r -> r.status() == 409 && r.code().equals("ALREADY_CLOCKED_OUT")).count();
        assertThat(winners).hasSize(1);
        assertThat(conflicts).isEqualTo(THREADS - 1);

        Instant stored = jdbc.queryForObject("select clock_out_at from attendance where employee_id = ?",
                OffsetDateTime.class, alice.getId()).toInstant();
        assertThat(stored.toString()).isEqualTo(winners.get(0).json().get("clockOutAt").asString());
    }

    @Test
    @DisplayName("A-25 服務層：16 個執行緒同時下班 → 恰 1 個成功、其餘皆為 ALREADY_CLOCKED_OUT；下班時間不被後到者覆蓋")
    void concurrentClockOutAtServiceLevel() throws Exception {
        clock.set(Instant.parse("2026-10-01T01:00:00Z"));
        attendanceService.clockIn(alice.getId());

        clock.setAutoAdvance(Instant.parse("2026-10-01T10:00:00Z"), Duration.ofSeconds(1));
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            tasks.add(() -> {
                try {
                    return "OK:" + attendanceService.clockOut(alice.getId()).clockOutAt();
                } catch (AttendanceRuleException e) {
                    return e.getRule().name();
                } catch (Exception e) {
                    return "UNEXPECTED:" + e;
                }
            });
        }

        List<String> results = runSimultaneously(tasks);

        List<String> winners = results.stream().filter(r -> r.startsWith("OK:")).toList();
        assertThat(winners).hasSize(1);
        assertThat(results.stream().filter("ALREADY_CLOCKED_OUT"::equals).count()).isEqualTo(15);
        Instant stored = jdbc.queryForObject("select clock_out_at from attendance where employee_id = ?",
                OffsetDateTime.class, alice.getId()).toInstant();
        assertThat("OK:" + stored).isEqualTo(winners.get(0));
    }
}
