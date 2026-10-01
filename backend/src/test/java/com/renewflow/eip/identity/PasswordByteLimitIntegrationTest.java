package com.renewflow.eip.identity;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import com.renewflow.eip.support.TestFlywayConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R4 端到端：真的 HTTP、真的 bcrypt、真的資料庫。
 * 重現審查報告的情境：資料庫中的密碼是 24 個「中」（72 bytes），
 * 舊規則下輸入「24 個中＋文」（75 bytes）也能登入成功，因為 bcrypt 只看前 72 bytes。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestFlywayConfig.class)
class PasswordByteLimitIntegrationTest {

    private static final String CHINESE_72_BYTES = "中".repeat(24);
    private static final String ASCII_72_BYTES = "p".repeat(72);

    @LocalServerPort
    int port;
    @Autowired
    EmployeeRepository employeeRepository;
    @Autowired
    PasswordEncoder passwordEncoder;

    TestBrowser browser;

    @BeforeEach
    void setUp() {
        employeeRepository.deleteAll();
        employeeRepository.save(new Employee("T0001", "chinese", "中文密碼", passwordEncoder.encode(CHINESE_72_BYTES), Role.EMPLOYEE));
        employeeRepository.save(new Employee("T0002", "ascii", "ASCII 密碼", passwordEncoder.encode(ASCII_72_BYTES), Role.EMPLOYEE));
        browser = new TestBrowser(port);
        assertThat(browser.get("/api/auth/me").status()).isEqualTo(401);
    }

    private Reply login(String username, String password) {
        return browser.post("/api/auth/login", "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    @Test
    @DisplayName("R4 中文 72 bytes 的正確密碼可以登入")
    void chinese72BytesCanLogin() {
        assertThat(login("chinese", CHINESE_72_BYTES).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("R4 中文 75 bytes（72 bytes 的密碼多加一個字）→ 400，不再因 bcrypt 截斷而登入成功")
    void chinese75BytesRejectedInsteadOfLoggingIn() {
        Reply reply = login("chinese", CHINESE_72_BYTES + "文");

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(browser.get("/api/auth/me").status()).as("沒有因此登入").isEqualTo(401);
    }

    @Test
    @DisplayName("R4 ASCII 72 bytes 可登入、73 bytes → 400")
    void asciiBoundary() {
        assertThat(login("ascii", ASCII_72_BYTES).status()).isEqualTo(200);
        assertThat(browser.post("/api/auth/logout", null).status()).isEqualTo(204);

        Reply tooLong = login("ascii", ASCII_72_BYTES + "p");
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("R4 錯誤訊息只指出欄位名稱，不回傳使用者輸入的密碼")
    void errorDoesNotEchoPassword() {
        String tooLong = "中".repeat(30);
        Reply reply = login("chinese", tooLong);

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.body()).doesNotContain(tooLong).contains("password");
    }
}
