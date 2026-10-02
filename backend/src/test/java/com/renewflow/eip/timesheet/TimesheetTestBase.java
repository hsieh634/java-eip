package com.renewflow.eip.timesheet;

import com.renewflow.eip.attendance.CorrectionsTestBase;
import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;

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

/**
 * T04 測試共用基礎：沿用 alice（EMPLOYEE）、bob（MANAGER）、carol／dave（ADMIN）與可控時鐘（台北 2026-10-01 09:00）。
 * 每個測試前清空測試庫（含專案與工時）。專案與工時都是測試資料，只存在測試庫。
 */
public abstract class TimesheetTestBase extends CorrectionsTestBase {

    protected static final String ADMIN_PROJECTS = "/api/admin/projects";
    protected static final String PROJECTS = "/api/projects";
    protected static final String MY = "/api/timesheets/me";

    protected static String projectBody(String code, String name, String description) {
        return json("code", code == null ? "null" : str(code), "name", name == null ? "null" : str(name),
                "description", description == null ? "null" : str(description));
    }

    protected static String projectUpdate(String name, String description, boolean active, long expectedVersion) {
        return json("name", str(name), "description", description == null ? "null" : str(description),
                "active", String.valueOf(active), "expectedVersion", String.valueOf(expectedVersion));
    }

    /** 以 carol（ADMIN）建立專案並回傳 id。 */
    protected long createProject(String code, String name) {
        Reply reply = loginAs("carol").post(ADMIN_PROJECTS, projectBody(code, name, null));
        assertThat(reply.status()).as("create project %s", reply.body()).isEqualTo(201);
        return reply.json().get("id").asLong();
    }

    /** 以 carol 停用或啟用專案（讀取目前版本後提交）。 */
    protected void setProjectActive(long projectId, boolean active) {
        TestBrowser admin = loginAs("carol");
        long version = jdbc.queryForObject("select version from project where id = ?", Long.class, projectId);
        String name = jdbc.queryForObject("select name from project where id = ?", String.class, projectId);
        Reply reply = admin.put(ADMIN_PROJECTS + "/" + projectId, projectUpdate(name, null, active, version));
        assertThat(reply.status()).as("set active %s", reply.body()).isEqualTo(200);
    }

    protected static String entryBody(UUID requestId, String workDate, long projectId, String description, Object minutes) {
        return json("requestId", requestId == null ? "null" : str(requestId.toString()), "workDate", str(workDate),
                "projectId", String.valueOf(projectId), "description", str(description), "minutes", String.valueOf(minutes));
    }

    protected static String updateBody(String workDate, long projectId, String description, Object minutes, long expectedVersion) {
        return json("workDate", str(workDate), "projectId", String.valueOf(projectId), "description", str(description),
                "minutes", String.valueOf(minutes), "expectedVersion", String.valueOf(expectedVersion));
    }

    protected Reply add(TestBrowser browser, String workDate, long projectId, String description, int minutes) {
        return browser.post(MY, entryBody(UUID.randomUUID(), workDate, projectId, description, minutes));
    }

    protected long addOk(TestBrowser browser, String workDate, long projectId, String description, int minutes) {
        Reply reply = add(browser, workDate, projectId, description, minutes);
        assertThat(reply.status()).as("add entry %s", reply.body()).isEqualTo(201);
        return reply.json().get("id").asLong();
    }

    protected int entryRows() {
        return jdbc.queryForObject("select count(*) from timesheet_entry", Integer.class);
    }

    protected int minutesOfDay(long employeeId, String workDate) {
        return jdbc.queryForObject("select coalesce(sum(minutes), 0) from timesheet_entry where employee_id = ? and work_date = ?::date",
                Integer.class, employeeId, workDate);
    }

    protected long entryVersion(long id) {
        return jdbc.queryForObject("select version from timesheet_entry where id = ?", Long.class, id);
    }

    /** 用閘門讓多個請求同時放行，對真實 PostgreSQL 競爭。 */
    protected static <T> List<T> runSimultaneously(List<Callable<T>> tasks) throws Exception {
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
}
