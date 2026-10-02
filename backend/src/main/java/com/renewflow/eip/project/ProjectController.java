package com.renewflow.eip.project;

import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.project.ProjectDtos.CreateProjectRequest;
import com.renewflow.eip.project.ProjectDtos.ProjectListResponse;
import com.renewflow.eip.project.ProjectDtos.ProjectResponse;
import com.renewflow.eip.project.ProjectDtos.SelectableProjectListResponse;
import com.renewflow.eip.project.ProjectDtos.UpdateProjectRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 專案的 HTTP 邊界。
 * /api/admin/projects/**：URL 規則要求 ROLE_ADMIN，Service 內再檢查一次。
 * /api/projects：已登入即可，只回傳啟用中的專案。
 */
@RestController
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping("/api/admin/projects")
    public ProjectListResponse listAll(@AuthenticationPrincipal EmployeeUserDetails actor) {
        return new ProjectListResponse(projectService.listAll(actor));
    }

    @PostMapping("/api/admin/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@AuthenticationPrincipal EmployeeUserDetails actor, @Valid @RequestBody CreateProjectRequest body) {
        return projectService.create(actor, body);
    }

    @PutMapping("/api/admin/projects/{id}")
    public ProjectResponse update(@AuthenticationPrincipal EmployeeUserDetails actor, @PathVariable Long id,
                                  @Valid @RequestBody UpdateProjectRequest body) {
        return projectService.update(actor, id, body);
    }

    @GetMapping("/api/projects")
    public SelectableProjectListResponse selectable() {
        return new SelectableProjectListResponse(projectService.listSelectable());
    }
}
