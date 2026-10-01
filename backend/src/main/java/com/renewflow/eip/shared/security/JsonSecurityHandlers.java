package com.renewflow.eip.shared.security;

import com.renewflow.eip.shared.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Security Filter 發生在 Controller 之前，@RestControllerAdvice 管不到這一層，
 * 所以 401／403 要在這裡自己寫成 JSON（否則前端會收到 HTML 或空 body）。
 */
final class JsonSecurityHandlers {

    private final ObjectMapper objectMapper;
    private final CsrfTokenRepository csrfTokenRepository;

    JsonSecurityHandlers(ObjectMapper objectMapper, CsrfTokenRepository csrfTokenRepository) {
        this.objectMapper = objectMapper;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    /** 未登入存取受保護資源 → 401。 */
    AuthenticationEntryPoint entryPoint() {
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException e) ->
                write(response, HttpStatus.UNAUTHORIZED, new ApiError("UNAUTHENTICATED", "請先登入"));
    }

    /** CSRF 驗證失敗或權限不足 → 403，兩者用 code 區分。 */
    AccessDeniedHandler accessDeniedHandler() {
        return (HttpServletRequest request, HttpServletResponse response, AccessDeniedException e) -> {
            if (e instanceof CsrfException) {
                write(response, HttpStatus.FORBIDDEN, new ApiError("CSRF_REJECTED", "安全驗證失敗，請重新整理頁面後再試"));
            } else {
                write(response, HttpStatus.FORBIDDEN, new ApiError("FORBIDDEN", "沒有權限執行此操作"));
            }
        };
    }

    /**
     * 登出成功 → 204。框架的 CsrfLogoutHandler 已清掉舊 token Cookie；
     * 這裡立刻發一個新的，讓前端不必多打一次請求就能再次登入。
     */
    LogoutSuccessHandler logoutSuccessHandler() {
        return (HttpServletRequest request, HttpServletResponse response, Authentication authentication) -> {
            csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
            response.setStatus(HttpStatus.NO_CONTENT.value());
        };
    }

    /** 帳號已失效（停用／刪除／角色變更）→ 401，與一般未登入相同。 */
    void writeUnauthenticated(HttpServletResponse response) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, new ApiError("UNAUTHENTICATED", "請先登入"));
    }

    /** Filter 層的系統故障 → 500。固定文字，不含任何內部細節。 */
    void writeInternalError(HttpServletResponse response) throws IOException {
        write(response, HttpStatus.INTERNAL_SERVER_ERROR, new ApiError("INTERNAL_ERROR", "系統發生錯誤，請稍後再試"));
    }

    private void write(HttpServletResponse response, HttpStatus status, ApiError body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
