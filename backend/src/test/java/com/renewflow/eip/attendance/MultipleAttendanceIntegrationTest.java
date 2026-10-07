package com.renewflow.eip.attendance;

import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import static org.assertj.core.api.Assertions.assertThat;

/** M01～M09：真 HTTP、PostgreSQL、可控 Clock；沒有使用開發帳號。 */
class MultipleAttendanceIntegrationTest extends CorrectionsTestBase {
    private String inBody(UUID key) { return "{\"requestId\":\"" + key + "\"}"; }
    private Reply in(TestBrowser b, String instant) { clock.set(utc(instant)); return b.post(CLOCK_IN, inBody(UUID.randomUUID())); }
    private Reply out(TestBrowser b, String instant) { clock.set(utc(instant)); return b.post(CLOCK_OUT, null); }
    private long number(Reply r, String key) { return r.json().get(key).asLong(); }
    private int events() { return jdbc.queryForObject("select count(*) from attendance_clock_event", Integer.class); }
    private int submissions() { return jdbc.queryForObject("select count(*) from attendance_clock_submission", Integer.class); }
    private String target(Reply r) { return ",\"expectedAttendanceId\":" + number(r, "id") + ",\"expectedVersion\":" + number(r, "version"); }
    private String correct(Reply r, String in, String out) {
        return "{\"type\":\"CORRECT_TIME\",\"workDate\":\"2026-10-01\",\"clockInTime\":\"" + in + "\",\"clockOutTime\":" + (out == null ? "null" : "\"" + out + "\"") + ",\"reason\":\"多組更正\"" + target(r) + "}";
    }

    @Test void twoAndThreeSessionsTotalsAndOldReplay() {
        var b = loginAs("alice");
        UUID firstKey = UUID.randomUUID();
        Reply first = b.post(CLOCK_IN, inBody(firstKey));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json().get("requestId").asString()).isEqualTo(firstKey.toString());
        assertThat(b.post(CLOCK_IN, null).status()).isEqualTo(409);
        Reply firstOut = out(b, "2026-10-01T04:00:00Z");
        assertThat(firstOut.status()).isEqualTo(200);
        Reply second = in(b, "2026-10-01T06:00:00Z");
        assertThat(out(b, "2026-10-01T10:00:00Z").status()).isEqualTo(200);
        var two = b.get(TODAY).json();
        assertThat(two.get("records").size()).isEqualTo(2);
        assertThat(two.get("completedSeconds").asLong()).isEqualTo(7 * 3600);
        Reply third = in(b, "2026-10-01T11:00:00Z");
        assertThat(number(third, "id")).isNotIn(number(first, "id"), number(second, "id"));
        assertThat(b.get(TODAY).json().get("completedSeconds").asLong()).isEqualTo(7 * 3600);
        assertThat(b.post(CLOCK_IN, inBody(firstKey)).body()).isEqualTo(first.body());
        assertThat(attendanceRows()).isEqualTo(3);
        assertThat(events()).isEqualTo(5);
        assertThat(submissions()).isEqualTo(5);
        assertThat(out(b, "2026-10-01T12:00:00Z").status()).isEqualTo(200);
        assertThat(b.get(TODAY).json().get("completedSeconds").asLong()).isEqualTo(8 * 3600);
    }

    @Test void replayOutAndRejectChangedContentOrAction() {
        var b = loginAs("alice");
        Reply first = in(b, "2026-10-01T01:00:00Z");
        UUID key = UUID.randomUUID();
        String body = "{\"requestId\":\"" + key + "\",\"expectedAttendanceId\":" + number(first, "id") + ",\"expectedVersion\":0}";
        clock.set(utc("2026-10-01T04:00:00Z"));
        Reply done = b.post(CLOCK_OUT, body);
        assertThat(done.status()).isEqualTo(200);
        Reply next = in(b, "2026-10-01T06:00:00Z");
        assertThat(b.post(CLOCK_OUT, body).body()).isEqualTo(done.body());
        assertThat(b.get(TODAY).json().get("recordId").asLong()).isEqualTo(number(next, "id"));
        assertThat(b.get(TODAY).json().get("status").asString()).isEqualTo("WORKING");
        assertThat(b.post(CLOCK_IN, body).code()).isEqualTo("CLOCK_REQUEST_MISMATCH");
        assertThat(b.post(CLOCK_OUT, body.replace(",\"expectedVersion\":0", ",\"expectedVersion\":1")).code()).isEqualTo("CLOCK_REQUEST_MISMATCH");
        assertThat(events()).isEqualTo(3);
    }

    @Test void staleOutCannotCloseNewSession() {
        var b = loginAs("alice");
        Reply first = in(b, "2026-10-01T01:00:00Z");
        String delayed = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedAttendanceId\":" + number(first, "id") + ",\"expectedVersion\":0}";
        out(b, "2026-10-01T04:00:00Z");
        in(b, "2026-10-01T06:00:00Z");
        assertThat(b.post(CLOCK_OUT, delayed).code()).isEqualTo("RECORD_CHANGED");
        assertThat(events()).isEqualTo(3);
    }

    @Test void correctionOldIdAndVoidOnlyOneSession() {
        var b = loginAs("alice"); var admin = loginAs("carol");
        in(b, "2026-10-01T01:00:00Z"); Reply first = out(b, "2026-10-01T04:00:00Z");
        in(b, "2026-10-01T06:00:00Z"); Reply second = out(b, "2026-10-01T10:00:00Z");
        var request = b.post(REQUESTS, correct(first, "09:30", "12:00"));
        assertThat(request.status()).isEqualTo(201);
        assertThat(approve(admin, number(request, "id")).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select version from attendance where id=?", Long.class, number(second, "id"))).isEqualTo(number(second, "version"));
        Reply voided = admin.post(ADMIN + "/records/void", "{\"employeeId\":" + alice.getId() + ",\"attendanceId\":" + number(first,"id") + ",\"expectedVersion\":2,\"reason\":\"只作廢第一組\"}");
        assertThat(voided.status()).isEqualTo(200);
        assertThat(b.get(TODAY).json().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(b.get(TODAY).json().get("completedSeconds").asLong()).isEqualTo(4 * 3600);
        assertThat(events()).isEqualTo(4);
    }

    @Test void laterSessionMakesRevokeApprovalStaleAndRollsBack() {
        var b = loginAs("alice"); var admin = loginAs("carol");
        in(b, "2026-10-01T01:00:00Z"); Reply first = out(b, "2026-10-01T04:00:00Z");
        var request = b.post(REQUESTS, correct(first, "09:00", null));
        assertThat(request.status()).isEqualTo(201);
        in(b, "2026-10-01T06:00:00Z");
        int history = historyRows();
        assertThat(approve(admin, number(request,"id")).code()).isEqualTo("REQUEST_STALE");
        assertThat(requestStatus(number(request,"id"))).isEqualTo("PENDING");
        assertThat(historyRows()).isEqualTo(history);
        assertThat(jdbc.queryForObject("select clock_out_at is not null from attendance where id=?", Boolean.class, number(first,"id"))).isTrue();
    }

    @Test void supplementGapAdjacentAndOverlapRejected() {
        var b = loginAs("alice"); var admin = loginAs("carol");
        in(b, "2026-10-01T01:00:00Z"); out(b, "2026-10-01T04:00:00Z");
        in(b, "2026-10-01T06:00:00Z"); out(b, "2026-10-01T10:00:00Z");
        var request = b.post(REQUESTS, "{\"type\":\"CORRECT_TIME\",\"workDate\":\"2026-10-01\",\"clockInTime\":\"12:00\",\"clockOutTime\":\"14:00\",\"reason\":\"補登缺少時段\"}");
        assertThat(request.status()).isEqualTo(201);
        assertThat(approve(admin, number(request,"id")).status()).isEqualTo(200);
        assertThat(activeRows(alice)).isEqualTo(3);
        assertThat(b.get(TODAY).json().get("completedSeconds").asLong()).isEqualTo(9 * 3600);
        var overlap = b.post(REQUESTS, "{\"type\":\"CORRECT_TIME\",\"workDate\":\"2026-10-01\",\"clockInTime\":\"11:00\",\"clockOutTime\":\"13:00\",\"reason\":\"重疊\"}");
        assertThat(overlap.code()).isEqualTo("ATTENDANCE_OVERLAP");
    }

    @Test void sameKeyConcurrentCreatesOnlyOneEvent() throws Exception {
        var a = loginAs("alice"); var b = loginAs("alice");
        String body = inBody(UUID.randomUUID());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var x = pool.submit(() -> { gate.await(); return a.post(CLOCK_IN, body); });
            var y = pool.submit(() -> { gate.await(); return b.post(CLOCK_IN, body); });
            gate.countDown();
            var first = x.get(); var second = y.get();
            assertThat(first.status()).isEqualTo(200); assertThat(second.body()).isEqualTo(first.body());
        }
        assertThat(attendanceRows()).isEqualTo(1); assertThat(events()).isEqualTo(1); assertThat(submissions()).isEqualTo(1);
    }

    @Test void clockAndManualOpenSessionCompeteUnderSameLock() throws Exception {
        var employee = loginAs("alice"); var admin = loginAs("carol");
        clock.set(utc("2026-10-01T02:00:00Z"));
        String manual = "{\"employeeId\":" + alice.getId() + ",\"workDate\":\"2026-10-01\",\"clockInTime\":\"09:30\",\"clockOutTime\":null,\"reason\":\"並行補登\"}";
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var x = pool.submit(() -> { gate.await(); return employee.post(CLOCK_IN, null); });
            var y = pool.submit(() -> { gate.await(); return admin.post(ADMIN + "/records/correct", manual); });
            gate.countDown();
            var first = x.get(); var second = y.get();
            assertThat(java.util.List.of(first.status(),second.status())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(attendanceRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from attendance where voided_at is null and clock_out_at is null",Integer.class)).isEqualTo(1);
    }

    @Test void everyNewSessionChecksCurrentPolicyButReplayKeepsOriginalEvidence() {
        var b = loginAs("alice");
        String original = inBody(UUID.randomUUID());
        assertThat(b.post(CLOCK_IN, original).status()).isEqualTo(200);
        out(b, "2026-10-01T04:00:00Z");
        jdbc.update("update geofence_setting set enabled=true, center_latitude=25,center_longitude=121.5,version=version+1");
        clock.set(utc("2026-10-01T06:00:00Z"));
        assertThat(b.post(CLOCK_IN, null).code()).isEqualTo("LOCATION_REQUIRED");
        assertThat(attendanceRows()).isEqualTo(1); assertThat(events()).isEqualTo(2); assertThat(submissions()).isEqualTo(2);
        assertThat(b.post(CLOCK_IN, original).status()).isEqualTo(200);
        assertThat(events()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from attendance_clock_event where latitude is not null or longitude is not null",Integer.class)).isZero();
    }
}
