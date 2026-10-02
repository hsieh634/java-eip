package com.renewflow.eip.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 專案 DTO。Bean Validation 只擋「必填與長度上限」（→ 400 VALIDATION_FAILED）；
 * 代碼正規化後的格式、去除空白後的長度、「沒有變更」等規則在 Service（→ 400 INVALID_PROJECT）。
 */
public final class ProjectDtos {

    private ProjectDtos() {
    }

    /** 建立：代碼會被去除前後空白並轉大寫（長度上限以正規化前寬鬆檢查，正規化後再精確檢查）。 */
    public record CreateProjectRequest(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description) {
    }

    /** 修改：名稱、說明、啟用狀態；沒有 code 欄位（代碼建立後不可修改）。 */
    public record UpdateProjectRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            @NotNull Boolean active,
            @NotNull Long expectedVersion) {
    }

    /** 管理端看到的完整專案。 */
    public record ProjectResponse(Long id, String code, String name, String description, boolean active, long version,
                                  Instant createdAt, Instant updatedAt) {
        static ProjectResponse of(Project p) {
            return new ProjectResponse(p.getId(), p.getCode(), p.getName(), p.getDescription(), p.isActive(), p.getVersion(),
                    p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record ProjectListResponse(List<ProjectResponse> projects) {
    }

    /** 一般使用者看到的可填報專案（只有啟用中的）。 */
    public record SelectableProject(Long id, String code, String name, String description) {
        static SelectableProject of(Project p) {
            return new SelectableProject(p.getId(), p.getCode(), p.getName(), p.getDescription());
        }
    }

    public record SelectableProjectListResponse(List<SelectableProject> projects) {
    }
}
