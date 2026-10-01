package com.renewflow.eip.identity;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 登入與「目前使用者」的使用案例。不處理 HTTP／Session（那是 Controller 與 SessionLoginSupport 的事）。 */
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final EmployeeRepository employeeRepository;

    public AuthService(AuthenticationManager authenticationManager, EmployeeRepository employeeRepository) {
        this.authenticationManager = authenticationManager;
        this.employeeRepository = employeeRepository;
    }

    /**
     * 驗證帳密。成功回傳已驗證的 Authentication（principal 是 EmployeeUserDetails）。
     *
     * @throws InvalidCredentialsException 帳號不存在、密碼錯誤、帳號停用——全部同一種例外，避免帳號列舉
     */
    public Authentication authenticate(String username, String password) {
        try {
            return authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (InternalAuthenticationServiceException e) {
            // 原因：這是「系統壞了」（例如資料庫連不上），不能偽裝成密碼錯誤，否則故障會被誤判成使用者問題
            throw e;
        } catch (AuthenticationException e) {
            throw new InvalidCredentialsException();
        }
    }

    /** 以資料庫的最新狀態回傳目前使用者；員工被停用或刪除後，舊 Session 立即失效。 */
    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(Long employeeId) {
        return employeeRepository.findById(employeeId)
                .filter(Employee::isEnabled)
                .map(CurrentUserResponse::from)
                .orElseThrow(UnauthenticatedException::new);
    }
}
