package com.renewflow.eip.attendance;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * C-23：歷程寫入失敗時，整個操作必須回滾——出勤資料、申請狀態、歷程三者一起不變。
 * 做法：把歷程的單一寫入入口（AttendanceAuditLog.record）換成「會丟例外」的 Spy，
 * 其餘（真實 Tomcat、真實 PostgreSQL、完整的交易邊界）都不變。
 */
class AttendanceAuditRollbackIntegrationTest extends CorrectionsTestBase {

    @MockitoSpyBean
    AttendanceAuditLog auditLog;

    private void breakAuditLog() {
        doThrow(new IllegalStateException("audit write failed (injected)")).when(auditLog).record(any());
    }

    private void repairAuditLog() {
        doCallRealMethod().when(auditLog).record(any());
    }

    @Test
    @DisplayName("C-23 核准時歷程寫入失敗 → 500；出勤沒被作廢、申請仍待審、歷程沒有新增；修復後同一申請可正常核准")
    void approvalRollsBackWhenHistoryFails() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        long id = idOf(submitReset(aliceB, "回滾測試"));
        long version = activeVersion(alice, "2026-10-01");
        int historyBefore = historyRows();
        breakAuditLog();

        Reply failed = approve(carolB, id);

        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(failed.body()).doesNotContain("audit").doesNotContain("injected");
        assertThat(requestStatus(id)).as("申請狀態一起回滾").isEqualTo("PENDING");
        assertThat(activeRows(alice)).as("出勤沒有被作廢").isEqualTo(1);
        assertThat(voidedRows(alice)).isZero();
        assertThat(activeVersion(alice, "2026-10-01")).isEqualTo(version);
        assertThat(historyRows()).as("沒有半套的歷程").isEqualTo(historyBefore);

        repairAuditLog();
        assertThat(approve(carolB, id).status()).isEqualTo(200);
        assertThat(voidedRows(alice)).isEqualTo(1);
    }

    @Test
    @DisplayName("C-23 提出申請時歷程寫入失敗 → 500；沒有留下申請（INSERT 一起回滾），之後仍可正常提出")
    void submissionRollsBackWhenHistoryFails() {
        TestBrowser aliceB = loginAs("alice");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        breakAuditLog();

        Reply failed = submitReset(aliceB, "會失敗");

        assertThat(failed.status()).isEqualTo(500);
        assertThat(requestRows()).isZero();
        assertThat(historyRows()).isZero();

        repairAuditLog();
        assertThat(submitReset(aliceB, "修復後").status()).as("待審名額沒有被失敗的嘗試佔住").isEqualTo(201);
    }

    @Test
    @DisplayName("C-23 直接更正時歷程寫入失敗 → 500；出勤資料與版本完全不變")
    void directCorrectionRollsBackWhenHistoryFails() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        clock.set(utc("2026-10-01T06:00:00Z"));
        long version = activeVersion(alice, "2026-10-01");
        breakAuditLog();
        String body = json("employeeId", String.valueOf(alice.getId()), "workDate", str("2026-10-01"),
                "attendanceId", String.valueOf(activeId(alice, "2026-10-01")), "expectedVersion", String.valueOf(version),
                "clockInTime", str("08:00:00"), "clockOutTime", "null", "reason", str("回滾"));

        Reply failed = carolB.post(ADMIN + "/records/correct", body);

        assertThat(failed.status()).isEqualTo(500);
        assertThat(activeVersion(alice, "2026-10-01")).isEqualTo(version);
        assertThat(activeColumn(alice, "2026-10-01", "clock_in_at")).startsWith("2026-10-01 01:00:00");
        assertThat(activeColumn(alice, "2026-10-01", "corrected_at")).isNull();
        assertThat(historyRows()).isZero();
    }

    @Test
    @DisplayName("C-23 直接作廢時歷程寫入失敗 → 500；紀錄仍有效")
    void directVoidRollsBackWhenHistoryFails() {
        TestBrowser aliceB = loginAs("alice");
        TestBrowser carolB = loginAs("carol");
        punch(aliceB, CLOCK_IN, "2026-10-01T01:00:00Z");
        long id = activeId(alice, "2026-10-01");
        long version = activeVersion(alice, "2026-10-01");
        breakAuditLog();

        Reply failed = carolB.post(ADMIN + "/records/void", json("employeeId", String.valueOf(alice.getId()), "attendanceId", String.valueOf(id),
                "expectedVersion", String.valueOf(version), "reason", str("回滾")));

        assertThat(failed.status()).isEqualTo(500);
        assertThat(activeRows(alice)).isEqualTo(1);
        assertThat(voidedRows(alice)).isZero();
        assertThat(aliceB.get(TODAY).json().get("status").asString()).isEqualTo("WORKING");
    }
}
