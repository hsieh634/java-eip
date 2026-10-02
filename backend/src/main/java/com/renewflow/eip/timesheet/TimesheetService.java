package com.renewflow.eip.timesheet;

import com.renewflow.eip.attendance.DateRange;
import com.renewflow.eip.project.Project;
import com.renewflow.eip.project.ProjectRepository;
import com.renewflow.eip.shared.time.BusinessTime;
import com.renewflow.eip.shared.web.ApiException;
import com.renewflow.eip.timesheet.TimesheetDtos.CreateEntryRequest;
import com.renewflow.eip.timesheet.TimesheetDtos.EntryResponse;
import com.renewflow.eip.timesheet.TimesheetDtos.TimesheetListResponse;
import com.renewflow.eip.timesheet.TimesheetDtos.UpdateEntryRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 本人工時：查詢、新增、修改。員工 ID 一律由呼叫端（Controller 取自 Session）傳入；「今天」與時間一律來自 Clock。
 *
 * <p>寫入的鎖定順序固定為「員工列 → 專案列 → 工時紀錄列」，所有寫入路徑都一樣，避免死鎖：
 * <ol>
 *   <li>員工列 FOR NO KEY UPDATE：同一人的寫入排隊，每日合計的「讀取→檢查→寫入」不被插隊；不同員工互不影響。</li>
 *   <li>專案列 FOR SHARE：與「停用專案」的 UPDATE 互斥，先後明確（見 ProjectRepository#lockForTimesheet）。</li>
 *   <li>紀錄列：由版本條件 UPDATE 取得。</li>
 * </ol>
 * 每日 24 小時上限沒有資料庫 CHECK 可以表達（跨多列），所以完全依賴第 1 步的序列化。
 */
@Service
public class TimesheetService {

    private final TimesheetEntryRepository entryRepository;
    private final ProjectRepository projectRepository;
    private final TimesheetViews views;
    private final Clock clock;

    TimesheetService(TimesheetEntryRepository entryRepository, ProjectRepository projectRepository, TimesheetViews views, Clock clock) {
        this.entryRepository = entryRepository;
        this.projectRepository = projectRepository;
        this.views = views;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TimesheetListResponse list(Long employeeId, LocalDate from, LocalDate to) {
        DateRange range = DateRange.resolve(from, to, today());
        List<TimesheetEntry> entries = entryRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateDescIdDesc(employeeId, range.from(), range.to());
        int total = entries.stream().mapToInt(TimesheetEntry::getMinutes).sum();
        return new TimesheetListResponse(range.from(), range.to(), BusinessTime.ZONE.getId(), views.entries(entries), total,
                TimesheetViews.daily(entries));
    }

    @Transactional(readOnly = true)
    public EntryResponse get(Long employeeId, Long id) {
        TimesheetEntry entry = entryRepository.findByIdAndEmployeeId(id, employeeId).orElseThrow(TimesheetService::notFound);
        return views.entry(entry, projectRepository.findById(entry.getProjectId()).orElseThrow());
    }

    @Transactional
    public EntryResponse create(Long employeeId, CreateEntryRequest body) {
        Instant now = now();
        String description = normalizeDescription(body.description());
        int minutes = requireValidMinutes(body.minutes());
        requireNotFuture(body.workDate(), BusinessTime.workDateOf(now));

        lockEmployee(employeeId);
        // 同一次提交的重送（結果不明時沿用同一個 requestId）：回報已建立的那一筆，不新增、也不當作修改
        entryRepository.findByEmployeeIdAndRequestId(employeeId, body.requestId()).ifPresent(existing -> {
            throw duplicate(existing.getId());
        });
        Project project = lockActiveProject(body.projectId());
        requireWithinDailyLimit(employeeId, body.workDate(), minutes, -1);

        Long id = entryRepository.insertIfRequestNew(employeeId, project.getId(), body.workDate(), description, minutes, body.requestId(), now)
                // 員工列已鎖住，同一人不會有並行的同 requestId；這裡是最後一道防線（ON CONFLICT 不會讓交易失效，可以安全地再查詢）
                .orElseThrow(() -> duplicate(entryRepository.findByEmployeeIdAndRequestId(employeeId, body.requestId())
                        .map(TimesheetEntry::getId).orElse(null)));
        return views.entry(entryRepository.findById(id).orElseThrow(), project);
    }

    @Transactional
    public EntryResponse update(Long employeeId, Long id, UpdateEntryRequest body) {
        Instant now = now();
        String description = normalizeDescription(body.description());
        int minutes = requireValidMinutes(body.minutes());
        requireNotFuture(body.workDate(), BusinessTime.workDateOf(now));

        lockEmployee(employeeId);
        TimesheetEntry current = entryRepository.findByIdAndEmployeeId(id, employeeId).orElseThrow(TimesheetService::notFound);
        if (current.getVersion() != body.expectedVersion()) {
            throw changed();
        }
        // 維持原本的專案：即使已停用也可以修改日期、時數與內容；改掛到其他專案：目標必須啟用
        Project project = body.projectId().equals(current.getProjectId())
                ? projectRepository.lockForTimesheet(current.getProjectId()).orElseThrow(TimesheetService::projectNotFound)
                : lockActiveProject(body.projectId());
        if (current.getWorkDate().equals(body.workDate()) && current.getProjectId().equals(project.getId())
                && current.getDescription().equals(description) && current.getMinutes() == minutes) {
            throw invalid("內容與目前相同，沒有需要儲存的變更");
        }
        // 排除這筆自己的舊分鐘數：同一天修改不會重複計算；換日期則檢查目標日期
        requireWithinDailyLimit(employeeId, body.workDate(), minutes, current.getId());

        if (entryRepository.updateIfVersion(id, employeeId, body.expectedVersion(), project.getId(), body.workDate(), description, minutes, now) == 0) {
            throw changed();
        }
        return views.entry(entryRepository.findById(id).orElseThrow(), project);
    }

    // ---------------- 內部 ----------------

    private void lockEmployee(Long employeeId) {
        // ActiveAccountFilter 已確認帳號存在且有效；真的讀不到代表資料異常 → 500
        entryRepository.lockEmployee(employeeId).orElseThrow(() -> new IllegalStateException("employee row missing"));
    }

    private Project lockActiveProject(Long projectId) {
        Project project = projectRepository.lockForTimesheet(projectId).orElseThrow(TimesheetService::projectNotFound);
        if (!project.isActive()) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_INACTIVE",
                    "專案 " + project.getCode() + " 已停用，不能新增工時或把紀錄改到這個專案");
        }
        return project;
    }

    private void requireWithinDailyLimit(Long employeeId, LocalDate workDate, int minutes, long excludeId) {
        int others = entryRepository.sumMinutesOfDay(employeeId, workDate, excludeId);
        if (others + minutes > TimesheetRules.DAILY_LIMIT_MINUTES) {
            throw new ApiException(HttpStatus.CONFLICT, "DAILY_LIMIT_EXCEEDED",
                    "%s 已記錄 %s，再加 %s 會超過每日 24 小時上限".formatted(workDate, TimesheetRules.describe(others), TimesheetRules.describe(minutes)),
                    Map.of("limitMinutes", TimesheetRules.DAILY_LIMIT_MINUTES, "otherMinutes", others, "requestedMinutes", minutes));
        }
    }

    private static int requireValidMinutes(Integer minutes) {
        // 範圍已由 Bean Validation 檢查；這裡只檢查倍數（不四捨五入成合法值）
        if (minutes % TimesheetRules.STEP_MINUTES != 0) {
            throw invalid("工時需以 15 分鐘（0.25 小時）為單位");
        }
        return minutes;
    }

    private static void requireNotFuture(LocalDate workDate, LocalDate today) {
        if (workDate.isAfter(today)) {
            throw invalid("不能填寫未來日期的工時（今天是 " + today + "，台北時間）");
        }
    }

    private static String normalizeDescription(String raw) {
        String description = raw == null ? "" : raw.trim();
        if (description.isEmpty() || description.length() > TimesheetRules.DESCRIPTION_MAX) {
            throw invalid("工作內容必填，去除前後空白後最多 " + TimesheetRules.DESCRIPTION_MAX + " 個字");
        }
        return description;
    }

    private LocalDate today() {
        return BusinessTime.workDateOf(clock.instant());
    }

    private Instant now() {
        return BusinessTime.truncateToDatabasePrecision(clock.instant());
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIMESHEET", message);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TIMESHEET_NOT_FOUND", "找不到這筆工時紀錄");
    }

    private static ApiException projectNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "找不到這個專案");
    }

    private static ApiException changed() {
        return new ApiException(HttpStatus.CONFLICT, "TIMESHEET_CHANGED", "這筆工時已在其他地方被修改，請重新載入後再編輯");
    }

    private static ApiException duplicate(Long entryId) {
        return new ApiException(HttpStatus.CONFLICT, "DUPLICATE_SUBMISSION",
                "這次提交已經建立過（紀錄 #" + entryId + "），沒有重複新增", entryId == null ? Map.of() : Map.of("entryId", entryId));
    }
}
