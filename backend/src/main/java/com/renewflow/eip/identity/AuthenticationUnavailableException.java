package com.renewflow.eip.identity;

/**
 * 認證「機制」本身故障（例如查詢使用者時資料庫連不上），與「帳密不對」是兩回事。
 * 它刻意不是 Spring Security 的 AuthenticationException：那一族例外最後都會被 Security 轉成 401，
 * 會把系統故障偽裝成使用者輸入錯誤。這個例外會走到 GlobalExceptionHandler，回 500 INTERNAL_ERROR。
 */
public class AuthenticationUnavailableException extends RuntimeException {

    public AuthenticationUnavailableException(Throwable cause) {
        super("authentication backend failure", cause);
    }
}
