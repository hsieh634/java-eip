package com.renewflow.eip.attendance;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

/** M10：去重保存後故障，也必須回滾出勤與原始事件。 */
class MultipleAttendanceRollbackIntegrationTest extends CorrectionsTestBase {
    @MockitoSpyBean AttendanceClockSubmissions submissions;
    private void failAfterRecord() {
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("injected after submission record"); })
                .when(submissions).record(any(), any(), any(), any(), any());
    }
    @Test void clockInAndDedupAndEventRollbackTogether() {
        var b = loginAs("alice");
        String body = "{\"requestId\":\"" + UUID.randomUUID() + "\"}";
        failAfterRecord();
        assertThat(b.post(CLOCK_IN, body).status()).isEqualTo(500);
        assertThat(attendanceRows()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from attendance_clock_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from attendance_clock_submission", Integer.class)).isZero();
        doCallRealMethod().when(submissions).record(any(), any(), any(), any(), any());
        assertThat(b.post(CLOCK_IN, body).status()).isEqualTo(200);
        assertThat(b.post(CLOCK_IN, body).status()).isEqualTo(200);
        assertThat(attendanceRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from attendance_clock_submission", Integer.class)).isEqualTo(1);
    }
    @Test void clockOutRollbackKeepsOriginalVersion() {
        var b = loginAs("alice");
        var in = b.post(CLOCK_IN, null);
        assertThat(in.status()).isEqualTo(200);
        String body = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedAttendanceId\":" + in.json().get("id").asLong() + ",\"expectedVersion\":0}";
        clock.set(utc("2026-10-01T04:00:00Z"));
        failAfterRecord();
        assertThat(b.post(CLOCK_OUT, body).status()).isEqualTo(500);
        assertThat(b.get(TODAY).json().get("status").asString()).isEqualTo("WORKING");
        assertThat(b.get(TODAY).json().get("version").asLong()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from attendance_clock_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from attendance_clock_submission", Integer.class)).isEqualTo(1);
        doCallRealMethod().when(submissions).record(any(), any(), any(), any(), any());
        assertThat(b.post(CLOCK_OUT, body).status()).isEqualTo(200);
    }
}
