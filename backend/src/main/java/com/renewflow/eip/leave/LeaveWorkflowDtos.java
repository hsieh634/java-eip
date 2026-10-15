package com.renewflow.eip.leave;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 請假單關卡設定；不接受呼叫端指定解析後的審核人或提交快照。 */
public final class LeaveWorkflowDtos {
    private LeaveWorkflowDtos() {}
    public enum Rule { MANAGER, DESIGNATED }
    public record Values(@NotNull @Size(max=100) String name, @NotNull @Size(max=1000) String description,
                         @NotNull Rule source, Long designatedApproverId) {}
    public record DraftRequest(@NotNull UUID requestId, Long expectedPublishedId, Long expectedVersion,
                               @NotNull @Valid Values values) {}
    public record PublishRequest(@NotNull UUID requestId, @NotNull Long expectedVersion, @NotNull @Size(max=2000) String reason) {}
    public record Version(Long id, int number, String status, long rowVersion, Long basePublishedId, Values values,
                          Long createdBy, Instant createdAt, Long publishedBy, Instant publishedAt, String publishReason) {}
    public record Operation(UUID requestId, String action, Version version, boolean replayed) {}
    public record History(UUID requestId, String action, Version before, Version after, Long beforePublishedId,
                          Long afterPublishedId, LeaveDtos.PersonRef actor, String reason, Instant occurredAt) {}
    public record Settings(Version current, Version draft, List<Version> versions, List<History> history) {}
    public record Preview(LeaveDtos.PersonRef employee, String source, Long workflowVersionId, Integer workflowVersionNumber,
                          String workflowName, String stageName, Rule rule, LeaveDtos.PersonRef approver,
                          boolean personalOverride, String problem) {}
    public record SubmissionSnapshot(String source, Long workflowVersionId, Integer workflowVersionNumber, String workflowName,
                                     String stageName, Rule rule, LeaveDtos.PersonRef applicant, LeaveDtos.PersonRef approver,
                                     Instant capturedAt) {}
}
