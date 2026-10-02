package com.renewflow.eip.timesheet;

import com.renewflow.eip.project.Project;
import com.renewflow.eip.project.ProjectRepository;
import com.renewflow.eip.timesheet.TimesheetDtos.DailyTotal;
import com.renewflow.eip.timesheet.TimesheetDtos.EntryResponse;
import com.renewflow.eip.timesheet.TimesheetDtos.ProjectRef;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Entity → 回應 DTO。專案名稱以一次批次查詢取得（不在迴圈內逐筆查）。 */
@Component
public class TimesheetViews {

    private final ProjectRepository projectRepository;

    public TimesheetViews(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    public EntryResponse entry(TimesheetEntry e, Project project) {
        return new EntryResponse(e.getId(), e.getWorkDate(),
                new ProjectRef(project.getId(), project.getCode(), project.getName(), project.isActive()),
                e.getDescription(), e.getMinutes(), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }

    public List<EntryResponse> entries(List<TimesheetEntry> entries) {
        Map<Long, Project> projects = projectsOf(entries.stream().map(TimesheetEntry::getProjectId).distinct().toList());
        return entries.stream().map(e -> entry(e, projects.get(e.getProjectId()))).toList();
    }

    /** 每日合計：只列出有紀錄的日期，新到舊。 */
    public static List<DailyTotal> daily(List<TimesheetEntry> entries) {
        Map<LocalDate, Integer> byDate = new TreeMap<>(java.util.Comparator.reverseOrder());
        for (TimesheetEntry e : entries) {
            byDate.merge(e.getWorkDate(), e.getMinutes(), Integer::sum);
        }
        return byDate.entrySet().stream().map(x -> new DailyTotal(x.getKey(), x.getValue())).toList();
    }

    private Map<Long, Project> projectsOf(Collection<Long> ids) {
        return projectRepository.findAllById(ids).stream().collect(Collectors.toMap(Project::getId, Function.identity()));
    }
}
