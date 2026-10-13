package com.renewflow.eip.leave;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.leave.LeaveEnums.ApproverSource;
import com.renewflow.eip.leave.LeaveEnums.HistoryAction;
import com.renewflow.eip.leave.LeaveEnums.LeaveStatus;
import com.renewflow.eip.leave.LeaveEnums.LeaveType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * 請假 DTO。Bean Validation 只擋必填與寬鬆的長度上限（→ 400 VALIDATION_FAILED）；
 * 半小時刻度、起訖順序、原因去除空白後的長度、日期範圍在 Service（→ 400 INVALID_LEAVE／REASON_REQUIRED）。
 * 申請人、狀態、審核人、分鐘數都不在請求本文中——由後端決定。
 */
public final class LeaveDtos {

    private LeaveDtos() {
    }

    // ---------------------------------------------------------------- 請求

    public record SubmitLeaveRequest(
            @NotNull UUID requestId,
            @NotNull LeaveType leaveType,
            @NotNull LocalDate workDate,
            @NotNull @JsonFormat(pattern = "HH:mm") LocalTime startTime,
            @NotNull @JsonFormat(pattern = "HH:mm") LocalTime endTime,
            @NotNull @Size(max = 2000) String reason) {
    }

    public record WithdrawRequest(@NotNull Long expectedVersion, @Size(max = 2000) String reason) {
    }

    public record ApproveRequest(@NotNull Long expectedVersion, String evidenceToken) {
        public ApproveRequest(Long expectedVersion) { this(expectedVersion,null); }
    }

    public record RejectRequest(@NotNull Long expectedVersion, @Size(max = 2000) String reason, String evidenceToken) {
        public RejectRequest(Long expectedVersion,String reason) { this(expectedVersion,reason,null); }
    }

    public record ReassignRequest(@NotNull Long approverId, @NotNull Long expectedVersion, @Size(max = 2000) String reason) {
    }

    /** approverId 為 null＝移除指定，改回採用直屬主管。 */
    public record UpdateApproverSettingRequest(Long approverId, @NotNull Long expectedVersion, @Size(max = 2000) String reason) {
    }

    // ---------------------------------------------------------------- 回應

    public record PersonRef(Long id, String employeeNo, String displayName) {
    }

    /** valid：這位審核人「目前」是否仍是啟用中的 MANAGER／ADMIN（管理員判斷是否需要改派）。 */
    public record ApproverRef(Long id, String employeeNo, String displayName, boolean valid) {
    }

    public record LeaveRequestResponse(Long id, UUID requestId, LeaveType leaveType, LocalDate workDate,
                                       @JsonFormat(pattern = "HH:mm") LocalTime startTime,
                                       @JsonFormat(pattern = "HH:mm") LocalTime endTime,
                                       int minutes, String reason, LeaveStatus status,
                                       PersonRef applicant, ApproverRef approver, ApproverSource approverSource,
                                       PersonRef decidedBy, Instant decidedAt, String decisionReason,
                                       long version, Instant createdAt, Instant updatedAt, RevisionInfo revision) {
    }

    public record LeaveHistoryEntry(Long id, HistoryAction action, LeaveStatus fromStatus, LeaveStatus toStatus,
                                    PersonRef beforeApprover, PersonRef afterApprover, String reason, PersonRef actor,
                                    Instant occurredAt, DecisionContext decision, Long leaveRequestId, boolean decisionRestricted) {
    }

    public record LeavePage(List<LeaveRequestResponse> items, int page, int size, long total, Integer effectiveTotalMinutes) {
    }

    public record LeaveDetail(LeaveRequestResponse request, List<LeaveHistoryEntry> history, DocumentResponse document,
                              com.renewflow.eip.attendance.AttendanceEvidenceService.Snapshot evidence) {
    }

    public enum ViewMode { FULL, REVIEW_HISTORY }

    public record RevisionRequest(@NotNull UUID requestId, @NotNull LeaveEnums.Operation operation,
                                  @NotNull Long expectedSourceVersion, Long expectedEffectiveRequestId,
                                  @NotNull Long expectedEffectiveVersion, @NotNull LeaveType leaveType,
                                  @NotNull LocalDate workDate, @NotNull @JsonFormat(pattern="HH:mm") LocalTime startTime,
                                  @NotNull @JsonFormat(pattern="HH:mm") LocalTime endTime, @NotNull @Size(max=2000) String reason) {}
    public record RevisionInfo(Long documentId, int number, LeaveEnums.Operation operation, Long sourceRequestId,
                               Long sourceVersion, Long baseEffectiveRequestId, long baseEffectiveVersion,
                               long effectiveVersion, boolean currentlyEffective, boolean canceled,
                               PersonRef submittedApprover, String submittedApproverSource, ViewMode viewMode) {}
    public record DocumentResponse(Long id, long effectiveVersion, boolean canceled, LeaveRequestResponse origin,
                                   LeaveRequestResponse originalApproved, LeaveRequestResponse effective,
                                   LeaveRequestResponse pending, List<LeaveRequestResponse> versions, ViewMode viewMode, int throughRevision) {}
    public record RevisionSubmitResult(boolean replayed, UUID requestId, LeaveEnums.Operation operation,
                                       Long sourceRequestId, long sourceVersion, Long baseEffectiveRequestId,
                                       long baseEffectiveVersion, LeaveRequestResponse request) {}
    public record DecisionContext(boolean previewVerified, Long beforeEffectiveRequestId, long beforeEffectiveVersion,
                                  Long afterEffectiveRequestId, long afterEffectiveVersion,
                                  com.renewflow.eip.attendance.AttendanceEvidenceService.Snapshot attendance) {}

    /** POST 的回應：replayed＝這是相同 requestId＋相同內容的重送，回傳的是原申請（沒有新增）。 */
    public record SubmitResult(boolean replayed, LeaveRequestResponse request) {
    }

    /** 預計審核人（只是提示；送出時後端重新判斷）。source：DESIGNATED、MANAGER 或 NONE。 */
    public record ApproverPreview(PersonRef approver, String source, String problem) {
    }

    public record EmployeeBrief(Long id, String employeeNo, String displayName, Role role, boolean enabled) {
    }

    public record ApproverSettingResponse(EmployeeBrief employee, ApproverRef designatedApprover, ApproverRef manager,
                                          ApproverPreview effective, long version, Instant updatedAt) {
    }

    public record ApproverSettingList(List<ApproverSettingResponse> settings) {
    }

    public record HistoryList(List<LeaveHistoryEntry> history) {
    }
}
