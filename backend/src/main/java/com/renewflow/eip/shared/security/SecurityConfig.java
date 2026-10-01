package com.renewflow.eip.shared.security;

import com.renewflow.eip.identity.AccountStatusService;
import com.renewflow.eip.identity.EmployeeUserDetailsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * 安全規則的唯一入口。閱讀順序：csrf → 授權規則 → 登出 → 例外處理。
 * 請求先經過這條 Filter Chain，通過後才會到 Controller。
 */
@Configuration
class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        // 預設 bcrypt，雜湊前綴 {bcrypt} 記錄了演算法；日後換演算法時舊密碼仍可驗證
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(EmployeeUserDetailsService userDetailsService,
                                                PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository(
            @Value("${server.servlet.session.cookie.secure:true}") boolean cookieSecure) {
        // httpOnly=false：SPA 必須用 JavaScript 讀這個 Cookie 再放進標頭。
        // 它只是 CSRF token，不是登入憑證；登入憑證 JSESSIONID 仍是 HttpOnly。
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.secure(cookieSecure).sameSite("Lax"));
        return repository;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            CsrfTokenRepository csrfTokenRepository,
                                            SecurityContextRepository securityContextRepository,
                                            AccountStatusService accountStatusService,
                                            ObjectMapper objectMapper) {
        JsonSecurityHandlers handlers = new JsonSecurityHandlers(objectMapper, csrfTokenRepository);

        http
                // 寫入請求（POST/PUT/PATCH/DELETE）必須帶 X-XSRF-TOKEN 標頭
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)

                .securityContext(context -> context.securityContextRepository(securityContextRepository))

                // 所有受保護請求共用的最新帳號有效性檢查；放在授權之前，無效帳號不會進入任何 Controller
                .addFilterBefore(new ActiveAccountFilter(accountStatusService, handlers), AuthorizationFilter.class)

                // 原因：預設會把「被擋下的原請求」存進 Session 以便登入後導回；
                // API 不需要這個功能，且會讓每個匿名 401 都建立一個無用的 Session
                .requestCache(AbstractHttpConfigurer::disable)

                // 自己做 JSON 登入，不使用表單登入／Basic 驗證
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/logout").permitAll()
                        // 管理 API：只有 ADMIN（後端驗證；前端隱藏入口不是安全機制）。角色由 ActiveAccountFilter 確認為最新
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        // 沒有明確開放的一律拒絕（預設拒絕比預設允許安全）
                        .anyRequest().denyAll())

                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout") // 預設只接受 POST
                        .logoutSuccessHandler(handlers.logoutSuccessHandler()))

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()));

        return http.build();
    }
}
