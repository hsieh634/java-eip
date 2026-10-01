package com.renewflow.eip.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

/** {@link MaxUtf8Bytes} 的實作。計算方式與 Spring Security 的 BCrypt 相同：{@code getBytes(UTF_8)}。 */
public class MaxUtf8BytesValidator implements ConstraintValidator<MaxUtf8Bytes, CharSequence> {

    private int maxBytes;

    @Override
    public void initialize(MaxUtf8Bytes constraint) {
        this.maxBytes = constraint.value();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        // 快速判斷：每個 UTF-16 字元編碼後至少 1 位元組，字元數已超過上限就不必再編碼
        // （避免為了一個超大請求配置一份同樣大的位元組陣列）
        if (value.length() > maxBytes) {
            return false;
        }
        return value.toString().getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }
}
