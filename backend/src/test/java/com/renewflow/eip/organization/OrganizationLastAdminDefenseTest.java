package com.renewflow.eip.organization;

import com.renewflow.eip.identity.AccountState;
import com.renewflow.eip.identity.AccountStatusService;
import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.organization.OrganizationDtos.UpdateEmployeeRequest;
import com.renewflow.eip.shared.web.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/**
 * 第二道防線：T05 起取鎖後會重新確認操作者（舊身分 → 401），所以經由 API 已無法走到 LAST_ADMIN。
 * 這裡把「操作者檢查」換成永遠回 ACTIVE 的 Spy（模擬那一步被誤刪），確認 LAST_ADMIN 檢查仍會擋下。
 */
class OrganizationLastAdminDefenseTest extends OrganizationTestBase {

    @MockitoSpyBean
    AccountStatusService accountStatusService;
    @Autowired
    EmployeeAdminService employeeAdminService;

    @Test
    @DisplayName("O-09b 操作者檢查失效時：依舊身分降級或停用最後一位啟用 ADMIN → 409 LAST_ADMIN，資料不變")
    void lastAdminCheckIsSecondLine() {
        EmployeeUserDetails daveSnapshot = new EmployeeUserDetails(dave);
        assertThat(form(dave.getId()).role("MANAGER").reason("輪調").submit(loginAs("carol")).status()).isEqualTo(200);
        doReturn(AccountState.ACTIVE).when(accountStatusService).check(any());

        Form carolForm = form(carol.getId());
        assertThatThrownBy(() -> employeeAdminService.update(daveSnapshot, carol.getId(), new UpdateEmployeeRequest(
                carolForm.displayName(), Role.MANAGER, true, null, null, carolForm.expectedVersion(), "互相降級")))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("LAST_ADMIN"));
        assertThatThrownBy(() -> employeeAdminService.update(daveSnapshot, carol.getId(), new UpdateEmployeeRequest(
                carolForm.displayName(), Role.ADMIN, false, null, null, carolForm.expectedVersion(), "停用")))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("LAST_ADMIN"));
        assertThat(jdbc.queryForObject("select count(*) from employee where role = 'ADMIN' and enabled", Integer.class)).isEqualTo(1);
        assertThat(empVersion(carol.getId())).isZero();
    }
}
