package com.renewflow.eip.attendance;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 共用帳號有效性檢查（ActiveAccountFilter）：員工在「登入之後」被停用／刪除／改角色，
 * 舊 Session 的下一個受保護請求就必須被拒絕，而且不能讀寫任何資料。
 */
class ActiveAccountIntegrationTest extends AttendanceTestBase {

    private static final String CLOCK_IN = "/api/attendance/clock-in";
    private static final String TODAY = "/api/attendance/me/today";

    @Test
    @DisplayName("A-12 員工已停用後才發出的打卡請求 → 401；資料表沒有新增；Session 已被作廢（重新啟用也救不回）")
    void disabledEmployeeCannotClockIn() {
        TestBrowser aliceB = loginAs("alice");
        String staleSession = "JSESSIONID=" + aliceB.cookie("JSESSIONID");
        assertThat(aliceB.get(TODAY).status()).as("停用前可用").isEqualTo(200);

        jdbc.update("update employee set enabled = false where id = ?", alice.getId());

        Reply write = aliceB.post(CLOCK_IN, null);
        assertThat(write.status()).isEqualTo(401);
        assertThat(write.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(attendanceRows()).as("請求不得進入 Service／資料庫寫入").isZero();

        // 讀取也被拒絕
        assertThat(aliceB.get(TODAY).status()).isEqualTo(401);
        assertThat(aliceB.get("/api/attendance/me").status()).isEqualTo(401);

        // 重新啟用後，舊 Session 仍然無效：證明它是被「作廢」，而不是只在停用期間被擋
        jdbc.update("update employee set enabled = true where id = ?", alice.getId());
        assertThat(aliceB.getWithRawCookie(TODAY, staleSession).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("A-12b 停用的員工已有的資料不會被更新：先上班、停用後再下班 → 401，下班時間仍為 null")
    void disabledEmployeeCannotUpdateExistingRecord() {
        TestBrowser aliceB = loginAs("alice");
        assertThat(aliceB.post(CLOCK_IN, null).status()).isEqualTo(200);

        jdbc.update("update employee set enabled = false where id = ?", alice.getId());
        clock.set(DEFAULT_NOW.plusSeconds(3600));
        Reply out = aliceB.post("/api/attendance/clock-out", null);

        assertThat(out.status()).isEqualTo(401);
        String clockOut = jdbc.queryForObject("select clock_out_at::text from attendance where employee_id = ?",
                String.class, alice.getId());
        assertThat(clockOut).isNull();
    }

    @Test
    @DisplayName("A-13 員工已被刪除後，舊 Session 的受保護請求 → 401")
    void deletedEmployeeCannotUseOldSession() {
        TestBrowser bobBrowser = loginAs("bob");
        assertThat(bobBrowser.get(TODAY).status()).isEqualTo(200);

        jdbc.update("delete from employee where id = ?", bob.getId());

        assertThat(bobBrowser.get(TODAY).status()).isEqualTo(401);
        assertThat(bobBrowser.post(CLOCK_IN, null).status()).isEqualTo(401);
        assertThat(attendanceRows()).isZero();
    }

    @Test
    @DisplayName("A-15 角色在資料庫被改變 → 舊 Session 401（需重新登入）；重新登入後取得新角色")
    void roleChangeRequiresRelogin() {
        TestBrowser aliceB = loginAs("alice");
        assertThat(aliceB.get(TODAY).status()).isEqualTo(200);

        jdbc.update("update employee set role = 'MANAGER' where id = ?", alice.getId());

        assertThat(aliceB.get(TODAY).status()).isEqualTo(401);

        // 同一個瀏覽器可以直接重新登入（登入請求不經過帳號檢查）
        Reply relogin = aliceB.login("alice", PASSWORD);
        assertThat(relogin.status()).isEqualTo(200);
        assertThat(relogin.json().get("role").asString()).isEqualTo("MANAGER");
        assertThat(aliceB.get(TODAY).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("A-16 有出勤紀錄的員工無法被刪除（外鍵 RESTRICT），出勤歷史保留")
    void employeeWithAttendanceCannotBeDeleted() {
        TestBrowser aliceB = loginAs("alice");
        assertThat(aliceB.post(CLOCK_IN, null).status()).isEqualTo(200);

        assertThatThrownBy(() -> jdbc.update("delete from employee where id = ?", alice.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(attendanceRowsOf(alice)).isEqualTo(1);
    }

    @Test
    @DisplayName("有效的帳號不受影響：連續多次受保護請求都通過")
    void activeAccountPasses() {
        TestBrowser aliceB = loginAs("alice");
        for (int i = 0; i < 3; i++) {
            assertThat(aliceB.get(TODAY).status()).isEqualTo(200);
        }
    }
}
