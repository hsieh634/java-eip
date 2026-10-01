package com.renewflow.eip.identity;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import com.renewflow.eip.support.TestData;
import com.renewflow.eip.support.TestFlywayConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 啟動完整應用（真的 Tomcat＋真的 PostgreSQL 測試庫＋完整 Security Filter Chain），以瀏覽器方式驗證 T02。
 * 編號對應 docs/renewflow/t01-t02/specification.md。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestFlywayConfig.class)
class AuthFlowIntegrationTest {

    // 測試固定資料，非任何真實帳號密碼
    private static final String PASSWORD = "test-only-Passw0rd";
    private static final String LOGIN_OK = "{\"username\":\"alice\",\"password\":\"" + PASSWORD + "\"}";

    @LocalServerPort
    int port;
    @Autowired
    EmployeeRepository employeeRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    JdbcTemplate jdbcTemplate;

    TestBrowser browser;

    @BeforeEach
    void setUp() {
        TestData.reset(jdbcTemplate); // 先刪出勤再刪員工（外鍵）
        employeeRepository.save(new Employee("T0001", "alice", "愛麗絲", passwordEncoder.encode(PASSWORD), Role.MANAGER));
        browser = new TestBrowser(port);
    }

    /** 模擬 SPA 載入頁面：先打一次 /me 取得 XSRF-TOKEN Cookie。 */
    private void loadPage() {
        Reply me = browser.get("/api/auth/me");
        assertThat(me.status()).isEqualTo(401);
        assertThat(browser.cookie("XSRF-TOKEN")).as("未登入的回應也要帶 CSRF token").isNotBlank();
    }

    private Reply login() {
        loadPage();
        return browser.post("/api/auth/login", LOGIN_OK);
    }

    @Test
    @DisplayName("S-02 乾淨資料庫由 migration 建立 employee 表，且 V1 成功套用")
    void migrationBuiltSchema() {
        Integer tables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'employee'", Integer.class);
        Boolean v1Success = jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '1'", Boolean.class);
        assertThat(tables).isEqualTo(1);
        assertThat(v1Success).isTrue();
    }

    @Test
    @DisplayName("S-03 登入成功：200、回傳使用者、JSESSIONID 為 HttpOnly、回應不含密碼雜湊")
    void loginSuccess() {
        Reply reply = login();

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("username").asString()).isEqualTo("alice");
        assertThat(reply.json().get("role").asString()).isEqualTo("MANAGER");
        assertThat(reply.body()).doesNotContain("password").doesNotContain("bcrypt");
        assertThat(reply.setCookie("JSESSIONID")).containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax");
        assertThat(reply.setCookie("XSRF-TOKEN")).doesNotContainIgnoringCase("HttpOnly");
    }

    @Test
    @DisplayName("S-04 密碼錯誤 → 401 BAD_CREDENTIALS")
    void wrongPassword() {
        loadPage();
        Reply reply = browser.post("/api/auth/login", "{\"username\":\"alice\",\"password\":\"wrong\"}");
        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.code()).isEqualTo("BAD_CREDENTIALS");
    }

    @Test
    @DisplayName("S-05 帳號不存在 → 與密碼錯誤完全相同的回應（不洩漏帳號是否存在）")
    void unknownUserLooksLikeWrongPassword() {
        loadPage();
        Reply wrong = browser.post("/api/auth/login", "{\"username\":\"alice\",\"password\":\"wrong\"}");
        Reply unknown = browser.post("/api/auth/login", "{\"username\":\"nobody\",\"password\":\"wrong\"}");
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(unknown.body()).isEqualTo(wrong.body());
    }

    @Test
    @DisplayName("S-06 帳號停用 → 401 BAD_CREDENTIALS")
    void disabledAccountCannotLogin() {
        Employee alice = employeeRepository.findByUsername("alice").orElseThrow();
        alice.setEnabled(false);
        employeeRepository.save(alice);

        Reply reply = login();
        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.code()).isEqualTo("BAD_CREDENTIALS");
    }

    @Test
    @DisplayName("S-07 欄位空白 → 400 VALIDATION_FAILED")
    void blankFieldsRejected() {
        loadPage();
        Reply reply = browser.post("/api/auth/login", "{\"username\":\"\",\"password\":\"\"}");
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("S-08 未登入取 /me → 401 UNAUTHENTICATED")
    void meRequiresLogin() {
        Reply reply = browser.get("/api/auth/me");
        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.code()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("S-09 未登入存取不存在的 /api 路徑 → 401（不洩漏路徑是否存在）；登入後才是 404")
    void unknownApiPath() {
        assertThat(browser.get("/api/does-not-exist").status()).isEqualTo(401);
        login();
        Reply afterLogin = browser.get("/api/does-not-exist");
        assertThat(afterLogin.status()).isEqualTo(404);
        assertThat(afterLogin.code()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("S-10 登入後 /me 回傳自己")
    void meAfterLogin() {
        login();
        Reply me = browser.get("/api/auth/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().get("username").asString()).isEqualTo("alice");
        assertThat(me.json().get("employeeNo").asString()).isEqualTo("T0001");
    }

    @Test
    @DisplayName("S-11 登出後身份失效：204，且舊 Session 不能再使用")
    void logoutInvalidatesSession() {
        login();
        // 保留登出前的 Session ID，稍後模擬「有人拿著舊 Session ID 回來」
        String oldSession = "JSESSIONID=" + browser.cookie("JSESSIONID");
        assertThat(browser.getWithRawCookie("/api/auth/me", oldSession).status()).as("登出前舊 ID 有效").isEqualTo(200);

        Reply logout = browser.post("/api/auth/logout", null);
        assertThat(logout.status()).isEqualTo(204);

        assertThat(browser.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(browser.getWithRawCookie("/api/auth/me", oldSession).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("S-12 沒有 CSRF token 的登入 → 403 CSRF_REJECTED")
    void loginWithoutCsrfRejected() {
        loadPage();
        Reply reply = browser.postWithCsrf("/api/auth/login", LOGIN_OK, null);
        assertThat(reply.status()).isEqualTo(403);
        assertThat(reply.code()).isEqualTo("CSRF_REJECTED");
        assertThat(browser.get("/api/auth/me").status()).as("沒有成功登入").isEqualTo(401);
    }

    @Test
    @DisplayName("S-12b CSRF token 錯誤的登入 → 403")
    void loginWithWrongCsrfRejected() {
        loadPage();
        Reply reply = browser.postWithCsrf("/api/auth/login", LOGIN_OK, "not-the-token");
        assertThat(reply.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("S-13 已登入但缺 CSRF 的登出 → 403，且仍保持登入")
    void logoutWithoutCsrfRejected() {
        login();
        Reply reply = browser.postWithCsrf("/api/auth/logout", null, null);
        assertThat(reply.status()).isEqualTo(403);
        assertThat(reply.code()).isEqualTo("CSRF_REJECTED");
        assertThat(browser.get("/api/auth/me").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("S-14 登入與登出都會更新 CSRF token；登出後可直接再次登入")
    void csrfTokenRotatesOnLoginAndLogout() {
        loadPage();
        String beforeLogin = browser.cookie("XSRF-TOKEN");

        assertThat(browser.post("/api/auth/login", LOGIN_OK).status()).isEqualTo(200);
        String afterLogin = browser.cookie("XSRF-TOKEN");
        assertThat(afterLogin).isNotBlank().isNotEqualTo(beforeLogin);

        assertThat(browser.post("/api/auth/logout", null).status()).isEqualTo(204);
        String afterLogout = browser.cookie("XSRF-TOKEN");
        assertThat(afterLogout).isNotBlank().isNotEqualTo(afterLogin);

        // 不需要再打任何 GET，直接用登出時發的新 token 再登入
        assertThat(browser.post("/api/auth/login", LOGIN_OK).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("S-15 登入換發 Session ID，舊 ID 無法取得身份（防 Session Fixation）")
    void sessionIdChangesOnLogin() {
        login();
        String firstSession = browser.cookie("JSESSIONID");

        // 帶著既有 Session 再登入一次
        Reply second = browser.post("/api/auth/login", LOGIN_OK);
        assertThat(second.status()).isEqualTo(200);
        String secondSession = browser.cookie("JSESSIONID");

        assertThat(secondSession).isNotEqualTo(firstSession);
        assertThat(browser.getWithRawCookie("/api/auth/me", "JSESSIONID=" + firstSession).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("S-16 登入後員工被停用 → 既有 Session 呼叫 /me 立即 401")
    void disabledAfterLoginLosesAccess() {
        login();
        assertThat(browser.get("/api/auth/me").status()).isEqualTo(200);

        Employee alice = employeeRepository.findByUsername("alice").orElseThrow();
        alice.setEnabled(false);
        employeeRepository.save(alice);

        assertThat(browser.get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    @DisplayName("資料庫約束：username 必須全小寫、角色必須是合法值")
    void databaseConstraintsEnforced() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into employee(employee_no, username, display_name, password_hash, role) "
                        + "values ('X1','Bob','b','h','EMPLOYEE')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into employee(employee_no, username, display_name, password_hash, role) "
                        + "values ('X2','bob','b','h','BOSS')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
