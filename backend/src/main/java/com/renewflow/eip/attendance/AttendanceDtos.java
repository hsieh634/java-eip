package com.renewflow.eip.attendance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * T03.1 的請求／回應 DTO。Entity 不對外；請求中沒有任何「操作者」「審核時間」之類由後端決定的欄位。
 * 時間輸入是「台北當地時間」（LocalTime），輸出是 ISO 8601 的時間點（Instant，UTC）。
 */
public final class AttendanceDtos {

    private AttendanceDtos() {
    }

    // ---------------- 請求 ----------------

    /** 員工提出申請。RESET_TODAY 只需要 type 與 reason；CORRECT_TIME 另需 workDate 與 clockInTime。 */
    public record CreateRequest(
            @NotNull AttendanceRequestType type,
            @NotBlank @Size(max = 500) String reason,
            LocalDate workDate,
            LocalTime clockInTime,
            LocalTime clockOutTime) {
    }

    public record ApproveRequest(@Size(max = 500) String comment) {
    }

    /** 退回原因必填。 */
    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** 管理員直接更正。expectedVersion 省略＝建立（該日必須沒有有效紀錄）。 */
    public record DirectCorrectionRequest(
            @NotNull Long employeeId,
            @NotNull LocalDate workDate,
            Long expectedVersion,
            @NotNull LocalTime clockInTime,
            LocalTime clockOutTime,
            @NotBlank @Size(max = 500) String reason) {
    }

    /** 管理員作廢今日的有效紀錄以開放重新打卡。 */
    public record DirectVoidRequest(
            @NotNull Long employeeId,
            @NotNull Long attendanceId,
            @NotNull Long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {
    }

    // ---------------- 回應 ----------------

    public record TimePair(Instant clockInAt, Instant clockOutAt) {
    }

    /** 實際修改後的狀態；voided=true 表示該筆被作廢。 */
    public record AfterState(Instant clockInAt, Instant clockOutAt, boolean voided) {
    }

    public record EmployeeSummary(Long id, String employeeNo, String displayName) {
    }

    /**
     * 申請。original＝申請當下的原時間（沒有紀錄則 null）；requested＝希望值（RESET_TODAY 為 null）；
     * current＝目前的有效紀錄（只在待審時提供，讓審核者看到「現在」與「申請當時」的差異）；
     * stale＝待審且目標資料已經變動，核准會被拒絕（員工可撤回後重新提出）。
     */
    public record AttendanceRequestResponse(
            Long id,
            AttendanceRequestType type,
            AttendanceRequestStatus status,
            LocalDate workDate,
            String reason,
            EmployeeSummary employee,
            TimePair original,
            TimePair requested,
            TimePair current,
            boolean stale,
            Instant createdAt,
            Instant reviewedAt,
            String reviewerName,
            String reviewComment) {
    }

    public record RequestListResponse(List<AttendanceRequestResponse> requests) {
    }

    public record HistoryEntryResponse(
            Long id,
            AttendanceOperation operation,
            LocalDate workDate,
            Long requestId,
            AttendanceRequestType requestType,
            String reason,
            String actorName,
            String employeeName,
            TimePair original,
            TimePair requested,
            AfterState after,
            String result,
            Instant occurredAt) {
    }

    public record HistoryListResponse(List<HistoryEntryResponse> entries) {
    }

    public record AdminRecordsResponse(
            EmployeeSummary employee,
            LocalDate from,
            LocalDate to,
            String timeZone,
            List<AttendanceRecordResponse> records) {
    }
}
