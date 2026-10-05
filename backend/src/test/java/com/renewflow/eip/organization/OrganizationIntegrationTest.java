package com.renewflow.eip.organization;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.organization.OrganizationDtos.UpdateEmployeeRequest;
import com.renewflow.eip.shared.web.ApiException;
import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T05 前置：部門與員工管理的 API 行為（真的 Tomcat＋真的 PostgreSQL 測試庫＋完整 Security）。 */
class OrganizationIntegrationTest extends OrganizationTestBase {

    @Autowired
    EmployeeAdminService employeeAdminService;

    // ================================================================ 權限

    @Test
    @DisplayName("O-01 權限：未登入 401；EMPLOYEE／MANAGER 對所有組織 API 都是 403；ADMIN 可用；回應不含密碼雜湊")
    void onlyAdminCanUseOrganizationApis() {
        long deptId = createDept("PERM", "權限測試");
        List<String> gets = List.of(DEPARTMENTS, DEPARTMENTS + "/" + deptId + "/history", EMPLOYEES, EMPLOYEES + "/" + alice.getId(),
                EMPLOYEES + "/manager-options", EMPLOYEES + "/" + alice.getId() + "/history");
        TestBrowser anonymous = new TestBrowser(port);
        anonymous.get("/api/auth/me");
        for (String path : gets) {
            assertThat(anonymous.get(path).status()).as("anonymous GET " + path).isEqualTo(401);
        }
        for (String user : List.of("alice", "bob")) {
            TestBrowser b = loginAs(user);
            for (String path : gets) {
                assertThat(b.get(path).status()).as(user + " GET " + path).isEqualTo(403);
            }
            assertThat(b.post(DEPARTMENTS, deptBody("NOPE", "不行")).status()).isEqualTo(403);
            assertThat(b.put(DEPARTMENTS + "/" + deptId, deptUpdate("改名", true, 0, null)).status()).isEqualTo(403);
            assertThat(b.post(EMPLOYEES, createBody("N-001", "nope", "不行", "ADMIN", null, null, "x-Passw0rd")).status()).isEqualTo(403);
            assertThat(form(alice.getId()).role("ADMIN").submit(b).status()).as(user + " 提升權限").isEqualTo(403);
        }
        assertThat(jdbc.queryForObject("select count(*) from employee where username = 'nope'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select role from employee where id = ?", String.class, alice.getId())).isEqualTo("EMPLOYEE");
        assertThat(jdbc.queryForObject("select count(*) from department", Integer.class)).isEqualTo(1);

        TestBrowser carolB = loginAs("carol");
        for (String path : gets) {
            Reply reply = carolB.get(path);
            assertThat(reply.status()).as("carol GET " + path).isEqualTo(200);
            assertThat(reply.body()).doesNotContain("{bcrypt}").doesNotContainIgnoringCase("password");
        }
    }

    @Test
    @DisplayName("O-01b Service 也檢查 ADMIN（不只靠 URL 規則）：以非 ADMIN 身份直接呼叫 → AccessDenied")
    void serviceRejectsNonAdmin() {
        EmployeeUserDetails bobDetails = new EmployeeUserDetails(bob);
        assertThatThrownBy(() -> employeeAdminService.search(bobDetails, null, null, 0, 20))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> employeeAdminService.update(bobDetails, alice.getId(),
                new UpdateEmployeeRequest("x", Role.ADMIN, true, null, null, 0L, "提權")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    // ================================================================ 部門

    @Test
    @DisplayName("O-02 建立部門：代碼去空白轉大寫、名稱去空白；格式不符 400；代碼重複 409；寫入建立歷程（操作者 carol）")
    void createDepartment() {
        TestBrowser carolB = loginAs("carol");
        Reply created = carolB.post(DEPARTMENTS, deptBody("  project ", "  專案服務 "));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().get("code").asString()).isEqualTo("PROJECT");
        assertThat(created.json().get("name").asString()).isEqualTo("專案服務");
        assertThat(created.json().get("active").asBoolean()).isTrue();
        assertThat(created.json().get("version").asLong()).isZero();

        for (String bad : List.of("A", "HAS SPACE", "中文", "X".repeat(33))) {
            Reply r = carolB.post(DEPARTMENTS, deptBody(bad, "名稱"));
            assertThat(r.status()).as("code " + bad).isEqualTo(400);
        }
        assertThat(carolB.post(DEPARTMENTS, deptBody("OK", "   ")).status()).isEqualTo(400);
        assertThat(carolB.post(DEPARTMENTS, deptBody("OK", "名".repeat(101))).code()).isEqualTo("INVALID_DEPARTMENT");
        Reply dup = carolB.post(DEPARTMENTS, deptBody("Project", "重複"));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.code()).isEqualTo("DEPARTMENT_CODE_DUPLICATE");
        assertThat(jdbc.queryForObject("select count(*) from department", Integer.class)).isEqualTo(1);

        JsonNode history = carolB.get(DEPARTMENTS + "/" + created.json().get("id").asLong() + "/history").json().get("entries");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("action").asString()).isEqualTo("DEPARTMENT_CREATED");
        assertThat(history.get(0).get("source").asString()).isEqualTo("ADMIN");
        assertThat(history.get(0).get("actor").get("employeeNo").asString()).isEqualTo("T0003");
        assertThat(history.get(0).get("changes").toString()).contains("PROJECT").contains("專案服務");
    }

    @Test
    @DisplayName("O-03 修改部門：改名不需原因；改啟用狀態需原因；沒有變更 400；過期版本 409 且資料不變；不存在 404；代碼不可修改")
    void updateDepartment() {
        long id = createDept("SALES", "業務");
        TestBrowser carolB = loginAs("carol");

        Reply renamed = carolB.put(DEPARTMENTS + "/" + id, deptUpdate(" 業務部 ", true, 0, null));
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.json().get("name").asString()).isEqualTo("業務部");
        assertThat(renamed.json().get("version").asLong()).isEqualTo(1);

        Reply noReason = carolB.put(DEPARTMENTS + "/" + id, deptUpdate("業務部", false, 1, "  "));
        assertThat(noReason.status()).isEqualTo(400);
        assertThat(noReason.code()).isEqualTo("REASON_REQUIRED");

        assertThat(carolB.put(DEPARTMENTS + "/" + id, deptUpdate("業務部", true, 1, null)).code()).isEqualTo("NO_CHANGE");

        Reply stale = carolB.put(DEPARTMENTS + "/" + id, deptUpdate("舊畫面名稱", true, 0, null));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("DEPARTMENT_CHANGED");
        assertThat(jdbc.queryForObject("select name from department where id = ?", String.class, id)).isEqualTo("業務部");

        assertThat(carolB.put(DEPARTMENTS + "/999999", deptUpdate("x", true, 0, null)).code()).isEqualTo("DEPARTMENT_NOT_FOUND");
        assertThat(carolB.get(DEPARTMENTS + "/999999/history").status()).isEqualTo(404);

        Reply withCode = carolB.put(DEPARTMENTS + "/" + id, "{\"code\":\"HACK\",\"name\":\"業務部\",\"active\":false,\"expectedVersion\":1,\"reason\":\"停用\"}");
        assertThat(withCode.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select code from department where id = ?", String.class, id)).as("代碼不可修改").isEqualTo("SALES");
        assertThat(deptActive(id)).isFalse();

        Reply reactivated = carolB.put(DEPARTMENTS + "/" + id, deptUpdate("業務部", true, 2, "重新啟用"));
        assertThat(reactivated.status()).isEqualTo(200);
        JsonNode entries = carolB.get(DEPARTMENTS + "/" + id + "/history").json().get("entries");
        assertThat(entries).hasSize(4);
        assertThat(entries.get(0).get("reason").asString()).isEqualTo("重新啟用");
        assertThat(entries.get(0).get("changes").toString()).contains("\"field\":\"active\"").contains("\"before\":false").contains("\"after\":true");
        assertThat(entries.get(3).get("action").asString()).as("新到舊").isEqualTo("DEPARTMENT_CREATED");
    }

    @Test
    @DisplayName("O-04 停用部門：仍有啟用中的員工 → 409（附人數）；只剩已停用的員工 → 可以停用，員工保留部門")
    void departmentWithActiveEmployeesCannotBeDeactivated() {
        long id = createDept("OPS", "營運");
        TestBrowser carolB = loginAs("carol");
        assertThat(form(alice.getId()).department(id).submit(carolB).status()).isEqualTo(200);

        Reply blocked = setDeptActive(carolB, id, false);
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.code()).isEqualTo("DEPARTMENT_HAS_ACTIVE_EMPLOYEES");
        assertThat(blocked.json().get("activeEmployeeCount").asLong()).isEqualTo(1);
        assertThat(deptActive(id)).isTrue();

        assertThat(form(alice.getId()).enabled(false).submit(carolB).status()).isEqualTo(200);
        assertThat(setDeptActive(carolB, id, false).status()).isEqualTo(200);
        assertThat(departmentOf(alice.getId())).as("停用的員工保留部門").isEqualTo(id);
        JsonNode list = carolB.get(DEPARTMENTS).json().get("departments");
        assertThat(list.get(0).get("activeEmployeeCount").asLong()).isZero();
    }

    // ================================================================ 員工：建立

    @Test
    @DisplayName("O-05 建立員工：正規化員工編號與帳號；新員工可用初始密碼登入、打卡、填工時；回應與歷程都沒有密碼；密碼不 trim")
    void createEmployeeCanLogIn() {
        long dept = createDept("PROJECT", "專案服務");
        TestBrowser carolB = loginAs("carol");
        String initialPassword = " test-only Init 123 ";

        Reply created = carolB.post(EMPLOYEES, createBody(" t0100 ", " New.User ", " 新同事 ", "EMPLOYEE", dept, bob.getId(), initialPassword));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        JsonNode e = created.json();
        assertThat(e.get("employeeNo").asString()).isEqualTo("T0100");
        assertThat(e.get("username").asString()).isEqualTo("new.user");
        assertThat(e.get("displayName").asString()).isEqualTo("新同事");
        assertThat(e.get("enabled").asBoolean()).isTrue();
        assertThat(e.get("department").get("code").asString()).isEqualTo("PROJECT");
        assertThat(e.get("manager").get("employeeNo").asString()).isEqualTo("T0002");
        assertThat(created.body()).doesNotContain("{bcrypt}").doesNotContainIgnoringCase("password").doesNotContain("Init 123");

        long id = e.get("id").asLong();
        String hash = jdbc.queryForObject("select password_hash from employee where id = ?", String.class, id);
        assertThat(hash).startsWith("{bcrypt}").doesNotContain("Init");
        assertThat(passwordEncoder.matches(initialPassword, hash)).as("原樣雜湊，不 trim").isTrue();
        assertThat(passwordEncoder.matches(initialPassword.trim(), hash)).isFalse();
        String historyText = jdbc.queryForObject("select string_agg(changes, '|') from organization_history where employee_id = ?", String.class, id);
        assertThat(historyText).doesNotContainIgnoringCase("password").doesNotContain("Init 123").doesNotContain("{bcrypt}");

        TestBrowser newcomer = new TestBrowser(port);
        assertThat(newcomer.login("NEW.USER", initialPassword).status()).as("帳號與登入相同的正規化").isEqualTo(200);
        assertThat(newcomer.post(CLOCK_IN, null).status()).isEqualTo(200);
        long projectId = createProject("TP-ORG", "組織測試專案");
        assertThat(addOk(newcomer, "2026-10-01", projectId, "第一天", 60)).isPositive();
        assertThat(new TestBrowser(port).login("new.user", initialPassword.trim()).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("O-06 建立員工的拒絕：格式 400、密碼超過 72 位元組 400、員工編號／帳號重複 409、部門停用 409、主管無效 409、不存在 404")
    void createEmployeeRejections() {
        long dept = createDept("PROJECT", "專案服務");
        long inactive = createDept("OLD", "舊部門");
        TestBrowser carolB = loginAs("carol");
        assertThat(setDeptActive(carolB, inactive, false).status()).isEqualTo(200);
        String pw = "test-only-Passw0rd";
        int before = jdbc.queryForObject("select count(*) from employee", Integer.class);

        assertThat(carolB.post(EMPLOYEES, createBody("X", "ok_user", "名", "EMPLOYEE", null, null, pw)).code()).isEqualTo("INVALID_EMPLOYEE");
        assertThat(carolB.post(EMPLOYEES, createBody("T0200", "bad user", "名", "EMPLOYEE", null, null, pw)).code()).isEqualTo("INVALID_EMPLOYEE");
        assertThat(carolB.post(EMPLOYEES, createBody("T0200", "ok_user", "   ", "EMPLOYEE", null, null, pw)).status()).isEqualTo(400);
        assertThat(carolB.post(EMPLOYEES, createBody("T0200", "ok_user", "名", "BOSS", null, null, pw)).status()).isEqualTo(400);
        assertThat(carolB.post(EMPLOYEES, createBody("T0200", "ok_user", "名", "EMPLOYEE", null, null, null)).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(carolB.post(EMPLOYEES, createBody("T0200", "ok_user", "名", "EMPLOYEE", null, null, "密".repeat(25))).code())
                .as("75 位元組").isEqualTo("VALIDATION_FAILED");
        Reply dupNo = carolB.post(EMPLOYEES, createBody("t0001", "fresh_user", "名", "EMPLOYEE", null, null, pw));
        assertThat(dupNo.status()).isEqualTo(409);
        assertThat(dupNo.code()).isEqualTo("EMPLOYEE_NO_DUPLICATE");
        assertThat(carolB.post(EMPLOYEES, createBody("T0200", "ALICE", "名", "EMPLOYEE", null, null, pw)).code()).isEqualTo("USERNAME_DUPLICATE");
        assertThat(carolB.post(EMPLOYEES, createBody("T0200", "ok_user", "名", "EMPLOYEE", inactive, null, pw)).code()).isEqualTo("DEPARTMENT_INACTIVE");
        assertThat(carolB.post(EMPLOYEES, createBody("T0200", "ok_user", "名", "EMPLOYEE", 999999L, null, pw)).code()).isEqualTo("DEPARTMENT_NOT_FOUND");
        assertThat(carolB.post(EMPLOYEES, createBody("T0200", "ok_user", "名", "EMPLOYEE", dept, alice.getId(), pw)).code()).isEqualTo("MANAGER_INVALID");
        assertThat(carolB.post(EMPLOYEES, createBody("T0200", "ok_user", "名", "EMPLOYEE", dept, 999999L, pw)).code()).isEqualTo("EMPLOYEE_NOT_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from employee", Integer.class)).isEqualTo(before);
        assertThat(orgHistoryRows()).as("只有兩個部門建立＋一次停用").isEqualTo(3);

        assertThat(carolB.post(EMPLOYEES, createBody("T0200", "ok_user", "名", "MANAGER", dept, carol.getId(), "密".repeat(24))).status())
                .as("剛好 72 位元組").isEqualTo(201);
    }

    // ================================================================ 員工：修改

    @Test
    @DisplayName("O-07 修改員工：只改名稱不需原因；改角色／啟用／部門／主管需原因；沒有變更 400；版本 +1；過期版本 409 且資料不變；歷程有前後值")
    void updateEmployee() {
        long dept = createDept("PROJECT", "專案服務");
        TestBrowser carolB = loginAs("carol");

        Reply renamed = form(alice.getId()).displayName("愛麗絲 王").reason(null).submit(carolB);
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.json().get("version").asLong()).isEqualTo(1);

        Reply noReason = form(alice.getId()).department(dept).reason(" ").submit(carolB);
        assertThat(noReason.code()).isEqualTo("REASON_REQUIRED");
        assertThat(form(alice.getId()).role("MANAGER").reason(null).submit(carolB).code()).isEqualTo("REASON_REQUIRED");
        assertThat(form(alice.getId()).submit(carolB).code()).isEqualTo("NO_CHANGE");

        Form loaded = form(alice.getId());
        assertThat(loaded.department(dept).manager(bob.getId()).reason("加入專案組").submit(carolB).status()).isEqualTo(200);
        Reply stale = loaded.displayName("舊畫面").submit(carolB);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("EMPLOYEE_CHANGED");
        assertThat(jdbc.queryForObject("select display_name from employee where id = ?", String.class, alice.getId())).isEqualTo("愛麗絲 王");
        assertThat(empVersion(alice.getId())).isEqualTo(2);

        JsonNode detail = carolB.get(EMPLOYEES + "/" + alice.getId()).json();
        assertThat(detail.get("department").get("code").asString()).isEqualTo("PROJECT");
        assertThat(detail.get("manager").get("displayName").asString()).isEqualTo("鮑伯");
        assertThat(carolB.get(EMPLOYEES + "/" + bob.getId()).json().get("subordinateCount").asLong()).isEqualTo(1);

        JsonNode entries = carolB.get(EMPLOYEES + "/" + alice.getId() + "/history").json().get("entries");
        assertThat(entries).hasSize(2);
        JsonNode latest = entries.get(0);
        assertThat(latest.get("reason").asString()).isEqualTo("加入專案組");
        assertThat(latest.get("actor").get("displayName").asString()).isEqualTo("卡蘿");
        String changes = latest.get("changes").toString();
        assertThat(changes).contains("\"field\":\"department\"").contains("PROJECT 專案服務")
                .contains("\"field\":\"manager\"").contains("T0002 鮑伯").contains("\"before\":null");
        assertThat(entries.get(1).get("changes").toString()).contains("愛麗絲").contains("愛麗絲 王");
        assertThat(carolB.put(EMPLOYEES + "/999999", form(alice.getId()).body()).code()).isEqualTo("EMPLOYEE_NOT_FOUND");
    }

    @Test
    @DisplayName("O-08 ADMIN 本人：可改自己的名稱、部門、主管；不能停用自己或移除自己的 ADMIN（403），資料不變")
    void adminSelfRules() {
        long dept = createDept("MGMT", "管理");
        TestBrowser carolB = loginAs("carol");
        assertThat(form(carol.getId()).displayName("卡蘿 Chen").department(dept).manager(dave.getId()).submit(carolB).status()).isEqualTo(200);

        Reply disableSelf = form(carol.getId()).enabled(false).submit(carolB);
        assertThat(disableSelf.status()).isEqualTo(403);
        assertThat(disableSelf.code()).isEqualTo("SELF_ADMIN_CHANGE_FORBIDDEN");
        assertThat(form(carol.getId()).role("MANAGER").submit(carolB).code()).isEqualTo("SELF_ADMIN_CHANGE_FORBIDDEN");
        assertThat(jdbc.queryForObject("select role from employee where id = ?", String.class, carol.getId())).isEqualTo("ADMIN");
        assertThat(carolB.get(EMPLOYEES).status()).as("自己的 Session 仍有效").isEqualTo(200);
    }

    @Test
    @DisplayName("O-09 最後一位 ADMIN：其他 ADMIN 已被降級後，依舊身份送出的降級 → 409 LAST_ADMIN（Service 鎖內重新計算）")
    void lastAdminIsProtected() {
        TestBrowser carolB = loginAs("carol");
        EmployeeUserDetails daveSnapshot = new EmployeeUserDetails(dave); // dave 的請求已通過 Filter（當時還是 ADMIN）
        assertThat(form(dave.getId()).role("MANAGER").reason("輪調").submit(carolB).status()).isEqualTo(200);

        Form demoteCarol = form(carol.getId()).role("MANAGER").reason("互相降級");
        assertThatThrownBy(() -> employeeAdminService.update(daveSnapshot, carol.getId(), new UpdateEmployeeRequest(
                demoteCarol.displayName(), Role.MANAGER, true, null, null, demoteCarol.expectedVersion(), "互相降級")))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("LAST_ADMIN"));
        assertThatThrownBy(() -> employeeAdminService.update(daveSnapshot, carol.getId(), new UpdateEmployeeRequest(
                demoteCarol.displayName(), Role.ADMIN, false, null, null, demoteCarol.expectedVersion(), "停用")))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("LAST_ADMIN"));
        assertThat(jdbc.queryForObject("select count(*) from employee where role = 'ADMIN' and enabled", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("O-10 主管規則：自己 400；EMPLOYEE／停用者不能當主管 409；直接與間接循環 409；跨部門主管可以")
    void managerRules() {
        long a = createDept("DEPT-A", "甲");
        long b = createDept("DEPT-B", "乙");
        Employee mia = extra("T0010", "mia", Role.MANAGER);
        Employee off = extra("T0011", "off", Role.MANAGER);
        TestBrowser carolB = loginAs("carol");
        assertThat(form(off.getId()).enabled(false).submit(carolB).status()).isEqualTo(200);

        Reply self = form(bob.getId()).manager(bob.getId()).submit(carolB);
        assertThat(self.status()).isEqualTo(400);
        assertThat(self.code()).isEqualTo("MANAGER_SELF");
        assertThat(form(bob.getId()).manager(alice.getId()).submit(carolB).code()).isEqualTo("MANAGER_INVALID");
        assertThat(form(bob.getId()).manager(off.getId()).submit(carolB).code()).isEqualTo("MANAGER_INVALID");
        assertThat(form(bob.getId()).manager(999999L).submit(carolB).code()).isEqualTo("EMPLOYEE_NOT_FOUND");

        // 跨部門：alice 在甲、bob 在乙，bob 仍可當 alice 的主管
        assertThat(form(alice.getId()).department(a).manager(bob.getId()).submit(carolB).status()).isEqualTo(200);
        assertThat(form(bob.getId()).department(b).manager(mia.getId()).submit(carolB).status()).isEqualTo(200);
        // bob → mia；mia → bob 是直接循環
        Reply direct = form(mia.getId()).manager(bob.getId()).submit(carolB);
        assertThat(direct.status()).isEqualTo(409);
        assertThat(direct.code()).isEqualTo("MANAGER_CYCLE");
        // mia → dave；dave → bob 是間接循環（bob → mia → dave → bob）
        assertThat(form(mia.getId()).manager(dave.getId()).submit(carolB).status()).isEqualTo(200);
        assertThat(form(dave.getId()).manager(bob.getId()).submit(carolB).code()).isEqualTo("MANAGER_CYCLE");
        assertThat(managerOf(dave.getId())).isNull();
        assertThat(form(dave.getId()).manager(carol.getId()).submit(carolB).status()).as("不形成循環的可以").isEqualTo(200);
        // 移除主管
        assertThat(form(alice.getId()).manager(null).submit(carolB).status()).isEqualTo(200);
        assertThat(managerOf(alice.getId())).isNull();

        JsonNode options = carolB.get(EMPLOYEES + "/manager-options").json().get("options");
        List<String> numbers = options.valueStream().map(o -> o.get("employeeNo").asString()).toList();
        assertThat(numbers).containsExactly("T0002", "T0003", "T0004", "T0010").doesNotContain("T0001", "T0011");
    }

    @Test
    @DisplayName("O-11 仍有部屬的人（含已停用的部屬）不能被停用或改成 EMPLOYEE（409 附人數）；改成 ADMIN 可以；不自動改派")
    void managerWithSubordinatesCannotBeDisabledOrDemoted() {
        assignManager(alice.getId(), bob.getId());
        TestBrowser carolB = loginAs("carol");
        assertThat(form(alice.getId()).enabled(false).submit(carolB).status()).isEqualTo(200);

        Reply disable = form(bob.getId()).enabled(false).submit(carolB);
        assertThat(disable.status()).isEqualTo(409);
        assertThat(disable.code()).isEqualTo("MANAGER_HAS_SUBORDINATES");
        assertThat(disable.json().get("subordinateCount").asLong()).isEqualTo(1);
        assertThat(form(bob.getId()).role("EMPLOYEE").submit(carolB).code()).isEqualTo("MANAGER_HAS_SUBORDINATES");
        assertThat(managerOf(alice.getId())).as("沒有自動改派").isEqualTo(bob.getId());

        assertThat(form(bob.getId()).role("ADMIN").submit(carolB).status()).isEqualTo(200);
        assertThat(form(alice.getId()).manager(null).submit(carolB).status()).isEqualTo(200);
        assertThat(form(bob.getId()).enabled(false).submit(carolB).status()).as("沒有部屬後可以停用").isEqualTo(200);
    }

    @Test
    @DisplayName("O-12 部門規則：新指派的部門必須啟用；重新啟用員工時部門已停用 → 409，除非同時改派到啟用部門或移除部門")
    void departmentRulesOnEmployee() {
        long old = createDept("OLD", "舊部門");
        long fresh = createDept("NEW", "新部門");
        TestBrowser carolB = loginAs("carol");
        assertThat(form(alice.getId()).department(old).submit(carolB).status()).isEqualTo(200);
        assertThat(form(alice.getId()).enabled(false).submit(carolB).status()).isEqualTo(200);
        assertThat(setDeptActive(carolB, old, false).status()).isEqualTo(200);

        assertThat(form(bob.getId()).department(old).submit(carolB).code()).as("新指派到停用部門").isEqualTo("DEPARTMENT_INACTIVE");
        assertThat(form(alice.getId()).displayName("停用中改名").reason(null).submit(carolB).status()).as("停用員工保留停用部門也能改名").isEqualTo(200);
        Reply reenable = form(alice.getId()).enabled(true).submit(carolB);
        assertThat(reenable.status()).isEqualTo(409);
        assertThat(reenable.code()).isEqualTo("DEPARTMENT_INACTIVE");
        assertThat(form(alice.getId()).enabled(true).department(fresh).submit(carolB).status()).as("同時改派").isEqualTo(200);
        assertThat(departmentOf(alice.getId())).isEqualTo(fresh);

        assertThat(form(alice.getId()).enabled(false).department(old).submit(carolB).code()).as("停用員工也不能新指派到停用部門")
                .isEqualTo("DEPARTMENT_INACTIVE");
        assertThat(form(alice.getId()).enabled(false).submit(carolB).status()).isEqualTo(200);
        assertThat(form(alice.getId()).enabled(true).department(null).submit(carolB).status()).as("同時移除部門").isEqualTo(200);
    }

    @Test
    @DisplayName("O-13 停用或改角色後，對方的舊 Session 在下一個受保護請求 → 401；重新啟用的員工要重新登入")
    void disableOrRoleChangeInvalidatesOldSession() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser bobB = loginAs("bob");
        assertThat(aliceB.get(TODAY).status()).isEqualTo(200);
        TestBrowser carolB = loginAs("carol");

        assertThat(form(alice.getId()).enabled(false).submit(carolB).status()).isEqualTo(200);
        assertThat(form(bob.getId()).role("ADMIN").submit(carolB).status()).isEqualTo(200);

        assertThat(aliceB.get(TODAY).status()).isEqualTo(401);
        assertThat(aliceB.post(CLOCK_IN, null).status()).isEqualTo(401);
        assertThat(bobB.get(TODAY).status()).isEqualTo(401);
        assertThat(attendanceRows()).isZero();
        assertThat(new TestBrowser(port).login("alice", PASSWORD).status()).as("停用後不能登入").isEqualTo(401);

        assertThat(form(alice.getId()).enabled(true).submit(carolB).status()).isEqualTo(200);
        assertThat(aliceB.get(TODAY).status()).as("舊 Session 已作廢").isEqualTo(401);
        assertThat(loginAs("alice").get(TODAY).status()).isEqualTo(200);
        assertThat(loginAs("bob").get(EMPLOYEES).status()).as("新角色生效").isEqualTo(200);
    }

    @Test
    @DisplayName("O-14 清單：關鍵字比對名稱／帳號／員工編號（不分大小寫、% 與 _ 視為一般字元）、狀態篩選、分頁上限 50、依員工編號排序")
    void searchEmployees() {
        for (int i = 0; i < 55; i++) {
            extra(String.format("P%04d", i), "bulk" + i, Role.EMPLOYEE);
        }
        extra("Q-1", "under_score", Role.EMPLOYEE);
        TestBrowser carolB = loginAs("carol");
        assertThat(form(alice.getId()).enabled(false).submit(carolB).status()).isEqualTo(200);

        JsonNode all = carolB.get(EMPLOYEES).json();
        assertThat(all.get("total").asLong()).isEqualTo(60);
        assertThat(all.get("size").asInt()).isEqualTo(20);
        assertThat(all.get("items")).hasSize(20);
        assertThat(all.get("items").get(0).get("employeeNo").asString()).isEqualTo("P0000");
        JsonNode capped = carolB.get(EMPLOYEES + "?size=500&page=1").json();
        assertThat(capped.get("size").asInt()).isEqualTo(50);
        assertThat(capped.get("items")).hasSize(10);

        assertThat(carolB.get(EMPLOYEES + "?q=%E6%84%9B%E9%BA%97").json().get("total").asLong()).as("名稱「愛麗」").isEqualTo(1);
        assertThat(carolB.get(EMPLOYEES + "?q=BOB").json().get("total").asLong()).as("帳號不分大小寫").isEqualTo(1);
        assertThat(carolB.get(EMPLOYEES + "?q=t000").json().get("total").asLong()).as("員工編號").isEqualTo(4);
        assertThat(carolB.get(EMPLOYEES + "?q=_").json().get("total").asLong()).as("_ 是一般字元").isEqualTo(1);
        assertThat(carolB.get(EMPLOYEES + "?q=%25").json().get("total").asLong()).as("% 是一般字元").isZero();
        assertThat(carolB.get(EMPLOYEES + "?status=INACTIVE").json().get("total").asLong()).isEqualTo(1);
        assertThat(carolB.get(EMPLOYEES + "?status=ACTIVE").json().get("total").asLong()).isEqualTo(59);
        assertThat(carolB.get(EMPLOYEES + "?status=WHATEVER").status()).isEqualTo(400);
        assertThat(carolB.get(EMPLOYEES + "?q=nobody-here").json().get("items")).isEmpty();
    }

    @Test
    @DisplayName("O-15 沒有部門與主管的員工照常登入、打卡、填工時（組織資料不是使用其他功能的前提）")
    void unassignedEmployeesStillWork() {
        assertThat(departmentOf(alice.getId())).isNull();
        assertThat(managerOf(alice.getId())).isNull();
        long projectId = createProject("TP-FREE", "未指派也能填");
        TestBrowser aliceB = loginAs("alice");
        assertThat(aliceB.post(CLOCK_IN, null).status()).isEqualTo(200);
        assertThat(addOk(aliceB, "2026-10-01", projectId, "未指派部門", 30)).isPositive();
        TestBrowser bobB = loginAs("bob");
        assertThat(bobB.post(CLOCK_IN, null).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("O-16 組織歷程只能新增：UPDATE／DELETE 被資料庫拒絕；資料庫也擋員工自己當自己的主管")
    void historyIsAppendOnly() {
        createDept("AUDIT", "稽核");
        assertThatThrownBy(() -> jdbc.update("update organization_history set reason = 'x'")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from organization_history")).isInstanceOf(DataAccessException.class);
        assertThat(orgHistoryRows()).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update employee set manager_id = id where id = ?", alice.getId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update department set code = 'lower' where code = 'AUDIT'")).isInstanceOf(DataAccessException.class);
    }
}
