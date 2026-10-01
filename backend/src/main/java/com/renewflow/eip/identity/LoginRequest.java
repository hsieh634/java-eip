package com.renewflow.eip.identity;

import com.renewflow.eip.shared.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登入請求 DTO。這裡只驗證「格式」；帳密是否正確是業務判斷，由 Service／Security 處理。
 *
 * <p>密碼上限是 72 個 UTF-8 <b>位元組</b>（不是 72 個字元）：bcrypt 只使用前 72 位元組，
 * 超過的部分會被忽略，等於讓「不同的密碼」被視為相同。所以超過就在認證之前直接拒絕，
 * 不截斷、不 trim，也不做未規劃的預雜湊。
 */
public record LoginRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @MaxUtf8Bytes(LoginRequest.PASSWORD_MAX_BYTES) String password) {

    /** bcrypt 實際使用的密碼位元組上限。前端 LoginPage 有一份對應的常數，兩邊要一起改。 */
    public static final int PASSWORD_MAX_BYTES = 72;

    /** 避免密碼意外出現在 log 或例外訊息中。 */
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=***]";
    }
}
