package com.renewflow.eip.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登入請求 DTO。這裡只驗證「格式」；帳密是否正確是業務判斷，由 Service／Security 處理。
 * 密碼上限 72：bcrypt 只看前 72 bytes，放任更長的輸入會讓「不同密碼其實等價」。
 */
public record LoginRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Size(max = 72) String password) {

    /** 避免密碼意外出現在 log 或例外訊息中。 */
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=***]";
    }
}
