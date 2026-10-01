package com.renewflow.eip.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字串以 UTF-8 編碼後的「位元組數」不得超過上限。
 *
 * <p>為什麼不用內建的 {@code @Size(max = 72)}：{@code @Size} 數的是字元，但 bcrypt 只使用密碼前 72 個
 * <b>位元組</b>。一個中文字是 3 位元組、一個 emoji 是 4 位元組，所以 25 個中文字（75 位元組）能通過
 * {@code @Size(max = 72)}，卻會和前 24 個字產生相同的 bcrypt 結果。
 *
 * <p>{@code null} 視為通過（是否必填交給 {@code @NotBlank}）；不 trim、不截斷。
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {

    /** 位元組數上限（含）。 */
    int value();

    String message() default "must not exceed {value} bytes in UTF-8";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
