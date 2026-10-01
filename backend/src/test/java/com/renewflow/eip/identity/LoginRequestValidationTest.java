package com.renewflow.eip.identity;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R4：密碼上限是 72 UTF-8 bytes（不是 72 個字元）。純單元測試，不啟動 Spring、不需要資料庫。
 * UTF-8 位元組數：ASCII 1、中文 3、emoji（如 😀）4。
 */
class LoginRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static Set<ConstraintViolation<LoginRequest>> validate(String password) {
        return validator.validate(new LoginRequest("alice", password));
    }

    private static boolean passwordRejected(String password) {
        return validate(password).stream().anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    @DisplayName("R4 ASCII：72 bytes 通過、73 bytes 拒絕")
    void ascii() {
        assertThat(bytes("a".repeat(72))).isEqualTo(72);
        assertThat(passwordRejected("a".repeat(72))).isFalse();
        assertThat(passwordRejected("a".repeat(73))).isTrue();
    }

    @Test
    @DisplayName("R4 中文：24 字＝72 bytes 通過、25 字＝75 bytes 拒絕（舊的 @Size(max=72) 會放行這個）")
    void chinese() {
        String seventyTwo = "中".repeat(24);
        String seventyFive = seventyTwo + "文";
        assertThat(bytes(seventyTwo)).isEqualTo(72);
        assertThat(bytes(seventyFive)).isEqualTo(75);
        assertThat(seventyFive.length()).as("字元數只有 25，舊規則看不出問題").isLessThanOrEqualTo(72);

        assertThat(passwordRejected(seventyTwo)).isFalse();
        assertThat(passwordRejected(seventyFive)).isTrue();
    }

    @Test
    @DisplayName("R4 字元數在 72 內、bytes 卻超過：36 個中文字（36 字元／108 bytes）拒絕")
    void manyChineseCharactersUnderCharacterLimit() {
        assertThat(passwordRejected("中".repeat(36))).isTrue();
    }

    @Test
    @DisplayName("R4 emoji（4 bytes、2 個 UTF-16 字元）：18 個＝72 bytes 通過；再加 1 個 ASCII ＝73 bytes 拒絕")
    void emojiBoundary() {
        String eighteen = "😀".repeat(18);
        assertThat(bytes(eighteen)).isEqualTo(72);
        assertThat(eighteen.length()).as("UTF-16 字元數是 36").isEqualTo(36);
        assertThat(passwordRejected(eighteen)).isFalse();
        assertThat(passwordRejected(eighteen + "a")).isTrue();
        assertThat(passwordRejected("😀".repeat(19))).isTrue();
    }

    @Test
    @DisplayName("R4 混合：17 個 emoji（68）＋1 個中文（3）＋1 個 ASCII ＝72 通過；再加 1 個 ASCII ＝73 拒絕")
    void mixedBoundary() {
        String seventyTwo = "😀".repeat(17) + "中" + "a";
        assertThat(bytes(seventyTwo)).isEqualTo(72);
        assertThat(passwordRejected(seventyTwo)).isFalse();
        assertThat(passwordRejected(seventyTwo + "a")).isTrue();
    }

    @Test
    @DisplayName("R4 保留既有政策：空字串、全空白、null 仍被拒絕（必填）；前後空白不被 trim")
    void requiredPolicyUnchanged() {
        assertThat(passwordRejected("")).isTrue();
        assertThat(passwordRejected("   ")).isTrue();
        assertThat(passwordRejected(null)).isTrue();
        // 不 trim：含前後空白的密碼照原樣計算 bytes，72 bytes 的邊界包含空白
        assertThat(passwordRejected(" " + "a".repeat(70) + " ")).isFalse();
        assertThat(passwordRejected(" " + "a".repeat(71) + " ")).isTrue();
    }

    @Test
    @DisplayName("R4 帳號欄位政策不變：必填、最多 50 字元")
    void usernamePolicyUnchanged() {
        assertThat(validator.validate(new LoginRequest("", "x"))).isNotEmpty();
        assertThat(validator.validate(new LoginRequest("a".repeat(50), "x"))).isEmpty();
        assertThat(validator.validate(new LoginRequest("a".repeat(51), "x"))).isNotEmpty();
    }
}
