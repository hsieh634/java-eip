package com.renewflow.eip.identity;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import com.renewflow.eip.support.TestFlywayConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R2：受控故障測試。把「依帳號取使用者」換成可控的替身，從真的 HTTP 請求一路驗到最終的
 * status 與 JSON code，而不是只驗 Service 拋出什麼例外。
 * （與 {@link AuthFlowIntegrationTest} 的差別：這裡的 EmployeeUserDetailsService 是 Mockito 替身，不碰資料庫。）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestFlywayConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthFailureIntegrationTest {

    private static final String PASSWORD = "test-only-Passw0rd";
    private static final String LOGIN = "{\"username\":\"alice\",\"password\":\"" + PASSWORD + "\"}";

    @MockitoBean
    EmployeeUserDetailsService userDetailsService;

    @LocalServerPort
    int port;
    @Autowired
    PasswordEncoder passwordEncoder;

    TestBrowser browser;

    @BeforeEach
    void setUp() {
        browser = new TestBrowser(port);
        assertThat(browser.get("/api/auth/me").status()).as("取得 CSRF token Cookie").isEqualTo(401);
    }

    @Test
    @DisplayName("R2 認證機制故障（資料庫連不上）→ 500 INTERNAL_ERROR，不是 401；回應不洩漏細節，日誌留有原因")
    void backendFailureIsServerErrorNotUnauthorized(CapturedOutput output) {
        when(userDetailsService.loadUserByUsername(anyString())).thenThrow(
                new DataAccessResourceFailureException("could not connect to jdbc:postgresql://secret-db-host:5432/prod as secret-db-user"));

        Reply reply = browser.post("/api/auth/login", LOGIN);

        assertThat(reply.status()).isEqualTo(500);
        assertThat(reply.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(reply.body())
                .doesNotContain("secret-db-host").doesNotContain("secret-db-user")
                .doesNotContain("jdbc").doesNotContain("SQL").doesNotContain(PASSWORD).doesNotContain("Exception");
        // 沒有因此建立登入狀態
        assertThat(browser.get("/api/auth/me").status()).isEqualTo(401);
        // 可排查：伺服器日誌記錄了錯誤與底層原因（日誌不在回應中）
        assertThat(output.getAll()).contains("Authentication backend failure").contains("secret-db-host");
        assertThat(output.getAll()).as("日誌不含密碼").doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("R2 對照：帳號不存在 → 仍是 401 BAD_CREDENTIALS")
    void unknownUserStillUnauthorized() {
        when(userDetailsService.loadUserByUsername(anyString())).thenThrow(new UsernameNotFoundException("employee not found"));

        Reply reply = browser.post("/api/auth/login", LOGIN);

        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.code()).isEqualTo("BAD_CREDENTIALS");
    }

    @Test
    @DisplayName("R2 對照：密碼錯誤 → 仍是 401 BAD_CREDENTIALS")
    void wrongPasswordStillUnauthorized() {
        when(userDetailsService.loadUserByUsername(anyString())).thenReturn(
                new EmployeeUserDetails(employee(true, "another-password")));

        Reply reply = browser.post("/api/auth/login", LOGIN);

        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.code()).isEqualTo("BAD_CREDENTIALS");
    }

    @Test
    @DisplayName("R2 對照：帳號停用 → 仍是 401 BAD_CREDENTIALS")
    void disabledAccountStillUnauthorized() {
        when(userDetailsService.loadUserByUsername(anyString())).thenReturn(
                new EmployeeUserDetails(employee(false, PASSWORD)));

        Reply reply = browser.post("/api/auth/login", LOGIN);

        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.code()).isEqualTo("BAD_CREDENTIALS");
    }

    @Test
    @DisplayName("R2 對照：缺 CSRF → 仍是 403 CSRF_REJECTED，且不會進到認證")
    void csrfRejectionUnchanged() {
        Reply reply = browser.postWithCsrf("/api/auth/login", LOGIN, null);

        assertThat(reply.status()).isEqualTo(403);
        assertThat(reply.code()).isEqualTo("CSRF_REJECTED");
        verifyNoInteractions(userDetailsService);
    }

    @Test
    @DisplayName("R4 超過 72 UTF-8 bytes 的密碼 → 400，且在認證之前就被拒絕（完全沒有查使用者）")
    void oversizedPasswordRejectedBeforeAuthentication() {
        String seventyFiveBytes = "中".repeat(24) + "文";

        Reply reply = browser.post("/api/auth/login", "{\"username\":\"alice\",\"password\":\"" + seventyFiveBytes + "\"}");

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.code()).isEqualTo("VALIDATION_FAILED");
        verifyNoInteractions(userDetailsService);
    }

    private Employee employee(boolean enabled, String rawPassword) {
        Employee employee = new Employee("T0001", "alice", "愛麗絲", passwordEncoder.encode(rawPassword), Role.EMPLOYEE);
        employee.setEnabled(enabled);
        return employee;
    }
}
