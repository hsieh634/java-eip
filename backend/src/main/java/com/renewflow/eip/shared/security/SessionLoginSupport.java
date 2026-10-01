package com.renewflow.eip.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * 「驗證成功之後」的瀏覽器端收尾。Spring 內建的表單登入 Filter 會替你做這幾件事；
 * 我們自己寫 JSON 登入，就必須自己做齊，缺任何一步都有後果：
 * <ol>
 *   <li>換發 Session ID —— 否則有 Session Fixation（攻擊者先植入已知 Session ID，等受害者登入後共用）</li>
 *   <li>輪替 CSRF token —— 登入前後不沿用同一個 token</li>
 *   <li>把登入者存進 Session —— 否則下一個請求又變回未登入</li>
 * </ol>
 */
@Component
public class SessionLoginSupport {

    private final SecurityContextHolderStrategy contextHolderStrategy = SecurityContextHolder.getContextHolderStrategy();
    private final SessionAuthenticationStrategy sessionFixationStrategy = new ChangeSessionIdAuthenticationStrategy();
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;

    public SessionLoginSupport(SecurityContextRepository securityContextRepository,
                               CsrfTokenRepository csrfTokenRepository) {
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    public void establish(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        // 1. 若請求已帶有 Session，換成新的 ID（沒有 Session 時此策略不會做事）
        sessionFixationStrategy.onAuthentication(authentication, request, response);

        // 2. 輪替 CSRF token：直接產生新值並寫入 Cookie
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);

        // 3. 存入 SecurityContext（此呼叫會在需要時建立 Session，並讓回應帶上 JSESSIONID）
        SecurityContext context = contextHolderStrategy.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolderStrategy.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
