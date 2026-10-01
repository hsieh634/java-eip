package com.renewflow.eip.shared.security;

import com.renewflow.eip.identity.AccountState;
import com.renewflow.eip.identity.AccountStatusService;
import com.renewflow.eip.identity.EmployeeUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 所有受保護請求共用的「最新帳號有效性」檢查（T02 記錄的 T03 前置事項）。
 *
 * <p>Session 內的登入者只是登入當時的快照。員工被停用、刪除或角色改變後，舊 Session 仍然存在，
 * 所以每個已驗證的請求都回資料庫確認一次；無效就作廢 Session 並回 401，請求<b>不會進入 Controller 與 Service</b>，
 * 因此不會讀寫任何資料。
 *
 * <p>位置：Filter Chain 中、授權（AuthorizationFilter）之前。成本：每個已驗證請求一次主鍵單列查詢。
 *
 * <p>資料庫故障時回 500，而且<b>不</b>作廢 Session——「系統壞了」不能被誤判成「帳號停用」。
 */
final class ActiveAccountFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ActiveAccountFilter.class);

    private final AccountStatusService accountStatusService;
    private final JsonSecurityHandlers handlers;
    private final SecurityContextLogoutHandler invalidateSession = new SecurityContextLogoutHandler();

    ActiveAccountFilter(AccountStatusService accountStatusService, JsonSecurityHandlers handlers) {
        this.accountStatusService = accountStatusService;
        this.handlers = handlers;
    }

    /** 登入請求不檢查：讓帶著過期 Session 的人仍然可以重新登入。（登出由更早的 LogoutFilter 處理。） */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod()) && "/api/auth/login".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof EmployeeUserDetails principal)) {
            chain.doFilter(request, response); // 匿名請求：交給授權規則決定
            return;
        }

        AccountState state;
        try {
            state = accountStatusService.check(principal);
        } catch (RuntimeException e) {
            log.error("Account status check failed", e);
            handlers.writeInternalError(response);
            return;
        }

        if (state != AccountState.ACTIVE) {
            log.info("Rejecting request: employee {} is {}", principal.getEmployeeId(), state);
            invalidateSession.logout(request, response, authentication); // 作廢 Session、清除登入身份
            handlers.writeUnauthenticated(response);
            return;
        }
        chain.doFilter(request, response);
    }
}
