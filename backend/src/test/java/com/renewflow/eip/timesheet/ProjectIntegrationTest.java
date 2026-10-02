package com.renewflow.eip.timesheet;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** T04 專案管理：建立、正規化、唯一、修改、停用／啟用、版本、權限。真的 HTTP＋PostgreSQL 測試庫。 */
class ProjectIntegrationTest extends TimesheetTestBase {

    private int projectRows() {
        return jdbc.queryForObject("select count(*) from project", Integer.class);
    }

    @Test
    @DisplayName("J-01 建立：代碼去除空白並轉大寫、預設啟用、版本 0；管理清單與一般使用者的可填報清單都看得到")
    void createNormalizesCode() {
        TestBrowser carolB = loginAs("carol");
        Reply created = carolB.post(ADMIN_PROJECTS, projectBody("  tp-web_1 ", "  TeamPortal 網站  ", "  前端與後端  "));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        var p = created.json();
        assertThat(p.get("code").asString()).isEqualTo("TP-WEB_1");
        assertThat(p.get("name").asString()).isEqualTo("TeamPortal 網站");
        assertThat(p.get("description").asString()).isEqualTo("前端與後端");
        assertThat(p.get("active").asBoolean()).isTrue();
        assertThat(p.get("version").asLong()).isZero();
        assertThat(p.get("createdAt").asString()).isEqualTo("2026-10-01T01:00:00Z");
        assertThat(carolB.get(ADMIN_PROJECTS).json().get("projects")).hasSize(1);
        var selectable = loginAs("alice").get(PROJECTS).json().get("projects");
        assertThat(selectable).hasSize(1);
        assertThat(selectable.get(0).get("code").asString()).isEqualTo("TP-WEB_1");
    }

    @Test
    @DisplayName("J-02 驗證：代碼含空白／中文／其他符號、太短、太長；名稱空白或過長；說明過長 → 400，不建立任何專案")
    void validation() {
        TestBrowser carolB = loginAs("carol");
        record Case(String name, String body, String code) {
        }
        List<Case> cases = List.of(
                new Case("代碼含空白", projectBody("TP WEB", "x", null), "INVALID_PROJECT"),
                new Case("代碼含中文", projectBody("專案A", "x", null), "INVALID_PROJECT"),
                new Case("代碼含句點", projectBody("TP.WEB", "x", null), "INVALID_PROJECT"),
                new Case("代碼 1 字", projectBody(" a ", "x", null), "INVALID_PROJECT"),
                new Case("代碼 33 字", projectBody("A".repeat(33), "x", null), "INVALID_PROJECT"),
                new Case("代碼空白", projectBody("   ", "x", null), "VALIDATION_FAILED"),
                new Case("代碼缺少", projectBody(null, "x", null), "VALIDATION_FAILED"),
                new Case("名稱空白", projectBody("TP", "   ", null), "VALIDATION_FAILED"),
                new Case("名稱 101 字", projectBody("TP", "名".repeat(101), null), "INVALID_PROJECT"),
                new Case("說明 1001 字", projectBody("TP", "x", "說".repeat(1001)), "VALIDATION_FAILED"));
        for (Case c : cases) {
            Reply reply = carolB.post(ADMIN_PROJECTS, c.body());
            assertThat(reply.status()).as(c.name() + " " + reply.body()).isEqualTo(400);
            assertThat(reply.code()).as(c.name()).isEqualTo(c.code());
        }
        assertThat(carolB.post(ADMIN_PROJECTS, projectBody("AB", "名".repeat(100), "說".repeat(1000))).status()).as("邊界：代碼 2 字、名稱 100、說明 1000").isEqualTo(201);
        assertThat(carolB.post(ADMIN_PROJECTS, projectBody("A".repeat(32), "x", null)).status()).as("代碼 32 字").isEqualTo(201);
        assertThat(projectRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("J-03 代碼唯一：大小寫不同也視為重複 → 409 PROJECT_CODE_DUPLICATE；8 個並行建立同一代碼 → 恰 1 個成功")
    void codeIsUnique() throws Exception {
        TestBrowser carolB = loginAs("carol");
        assertThat(carolB.post(ADMIN_PROJECTS, projectBody("TP-WEB", "第一個", null)).status()).isEqualTo(201);
        Reply dup = carolB.post(ADMIN_PROJECTS, projectBody(" tp-web ", "第二個", null));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.code()).isEqualTo("PROJECT_CODE_DUPLICATE");

        List<TestBrowser> admins = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            admins.add(loginAs("carol"));
            admins.add(loginAs("dave"));
        }
        List<Callable<Reply>> tasks = new ArrayList<>();
        int n = 0;
        for (TestBrowser admin : admins) {
            String name = "並行 " + n++;
            tasks.add(() -> admin.post(ADMIN_PROJECTS, projectBody("RACE", name, null)));
        }
        List<Reply> replies = runSimultaneously(tasks);
        assertThat(replies.stream().filter(r -> r.status() == 201).count()).isEqualTo(1);
        assertThat(replies.stream().filter(r -> r.status() == 409 && r.code().equals("PROJECT_CODE_DUPLICATE")).count()).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from project where code = 'RACE'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("J-04 修改名稱與說明；代碼不可改（本文帶 code 也被忽略）；停用後不在可填報清單、管理清單仍在；重新啟用恢復；版本 +1")
    void updateDeactivateReactivate() {
        TestBrowser carolB = loginAs("carol");
        long id = createProject("TP-WEB", "舊名稱");
        String withCode = "{\"code\":\"HACKED\",\"name\":\"新名稱\",\"description\":\"新說明\",\"active\":true,\"expectedVersion\":0}";

        Reply renamed = carolB.put(ADMIN_PROJECTS + "/" + id, withCode);
        assertThat(renamed.status()).as(renamed.body()).isEqualTo(200);
        assertThat(renamed.json().get("code").asString()).isEqualTo("TP-WEB");
        assertThat(renamed.json().get("name").asString()).isEqualTo("新名稱");
        assertThat(renamed.json().get("version").asLong()).isEqualTo(1);

        Reply deactivated = carolB.put(ADMIN_PROJECTS + "/" + id, projectUpdate("新名稱", "新說明", false, 1));
        assertThat(deactivated.json().get("active").asBoolean()).isFalse();
        assertThat(loginAs("alice").get(PROJECTS).json().get("projects")).isEmpty();
        assertThat(carolB.get(ADMIN_PROJECTS).json().get("projects").get(0).get("active").asBoolean()).isFalse();

        Reply reactivated = carolB.put(ADMIN_PROJECTS + "/" + id, projectUpdate("新名稱", "新說明", true, 2));
        assertThat(reactivated.json().get("version").asLong()).isEqualTo(3);
        assertThat(loginAs("alice").get(PROJECTS).json().get("projects")).hasSize(1);
        assertThat(projectRows()).as("停用不刪除").isEqualTo(1);
    }

    @Test
    @DisplayName("J-05 版本衝突：兩位管理員用同一版本修改 → 後到者 409 PROJECT_CHANGED，不覆蓋；不存在 404；沒有變更 400")
    void versionConflict() {
        long id = createProject("TP-WEB", "名稱");
        TestBrowser carolB = loginAs("carol");
        TestBrowser daveB = loginAs("dave");

        assertThat(carolB.put(ADMIN_PROJECTS + "/" + id, projectUpdate("卡蘿改的", null, true, 0)).status()).isEqualTo(200);
        Reply stale = daveB.put(ADMIN_PROJECTS + "/" + id, projectUpdate("戴夫用舊畫面", null, false, 0));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("PROJECT_CHANGED");
        assertThat(jdbc.queryForObject("select name from project where id = ?", String.class, id)).isEqualTo("卡蘿改的");
        assertThat(jdbc.queryForObject("select active from project where id = ?", Boolean.class, id)).isTrue();
        assertThat(carolB.put(ADMIN_PROJECTS + "/999999", projectUpdate("x", null, true, 0)).code()).isEqualTo("PROJECT_NOT_FOUND");
        Reply same = carolB.put(ADMIN_PROJECTS + "/" + id, projectUpdate("卡蘿改的", null, true, 1));
        assertThat(same.status()).isEqualTo(400);
        assertThat(same.code()).isEqualTo("INVALID_PROJECT");
    }

    @Test
    @DisplayName("J-06 權限：EMPLOYEE／MANAGER 呼叫管理 API → 403；未登入 401；缺 CSRF 403；停用或降級的管理員 401；所有角色可讀可填報專案")
    void permissions() {
        long id = createProject("TP-WEB", "名稱");
        for (String user : List.of("alice", "bob")) {
            TestBrowser b = loginAs(user);
            assertThat(b.get(ADMIN_PROJECTS).status()).as(user).isEqualTo(403);
            assertThat(b.post(ADMIN_PROJECTS, projectBody("NEW", "x", null)).status()).as(user).isEqualTo(403);
            assertThat(b.put(ADMIN_PROJECTS + "/" + id, projectUpdate("x", null, false, 0)).status()).as(user).isEqualTo(403);
            assertThat(b.get(PROJECTS).status()).as(user + " 可讀可填報專案").isEqualTo(200);
        }
        TestBrowser anonymous = new TestBrowser(port);
        anonymous.get("/api/auth/me");
        assertThat(anonymous.get(ADMIN_PROJECTS).status()).isEqualTo(401);
        assertThat(anonymous.get(PROJECTS).status()).isEqualTo(401);

        TestBrowser carolB = loginAs("carol");
        assertThat(carolB.postWithCsrf(ADMIN_PROJECTS, projectBody("NEW", "x", null), null).status()).as("缺 CSRF").isEqualTo(403);
        TestBrowser daveB = loginAs("dave");
        jdbc.update("update employee set enabled = false where id = ?", carol.getId());
        jdbc.update("update employee set role = 'EMPLOYEE' where id = ?", dave.getId());
        assertThat(carolB.post(ADMIN_PROJECTS, projectBody("NEW", "x", null)).status()).as("停用").isEqualTo(401);
        assertThat(daveB.put(ADMIN_PROJECTS + "/" + id, projectUpdate("x", null, false, 0)).status()).as("降級").isEqualTo(401);
        assertThat(projectRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from project where id = ?", Long.class, id)).isZero();
    }
}
