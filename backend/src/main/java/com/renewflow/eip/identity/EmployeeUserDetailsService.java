package com.renewflow.eip.identity;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Locale;

/** Spring Security 登入時呼叫這裡「依帳號取出使用者」；密碼比對由框架的 DaoAuthenticationProvider 負責。 */
@Service
public class EmployeeUserDetailsService implements UserDetailsService {

    private final EmployeeRepository employeeRepository;

    public EmployeeUserDetailsService(EmployeeRepository employeeRepository) {
        this.employeeRepository = employeeRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return employeeRepository.findByUsername(username.trim().toLowerCase(Locale.ROOT))
                .map(EmployeeUserDetails::new)
                // 此例外對外會被統一轉成「帳號或密碼錯誤」，不會洩漏帳號是否存在
                .orElseThrow(() -> new UsernameNotFoundException("employee not found"));
    }
}
