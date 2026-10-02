package com.renewflow.eip.project;

import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.project.ProjectDtos.CreateProjectRequest;
import com.renewflow.eip.project.ProjectDtos.ProjectResponse;
import com.renewflow.eip.project.ProjectDtos.SelectableProject;
import com.renewflow.eip.project.ProjectDtos.UpdateProjectRequest;
import com.renewflow.eip.shared.time.BusinessTime;
import com.renewflow.eip.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 專案：管理端（建立、清單、修改、停用／啟用；僅 ADMIN）與一般使用者（可填報的啟用專案）。
 *
 * <p>管理方法先確認 ADMIN（縱深防禦：/api/admin/** 的 URL 規則已擋一次，ActiveAccountFilter 確認角色是最新）。
 * 停用不刪除專案，也不影響既有工時；刪除專案的 API 不存在。
 */
@Service
public class ProjectService {

    /** 正規化後的代碼：2～32 個字元，只允許英文大寫、數字、底線、連字號（資料庫 ck_project_code 相同）。 */
    static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{2,32}$");
    static final int NAME_MAX = 100;
    static final int DESCRIPTION_MAX = 1000;

    private final ProjectRepository projectRepository;
    private final Clock clock;

    ProjectService(ProjectRepository projectRepository, Clock clock) {
        this.projectRepository = projectRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listAll(EmployeeUserDetails actor) {
        requireAdmin(actor);
        return projectRepository.findAllByOrderByCodeAsc().stream().map(ProjectResponse::of).toList();
    }

    /** 所有已登入且有效的使用者：只看得到啟用中的專案（本階段不限制專案成員）。 */
    @Transactional(readOnly = true)
    public List<SelectableProject> listSelectable() {
        return projectRepository.findByActiveTrueOrderByCodeAsc().stream().map(SelectableProject::of).toList();
    }

    @Transactional
    public ProjectResponse create(EmployeeUserDetails actor, CreateProjectRequest body) {
        requireAdmin(actor);
        String code = body.code().trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw invalid("專案代碼需為 2～32 個字元，只能使用英文字母、數字、底線（_）與連字號（-）");
        }
        String name = normalizeName(body.name());
        String description = normalizeDescription(body.description());
        Long id = projectRepository.insertIfCodeFree(code, name, description, now())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PROJECT_CODE_DUPLICATE", "專案代碼 " + code + " 已經存在"));
        return ProjectResponse.of(projectRepository.findById(id).orElseThrow());
    }

    /** 修改名稱、說明與啟用狀態（停用／重新啟用也走這裡）。版本不符 → 409，不覆蓋較新的資料。 */
    @Transactional
    public ProjectResponse update(EmployeeUserDetails actor, Long id, UpdateProjectRequest body) {
        requireAdmin(actor);
        String name = normalizeName(body.name());
        String description = normalizeDescription(body.description());
        boolean active = body.active();
        Project current = projectRepository.findById(id).orElseThrow(ProjectService::notFound);
        if (current.getVersion() != body.expectedVersion()) {
            throw changed();
        }
        if (current.getName().equals(name) && Objects.equals(current.getDescription(), description) && current.isActive() == active) {
            throw invalid("內容與目前相同，沒有需要儲存的變更");
        }
        if (projectRepository.updateIfVersion(id, body.expectedVersion(), name, description, active, now()) == 0) {
            // 讀取之後、更新之前被別人改過（或剛好被刪除，本階段沒有刪除 API）
            throw changed();
        }
        return ProjectResponse.of(projectRepository.findById(id).orElseThrow());
    }

    private static String normalizeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw invalid("專案名稱必填，去除前後空白後最多 " + NAME_MAX + " 個字");
        }
        return name;
    }

    /** 說明選填：去除前後空白，空白視為沒有說明。 */
    private static String normalizeDescription(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String description = raw.trim();
        if (description.length() > DESCRIPTION_MAX) {
            throw invalid("專案說明最多 " + DESCRIPTION_MAX + " 個字");
        }
        return description;
    }

    private Instant now() {
        return BusinessTime.truncateToDatabasePrecision(clock.instant());
    }

    private static void requireAdmin(EmployeeUserDetails actor) {
        if (actor == null || actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("administrator role required");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROJECT", message);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "找不到這個專案");
    }

    private static ApiException changed() {
        return new ApiException(HttpStatus.CONFLICT, "PROJECT_CHANGED", "專案已被其他管理員修改，請重新載入後再操作");
    }
}
