package com.renewflow.eip.leave;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * 請假歷程寫入失敗時，業務修改必須一起回滾（同一個交易）。
 * 把 LeaveAuditLog 換成會丟例外的 Spy；其餘（Tomcat、PostgreSQL、交易、組織鎖）都是真的。
 */
class LeaveAuditRollbackIntegrationTest extends LeaveTestBase {

    @MockitoSpyBean
    LeaveAuditLog auditLog;

    private void breakAuditLog() {
        doThrow(new IllegalStateException("audit write failed (injected)")).when(auditLog)
                .request(any(), any(), any(), any(), any(), any(), any(), any(), any());
        doThrow(new IllegalStateException("audit write failed (injected)")).when(auditLog)
                .setting(any(), any(), any(), any(), any(), any());
    }

    private void repairAuditLog() {
        doCallRealMethod().when(auditLog).request(any(), any(), any(), any(), any(), any(), any(), any(), any());
        doCallRealMethod().when(auditLog).setting(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("LA-01 送出時歷程失敗 → 500、沒有留下申請；修復後同一個 requestId 可以正常建立（不是重送）")
    void submitRollsBack() {
        TestBrowser aliceB = loginAs("alice");
        breakAuditLog();
        String body = leaveBody(UUID.randomUUID(), "SICK", DAY, "09:00", "10:00", "回滾");
        Reply failed = aliceB.post(ME, body);
        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.body()).doesNotContain("audit").doesNotContain("injected");
        assertThat(leaveRows()).isZero();
        repairAuditLog();
        Reply ok = aliceB.post(ME, body);
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.json().get("replayed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("LA-02 核准、退回、撤回、改派時歷程失敗 → 500；狀態、審核人、版本、歷程都不變")
    void transitionsRollBack() {
        TestBrowser aliceB = loginAs("alice");
        long id = submitOk(aliceB, DAY, "09:00", "10:00");
        String before = snapshot();
        breakAuditLog();
        TestBrowser bobB = loginAs("bob");
        assertThat(approve(bobB, id).status()).isEqualTo(500);
        assertThat(reject(bobB, id, "不行").status()).isEqualTo(500);
        assertThat(withdraw(aliceB, id).status()).isEqualTo(500);
        assertThat(reassign(loginAs("carol"), id, erin.getId(), "改派").status()).isEqualTo(500);
        assertThat(snapshot()).isEqualTo(before);
        repairAuditLog();
        assertThat(approve(bobB, id).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("LA-03 指定審核人設定時歷程失敗 → 500；設定沒有建立")
    void settingRollsBack() {
        breakAuditLog();
        assertThat(putSetting(loginAs("carol"), alice.getId(), erin.getId(), "設定").status()).isEqualTo(500);
        assertThat(settingVersion(alice.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from leave_approver_setting", Integer.class)).isZero();
    }
}
