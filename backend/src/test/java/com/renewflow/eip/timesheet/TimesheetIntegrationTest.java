package com.renewflow.eip.timesheet;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T04 本人工時：新增、查詢與合計、修改、驗證、專案狀態、資料所有權、每日上限、版本、重複提交、認證。
 * 時間由可控時鐘決定（預設台北 2026-10-01 09:00）。
 */
class TimesheetIntegrationTest extends TimesheetTestBase {

    private long web;
    private long ops;

    @BeforeEach
    void projects() {
        web = createProject("TP-WEB", "TeamPortal 網站");
        ops = createProject("OPS", "維運");
    }

    @Test
    @DisplayName("W-01 新增多筆（同日多專案、同專案多筆）→ 查詢：日期新到舊、同日 id 新到舊；期間總計與每日合計正確")
    void createAndList() {
        TestBrowser aliceB = loginAs("alice");
        long a = addOk(aliceB, "2026-09-30", web, "需求訪談", 120);
        long b = addOk(aliceB, "2026-10-01", web, "撰寫 API 文件", 90);
        long c = addOk(aliceB, "2026-10-01", ops, "部署檢查", 45);
        long d = addOk(aliceB, "2026-10-01", web, "撰寫 API 文件", 90); // 相同內容是合法的另一筆

        var body = aliceB.get(MY).json();
        assertThat(body.get("from").asString()).isEqualTo("2026-10-01");
        assertThat(body.get("to").asString()).isEqualTo("2026-10-31");
        assertThat(body.get("entries")).extracting(e -> e.get("id").asLong()).containsExactly(d, c, b);
        assertThat(body.get("totalMinutes").asInt()).isEqualTo(225);
        assertThat(body.get("daily")).hasSize(1);
        assertThat(body.get("daily").get(0).get("minutes").asInt()).isEqualTo(225);

        var range = aliceB.get(MY + "?from=2026-09-30&to=2026-10-01").json();
        assertThat(range.get("entries")).extracting(e -> e.get("id").asLong()).containsExactly(d, c, b, a);
        assertThat(range.get("totalMinutes").asInt()).isEqualTo(345);
        assertThat(range.get("daily")).extracting(x -> x.get("date").asString() + "=" + x.get("minutes").asInt())
                .containsExactly("2026-10-01=225", "2026-09-30=120");
        var first = range.get("entries").get(0);
        assertThat(first.get("project").get("code").asString()).isEqualTo("TP-WEB");
        assertThat(first.get("project").get("active").asBoolean()).isTrue();
        assertThat(first.get("version").asLong()).isZero();
    }

    @Test
    @DisplayName("W-02 查詢範圍：只給一邊、起日晚於迄日、超過 31 天 → 400 INVALID_DATE_RANGE；31 天可以")
    void rangeValidation() {
        TestBrowser aliceB = loginAs("alice");
        assertThat(aliceB.get(MY + "?from=2026-10-01").code()).isEqualTo("INVALID_DATE_RANGE");
        assertThat(aliceB.get(MY + "?from=2026-10-02&to=2026-10-01").code()).isEqualTo("INVALID_DATE_RANGE");
        assertThat(aliceB.get(MY + "?from=2026-09-01&to=2026-10-02").code()).isEqualTo("INVALID_DATE_RANGE");
        assertThat(aliceB.get(MY + "?from=2026-09-01&to=2026-10-01").status()).isEqualTo(200);
        assertThat(aliceB.get(MY + "?from=not-a-date&to=2026-10-01").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("W-03 分鐘數：15 與 1440 可以；0、-15、14、1441 → VALIDATION_FAILED；20、1439 → INVALID_TIMESHEET；小數 90.5 不會被截斷成 90")
    void minuteBoundaries() {
        TestBrowser aliceB = loginAs("alice");
        assertThat(add(aliceB, "2026-09-29", web, "邊界 15", 15).status()).isEqualTo(201);
        assertThat(add(aliceB, "2026-09-28", web, "邊界 1440", 1440).status()).isEqualTo(201);
        for (int bad : List.of(0, -15, 14, 1441)) {
            Reply r = add(aliceB, "2026-09-27", web, "非法 " + bad, bad);
            assertThat(r.status()).as("minutes " + bad).isEqualTo(400);
            assertThat(r.code()).as("minutes " + bad).isEqualTo("VALIDATION_FAILED");
        }
        for (int bad : List.of(20, 1439)) {
            Reply r = add(aliceB, "2026-09-27", web, "非法 " + bad, bad);
            assertThat(r.code()).as("minutes " + bad).isEqualTo("INVALID_TIMESHEET");
        }
        Reply fraction = aliceB.post(MY, entryBody(UUID.randomUUID(), "2026-09-27", web, "小數", "90.5"));
        assertThat(fraction.status()).as("90.5 → %s", fraction.body()).isEqualTo(400);
        Reply text = aliceB.post(MY, entryBody(UUID.randomUUID(), "2026-09-27", web, "文字", "\"1.5h\""));
        assertThat(text.status()).isEqualTo(400);
        assertThat(entryRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("W-04 內容：去除空白後為空 → 400；超過 1000 字 → 400；前後空白會被去除")
    void descriptionRules() {
        TestBrowser aliceB = loginAs("alice");
        assertThat(add(aliceB, "2026-10-01", web, "   ", 60).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(add(aliceB, "2026-10-01", web, "字".repeat(1001), 60).code()).isEqualTo("INVALID_TIMESHEET");
        Reply ok = add(aliceB, "2026-10-01", web, "  " + "字".repeat(1000) + "  ", 60);
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.json().get("description").asString()).hasSize(1000);
    }

    @Test
    @DisplayName("W-05 未來日期與台北日期邊界：台北 23:59:59 時「明天」不行；到台北 00:00 後就可以")
    void futureDateAndTaipeiBoundary() {
        TestBrowser aliceB = loginAs("alice");
        clock.set(utc("2026-10-01T15:59:59Z")); // 台北 2026-10-01 23:59:59
        Reply tomorrow = add(aliceB, "2026-10-02", web, "未來", 60);
        assertThat(tomorrow.status()).isEqualTo(400);
        assertThat(tomorrow.code()).isEqualTo("INVALID_TIMESHEET");
        assertThat(add(aliceB, "2026-10-01", web, "今天", 60).status()).isEqualTo(201);

        clock.set(utc("2026-10-01T16:00:00Z")); // 台北 2026-10-02 00:00:00
        assertThat(add(aliceB, "2026-10-02", web, "台北已是 10/2", 60).status()).isEqualTo(201);
        assertThat(add(aliceB, "2025-01-01", web, "很久以前也可以補填", 60).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("W-06 專案：不存在 → 404 PROJECT_NOT_FOUND；停用 → 409 PROJECT_INACTIVE；重新啟用後可以新增")
    void projectRules() {
        TestBrowser aliceB = loginAs("alice");
        assertThat(add(aliceB, "2026-10-01", 999_999, "不存在", 60).code()).isEqualTo("PROJECT_NOT_FOUND");
        setProjectActive(ops, false);
        Reply inactive = add(aliceB, "2026-10-01", ops, "停用專案", 60);
        assertThat(inactive.status()).isEqualTo(409);
        assertThat(inactive.code()).isEqualTo("PROJECT_INACTIVE");
        assertThat(entryRows()).isZero();
        setProjectActive(ops, true);
        assertThat(add(aliceB, "2026-10-01", ops, "重新啟用", 60).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("W-07 停用專案的既有紀錄：仍可查詢（標示停用）；維持原專案可改日期、時數、內容；可改掛到啟用專案；別的紀錄不能改掛到停用專案")
    void inactiveProjectEntries() {
        TestBrowser aliceB = loginAs("alice");
        long old = addOk(aliceB, "2026-10-01", ops, "停用前的工作", 60);
        long other = addOk(aliceB, "2026-10-01", web, "網站工作", 60);
        setProjectActive(ops, false);

        var listed = aliceB.get(MY).json().get("entries");
        assertThat(listed).anySatisfy(e -> {
            assertThat(e.get("id").asLong()).isEqualTo(old);
            assertThat(e.get("project").get("active").asBoolean()).isFalse();
        });
        Reply keep = aliceB.put(MY + "/" + old, updateBody("2026-09-30", ops, "停用前的工作（修正）", 75, 0));
        assertThat(keep.status()).as(keep.body()).isEqualTo(200);
        assertThat(keep.json().get("project").get("active").asBoolean()).isFalse();
        Reply moveIn = aliceB.put(MY + "/" + other, updateBody("2026-10-01", ops, "網站工作", 60, 0));
        assertThat(moveIn.status()).isEqualTo(409);
        assertThat(moveIn.code()).isEqualTo("PROJECT_INACTIVE");
        Reply moveOut = aliceB.put(MY + "/" + old, updateBody("2026-09-30", web, "改掛到網站", 75, 1));
        assertThat(moveOut.status()).isEqualTo(200);
        assertThat(moveOut.json().get("project").get("code").asString()).isEqualTo("TP-WEB");
    }

    @Test
    @DisplayName("W-08 資料所有權：他人的紀錄 GET／PUT → 404（與不存在相同，不洩漏內容）；清單只有自己的；本文夾帶 employeeId 無效")
    void ownership() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser bobB = loginAs("bob"); // MANAGER 也不能看別人的工時
        long alices = addOk(aliceB, "2026-10-01", web, "愛麗絲的工作", 60);

        Reply peek = bobB.get(MY + "/" + alices);
        assertThat(peek.status()).isEqualTo(404);
        assertThat(peek.code()).isEqualTo("TIMESHEET_NOT_FOUND");
        assertThat(peek.body()).doesNotContain("愛麗絲的工作");
        assertThat(bobB.get(MY + "/999999").code()).as("與不存在的回應相同").isEqualTo("TIMESHEET_NOT_FOUND");
        Reply hijack = bobB.put(MY + "/" + alices, updateBody("2026-10-01", web, "被改了", 60, 0));
        assertThat(hijack.status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select description from timesheet_entry where id = ?", String.class, alices)).isEqualTo("愛麗絲的工作");
        assertThat(bobB.get(MY).json().get("entries")).isEmpty();

        String forged = entryBody(UUID.randomUUID(), "2026-10-01", web, "我想替愛麗絲填", 60);
        forged = forged.substring(0, forged.length() - 1) + ",\"employeeId\":" + alice.getId() + ",\"version\":99,\"createdAt\":\"2000-01-01T00:00:00Z\"}";
        Reply created = bobB.post(MY, forged);
        assertThat(created.status()).isEqualTo(201);
        long forgedId = created.json().get("id").asLong();
        assertThat(jdbc.queryForObject("select employee_id from timesheet_entry where id = ?", Long.class, forgedId)).isEqualTo(bob.getId());
        assertThat(created.json().get("version").asLong()).isZero();
        assertThat(created.json().get("createdAt").asString()).isEqualTo("2026-10-01T01:00:00Z");
    }

    @Test
    @DisplayName("W-09 每日 24 小時上限：剛好 1440 可以，再多 15 分 → 409 DAILY_LIMIT_EXCEEDED（附分鐘數）；不同天、不同人各自計算")
    void dailyLimit() {
        TestBrowser aliceB = loginAs("alice");
        addOk(aliceB, "2026-10-01", web, "上午", 1380);
        addOk(aliceB, "2026-10-01", ops, "晚上", 60);
        Reply over = add(aliceB, "2026-10-01", web, "再加一點", 15);

        assertThat(over.status()).isEqualTo(409);
        assertThat(over.code()).isEqualTo("DAILY_LIMIT_EXCEEDED");
        assertThat(over.json().get("limitMinutes").asInt()).isEqualTo(1440);
        assertThat(over.json().get("otherMinutes").asInt()).isEqualTo(1440);
        assertThat(over.json().get("requestedMinutes").asInt()).isEqualTo(15);
        assertThat(minutesOfDay(alice.getId(), "2026-10-01")).isEqualTo(1440);
        assertThat(add(aliceB, "2026-09-30", web, "前一天", 15).status()).isEqualTo(201);
        assertThat(add(loginAs("bob"), "2026-10-01", web, "鮑伯同一天", 1440).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("W-10 修改時的合計：同日修改排除自己的舊值；換日期檢查目標日；同日換專案不重複計算")
    void dailyLimitOnUpdate() {
        TestBrowser aliceB = loginAs("alice");
        long a = addOk(aliceB, "2026-10-01", web, "A", 840);
        long b = addOk(aliceB, "2026-10-01", ops, "B", 600); // 當日 1440

        assertThat(aliceB.put(MY + "/" + a, updateBody("2026-10-01", web, "A", 855, 0)).code()).as("840→855：600+855>1440").isEqualTo("DAILY_LIMIT_EXCEEDED");
        assertThat(aliceB.put(MY + "/" + a, updateBody("2026-10-01", web, "A 改內容", 840, 0)).status()).as("同值只改內容：不能把舊的 840 再算一次").isEqualTo(200);
        assertThat(aliceB.put(MY + "/" + a, updateBody("2026-10-01", ops, "A 改內容", 840, 1)).status()).as("同日換專案").isEqualTo(200);
        assertThat(aliceB.put(MY + "/" + a, updateBody("2026-10-01", ops, "A 改內容", 825, 2)).status()).as("減少").isEqualTo(200);

        long c = addOk(aliceB, "2026-09-30", web, "C", 900);
        Reply move = aliceB.put(MY + "/" + b, updateBody("2026-09-30", ops, "B", 600, 0));
        assertThat(move.code()).as("換到 09-30：900+600>1440").isEqualTo("DAILY_LIMIT_EXCEEDED");
        assertThat(aliceB.put(MY + "/" + b, updateBody("2026-09-30", ops, "B", 540, 0)).status()).as("900+540=1440").isEqualTo(200);
        assertThat(minutesOfDay(alice.getId(), "2026-09-30")).isEqualTo(1440);
        assertThat(minutesOfDay(alice.getId(), "2026-10-01")).isEqualTo(825);
        assertThat(entryVersion(c)).isZero();
    }

    @Test
    @DisplayName("W-11 版本：兩個分頁讀到同一版本，先存的成功、後存的 409 TIMESHEET_CHANGED，不覆蓋；沒有變更 → 400")
    void versionConflict() {
        TestBrowser tab1 = loginAs("alice");
        TestBrowser tab2 = loginAs("alice");
        long id = addOk(tab1, "2026-10-01", web, "原本", 60);
        long v = tab1.get(MY + "/" + id).json().get("version").asLong();
        assertThat(tab2.get(MY + "/" + id).json().get("version").asLong()).isEqualTo(v);

        assertThat(tab1.put(MY + "/" + id, updateBody("2026-10-01", web, "分頁一", 90, v)).status()).isEqualTo(200);
        Reply stale = tab2.put(MY + "/" + id, updateBody("2026-10-01", web, "分頁二", 30, v));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("TIMESHEET_CHANGED");
        assertThat(jdbc.queryForObject("select description from timesheet_entry where id = ?", String.class, id)).isEqualTo("分頁一");
        assertThat(entryVersion(id)).isEqualTo(v + 1);
        assertThat(tab1.put(MY + "/" + id, updateBody("2026-10-01", web, "分頁一", 90, v + 1)).code()).isEqualTo("INVALID_TIMESHEET");
    }

    @Test
    @DisplayName("W-12 重複提交：同一個 requestId 再送（即使內容不同）→ 409 DUPLICATE_SUBMISSION 附本人紀錄 ID；不新增、也不當作修改；不同員工可用同一個 requestId")
    void duplicateRequestId() {
        TestBrowser aliceB = loginAs("alice");
        UUID requestId = UUID.randomUUID();
        Reply first = aliceB.post(MY, entryBody(requestId, "2026-10-01", web, "第一次送出", 60));
        long id = first.json().get("id").asLong();

        Reply again = aliceB.post(MY, entryBody(requestId, "2026-10-01", web, "第一次送出", 60));
        Reply different = aliceB.post(MY, entryBody(requestId, "2026-09-30", ops, "內容不同的重送", 120));

        for (Reply r : List.of(again, different)) {
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.code()).isEqualTo("DUPLICATE_SUBMISSION");
            assertThat(r.json().get("entryId").asLong()).isEqualTo(id);
        }
        assertThat(entryRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select description from timesheet_entry where id = ?", String.class, id)).isEqualTo("第一次送出");
        assertThat(entryVersion(id)).isZero();
        assertThat(loginAs("bob").post(MY, entryBody(requestId, "2026-10-01", web, "鮑伯碰巧同一個 requestId", 60)).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("W-13 認證與帳號：未登入 401；缺 CSRF 403；停用或角色變更後的舊 Session 401；都沒有寫入")
    void authentication() {
        TestBrowser anonymous = new TestBrowser(port);
        anonymous.get("/api/auth/me");
        assertThat(anonymous.get(MY).status()).isEqualTo(401);
        assertThat(anonymous.post(MY, entryBody(UUID.randomUUID(), "2026-10-01", web, "匿名", 60)).status()).isIn(401, 403);

        TestBrowser aliceB = loginAs("alice");
        assertThat(aliceB.postWithCsrf(MY, entryBody(UUID.randomUUID(), "2026-10-01", web, "缺 CSRF", 60), null).status()).isEqualTo(403);
        TestBrowser bobB = loginAs("bob");
        jdbc.update("update employee set enabled = false where id = ?", alice.getId());
        jdbc.update("update employee set role = 'EMPLOYEE' where id = ?", bob.getId());
        assertThat(aliceB.post(MY, entryBody(UUID.randomUUID(), "2026-10-01", web, "停用後", 60)).status()).isEqualTo(401);
        assertThat(bobB.post(MY, entryBody(UUID.randomUUID(), "2026-10-01", web, "降級後", 60)).status()).isEqualTo(401);
        assertThat(entryRows()).isZero();
    }

    @Test
    @DisplayName("W-14 所有角色都能填自己的工時（EMPLOYEE、MANAGER、ADMIN）")
    void everyRoleFillsOwn() {
        for (String user : List.of("alice", "bob", "carol")) {
            assertThat(add(loginAs(user), "2026-10-01", web, user + " 的工作", 60).status()).as(user).isEqualTo(201);
        }
        assertThat(jdbc.queryForObject("select count(distinct employee_id) from timesheet_entry", Integer.class)).isEqualTo(3);
    }
}
