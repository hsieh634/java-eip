package com.renewflow.eip.identity;

import org.springframework.stereotype.Service;

/**
 * 共用的「最新帳號有效性」判斷。Session 內的登入者只是登入當時的快照，
 * 必須回資料庫確認：帳號是否仍存在、是否啟用、角色是否仍相同。
 * 資料庫故障時這裡不吞例外——呼叫端要能分辨「帳號無效」與「系統故障」。
 */
@Service
public class AccountStatusService {

    private final EmployeeRepository employeeRepository;

    public AccountStatusService(EmployeeRepository employeeRepository) {
        this.employeeRepository = employeeRepository;
    }

    public AccountState check(EmployeeUserDetails principal) {
        return employeeRepository.findAccountStatusById(principal.getEmployeeId())
                .map(status -> {
                    if (!status.enabled()) {
                        return AccountState.DISABLED_OR_DELETED;
                    }
                    if (status.role() != principal.getRole()) {
                        return AccountState.ROLE_CHANGED;
                    }
                    return AccountState.ACTIVE;
                })
                .orElse(AccountState.DISABLED_OR_DELETED);
    }
}
