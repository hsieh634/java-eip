package com.renewflow.eip.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 原因：Spring Security 6 起 CSRF token 是「延遲載入」——沒人讀它就不會產生，Cookie 也就不會寫出。
 * SPA 第一次載入時沒有人讀它，會拿不到 token。這個 Filter 每個請求都讀一次，確保 XSRF-TOKEN Cookie 一定存在
 * （即使該請求最後是 401）。
 */
final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken(); // 讀取的副作用：觸發產生並寫入 Cookie
        }
        chain.doFilter(request, response);
    }
}
