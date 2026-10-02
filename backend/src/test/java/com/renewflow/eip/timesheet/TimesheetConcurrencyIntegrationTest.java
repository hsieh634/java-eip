package com.renewflow.eip.timesheet;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * T04 並行、鎖與交易：用閘門讓請求「同時」放行，對真實 PostgreSQL 競爭；
 * 以真實的資料列鎖（另開 JDBC 交易或在寫入途中暫停）觀察誰等誰。
 */
class TimesheetConcurrencyIntegrationTest extends TimesheetTestBase {

    @MockitoSpyBean
    TimesheetViews views;
    @Autowired
    DataSource dataSource;

    private long web;

    @BeforeEach
    void project() {
        web = createProject("TP-WEB", "TeamPortal 網站");
    }

    @AfterEach
    void repair() {
        doCallRealMethod().when(views).entry(any(), any());
    }

    private List<TestBrowser> tabs(String username, int count) {
        List<TestBrowser> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            TestBrowser b = loginAs(username);
            b.get(MY); // 預先建立連線
            list.add(b);
        }
        return list;
    }

    @Test
    @DisplayName("K-01 同一人 8 個分頁同時各新增 300 分鐘（多輪、不同日期）→ 每天恰 4 筆成功（1200），其餘 DAILY_LIMIT_EXCEEDED；合計從不超過 1440")
    void parallelCreatesRespectDailyLimit() throws Exception {
        List<TestBrowser> tabs = tabs("alice", 8);
        for (String day : List.of("2026-10-01", "2026-09-30", "2026-09-29")) {
            List<Callable<Reply>> tasks = new ArrayList<>();
            for (TestBrowser t : tabs) {
                tasks.add(() -> add(t, day, web, "並行", 300));
            }
            List<Reply> replies = runSimultaneously(tasks);
            assertThat(replies.stream().filter(r -> r.status() == 201).count()).as(day).isEqualTo(4);
            assertThat(replies.stream().filter(r -> r.status() == 409 && r.code().equals("DAILY_LIMIT_EXCEEDED")).count()).as(day).isEqualTo(4);
            assertThat(minutesOfDay(alice.getId(), day)).as(day).isEqualTo(1200);
        }
    }

    @Test
    @DisplayName("K-02 同一天兩筆各 705，同時把兩筆都改成 735（各自單獨剛好 1440，一起會到 1470）→ 每輪恰 1 筆成功")
    void parallelUpdatesRespectDailyLimit() throws Exception {
        TestBrowser t1 = loginAs("alice");
        TestBrowser t2 = loginAs("alice");
        for (int round = 1; round <= 5; round++) {
            String day = "2026-09-2" + round;
            long a = addOk(t1, day, web, "A", 705);
            long b = addOk(t1, day, web, "B", 705);
            List<Reply> replies = runSimultaneously(List.<Callable<Reply>>of(
                    () -> t1.put(MY + "/" + a, updateBody(day, web, "A", 735, 0)),
                    () -> t2.put(MY + "/" + b, updateBody(day, web, "B", 735, 0))));
            assertThat(replies.stream().filter(r -> r.status() == 200).count()).as("round %d %s", round, replies).isEqualTo(1);
            assertThat(replies.stream().filter(r -> r.status() == 409 && r.code().equals("DAILY_LIMIT_EXCEEDED")).count()).isEqualTo(1);
            assertThat(minutesOfDay(alice.getId(), day)).isEqualTo(1440);
        }
    }

    @Test
    @DisplayName("K-03 同一個 requestId 由 8 個分頁同時送出 → 恰 1 筆建立，其餘 409 DUPLICATE_SUBMISSION 且都指向同一筆")
    void parallelDuplicateSubmissions() throws Exception {
        List<TestBrowser> tabs = tabs("alice", 8);
        UUID requestId = UUID.randomUUID();
        List<Callable<Reply>> tasks = new ArrayList<>();
        for (TestBrowser t : tabs) {
            tasks.add(() -> t.post(MY, entryBody(requestId, "2026-10-01", web, "網路不穩時的重送", 60)));
        }
        List<Reply> replies = runSimultaneously(tasks);

        List<Reply> created = replies.stream().filter(r -> r.status() == 201).toList();
        assertThat(created).hasSize(1);
        long id = created.get(0).json().get("id").asLong();
        assertThat(replies.stream().filter(r -> r.status() == 409).allMatch(r -> r.code().equals("DUPLICATE_SUBMISSION") && r.json().get("entryId").asLong() == id)).isTrue();
        assertThat(entryRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("K-04 同一筆紀錄由 6 個分頁以同一版本同時修改 → 恰 1 個成功，其餘 409 TIMESHEET_CHANGED；版本只 +1")
    void parallelEditsSameVersion() throws Exception {
        TestBrowser owner = loginAs("alice");
        long id = addOk(owner, "2026-10-01", web, "原本", 60);
        List<TestBrowser> tabs = tabs("alice", 6);
        List<Callable<Reply>> tasks = new ArrayList<>();
        int n = 0;
        for (TestBrowser t : tabs) {
            int minutes = 60 + 15 * ++n;
            tasks.add(() -> t.put(MY + "/" + id, updateBody("2026-10-01", web, "分頁 " + minutes, minutes, 0)));
        }
        List<Reply> replies = runSimultaneously(tasks);
        assertThat(replies.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
        assertThat(replies.stream().filter(r -> r.status() == 409 && r.code().equals("TIMESHEET_CHANGED")).count()).isEqualTo(5);
        assertThat(entryVersion(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("K-05 停用先完成：停用的交易尚未提交時新增會等待；提交後新增被拒（409 PROJECT_INACTIVE），沒有寫入")
    void createWaitsForInFlightDeactivation() throws Exception {
        TestBrowser aliceB = loginAs("alice");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection admin = dataSource.getConnection()) {
            admin.setAutoCommit(false);
            try (Statement st = admin.createStatement()) {
                st.executeUpdate("update project set active = false, version = version + 1 where id = " + web);
            }
            Future<Reply> create = pool.submit(() -> add(aliceB, "2026-10-01", web, "停用途中", 60));
            Thread.sleep(800);
            assertThat(create.isDone()).as("新增在等停用的交易（FOR SHARE 等待寫入鎖）").isFalse();
            admin.commit();
            Reply reply = create.get(10, TimeUnit.SECONDS);
            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.code()).isEqualTo("PROJECT_INACTIVE");
        } finally {
            pool.shutdownNow();
        }
        assertThat(entryRows()).isZero();
    }

    @Test
    @DisplayName("K-06 新增先取得專案：新增交易進行中時，停用要等它提交；新增的紀錄保留，之後停用成功")
    void deactivationWaitsForInFlightCreate() throws Exception {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            inside.countDown();
            assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            return inv.callRealMethod();
        }).when(views).entry(any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Reply> create = pool.submit(() -> add(aliceB, "2026-10-01", web, "停用前開始的新增", 60));
            assertThat(inside.await(10, TimeUnit.SECONDS)).as("新增已寫入、尚未提交").isTrue();
            Future<Reply> deactivate = pool.submit(() -> carolB.put(ADMIN_PROJECTS + "/" + web, projectUpdate("TeamPortal 網站", null, false, 0)));
            Thread.sleep(800);
            assertThat(deactivate.isDone()).as("停用在等新增的交易（UPDATE 等待 FOR SHARE）").isFalse();
            release.countDown();
            assertThat(create.get(10, TimeUnit.SECONDS).status()).isEqualTo(201);
            assertThat(deactivate.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(entryRows()).as("先完成的新增保留").isEqualTo(1);
        assertThat(jdbc.queryForObject("select active from project where id = ?", Boolean.class, web)).isFalse();
    }

    @Test
    @DisplayName("K-07 鎖的範圍是「每位員工」：愛麗絲的員工列被鎖住時她的新增會等待，但鮑伯的新增立即完成")
    void employeeLockIsPerEmployee() throws Exception {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser bobB = loginAs("bob");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (Statement st = other.createStatement()) {
                st.executeQuery("select id from employee where id = " + alice.getId() + " for no key update").close();
            }
            Future<Reply> aliceCreate = pool.submit(() -> add(aliceB, "2026-10-01", web, "愛麗絲", 60));
            Future<Reply> bobCreate = pool.submit(() -> add(bobB, "2026-10-01", web, "鮑伯", 60));
            assertThat(bobCreate.get(5, TimeUnit.SECONDS).status()).as("其他員工不被擋").isEqualTo(201);
            assertThat(aliceCreate.isDone()).as("同一位員工要等").isFalse();
            other.commit();
            assertThat(aliceCreate.get(10, TimeUnit.SECONDS).status()).isEqualTo(201);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("K-08 交易失敗不留下部分更新：寫入後在同一交易內發生錯誤 → 500；新增沒有留下紀錄、修改沒有改到資料或版本")
    void failuresRollBack() {
        TestBrowser aliceB = loginAs("alice");
        long id = addOk(aliceB, "2026-10-01", web, "原本", 60);
        doThrow(new IllegalStateException("injected after write")).when(views).entry(any(), any());

        Reply create = add(aliceB, "2026-10-01", web, "會失敗", 60);
        Reply update = aliceB.put(MY + "/" + id, updateBody("2026-10-01", web, "改到一半", 90, 0));

        assertThat(create.status()).isEqualTo(500);
        assertThat(create.code()).as("故障不偽裝成上限或重複").isEqualTo("INTERNAL_ERROR");
        assertThat(update.status()).isEqualTo(500);
        assertThat(entryRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select description from timesheet_entry where id = ?", String.class, id)).isEqualTo("原本");
        assertThat(entryVersion(id)).isZero();
    }
}
