package com.renewflow.eip.attendance;

import com.renewflow.eip.identity.AccountState;
import com.renewflow.eip.identity.AccountStatusService;
import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * A-14：帳號有效性檢查本身發生資料庫故障（以受控替身模擬）。
 * 結果必須是 500 INTERNAL_ERROR——不能被誤判成「帳號停用」（401），Session 也不能因此被作廢。
 */
class AccountCheckFailureIntegrationTest extends AttendanceTestBase {

    @MockitoBean
    AccountStatusService accountStatusService;

    @Test
    @DisplayName("A-14 檢查時資料庫故障 → 500 INTERNAL_ERROR（讀寫皆然），不洩漏細節、不寫入資料、Session 不失效")
    void infrastructureFailureIsNotMistakenForDisabledAccount() {
        TestBrowser aliceB = loginAs("alice"); // 登入不經過帳號檢查
        when(accountStatusService.check(any())).thenThrow(
                new DataAccessResourceFailureException("could not connect to jdbc:postgresql://secret-db-host:5432/prod"));

        Reply read = aliceB.get("/api/attendance/me/today");
        assertThat(read.status()).isEqualTo(500);
        assertThat(read.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(read.body()).doesNotContain("secret-db-host").doesNotContain("jdbc").doesNotContain("Exception");

        Reply write = aliceB.post("/api/attendance/clock-in", null);
        assertThat(write.status()).isEqualTo(500);
        assertThat(write.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(attendanceRows()).as("檢查失敗時請求不得進入寫入").isZero();

        // 故障排除後，同一個 Session 仍然有效（沒有被當成帳號停用而作廢）
        doReturn(AccountState.ACTIVE).when(accountStatusService).check(any()); // 不能寫 when(mock.check(..))：那會先呼叫仍會拋例外的替身
        assertThat(aliceB.get("/api/attendance/me/today").status()).isEqualTo(200);
        assertThat(aliceB.post("/api/attendance/clock-in", null).status()).isEqualTo(200);
        assertThat(attendanceRows()).isEqualTo(1);
    }
}
