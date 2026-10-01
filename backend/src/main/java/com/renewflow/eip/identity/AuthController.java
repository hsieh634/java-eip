package com.renewflow.eip.identity;

import com.renewflow.eip.shared.security.SessionLoginSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP 邊界：解析請求、呼叫 Service、決定回應。登出（POST /api/auth/logout）由 Spring Security 的 Logout Filter
 * 在請求到達這裡之前處理，所以這個類別沒有 logout 方法。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionLoginSupport sessionLoginSupport;

    public AuthController(AuthService authService, SessionLoginSupport sessionLoginSupport) {
        this.authService = authService;
        this.sessionLoginSupport = sessionLoginSupport;
    }

    @PostMapping("/login")
    public CurrentUserResponse login(@Valid @RequestBody LoginRequest body,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        Authentication authentication = authService.authenticate(body.username(), body.password());
        sessionLoginSupport.establish(authentication, request, response);
        EmployeeUserDetails principal = (EmployeeUserDetails) authentication.getPrincipal();
        return authService.getCurrentUser(principal.getEmployeeId());
    }

    /** 員工 ID 來自 Session 內的登入身份（@AuthenticationPrincipal），沒有任何由前端傳入的 ID。 */
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal EmployeeUserDetails principal,
                                  Authentication authentication,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        try {
            return authService.getCurrentUser(principal.getEmployeeId());
        } catch (UnauthenticatedException e) {
            // 員工已被停用／刪除：連同 Session 一起作廢，不讓失效的登入狀態繼續留著
            new SecurityContextLogoutHandler().logout(request, response, authentication);
            throw e;
        }
    }
}
