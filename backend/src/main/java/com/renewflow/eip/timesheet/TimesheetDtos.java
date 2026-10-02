package com.renewflow.eip.timesheet;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 工時 DTO。本文沒有 employeeId、createdAt、version 等伺服器管理欄位：員工一律來自 Session，
 * 前端多送的欄位會被忽略（Jackson 預設不因未知欄位失敗）。
 * Bean Validation 擋格式與範圍（→ 400 VALIDATION_FAILED）；15 分鐘倍數、去除空白後的長度、未來日期、
 * 專案狀態、每日合計等規則在 Service。
 */
public final class TimesheetDtos {

    private TimesheetDtos() {
    }

    public record CreateEntryRequest(
            @NotNull UUID requestId,
            @NotNull LocalDate workDate,
            @NotNull Long projectId,
            @NotBlank @Size(max = 2000) String description,
            @NotNull @Min(TimesheetRules.MIN_MINUTES) @Max(TimesheetRules.MAX_MINUTES_PER_ENTRY) Integer minutes) {
    }

    public record UpdateEntryRequest(
            @NotNull LocalDate workDate,
            @NotNull Long projectId,
            @NotBlank @Size(max = 2000) String description,
            @NotNull @Min(TimesheetRules.MIN_MINUTES) @Max(TimesheetRules.MAX_MINUTES_PER_ENTRY) Integer minutes,
            @NotNull Long expectedVersion) {
    }

    /** 紀錄所屬專案的摘要；active=false 時畫面標示「已停用」。 */
    public record ProjectRef(Long id, String code, String name, boolean active) {
    }

    public record EntryResponse(
            Long id,
            LocalDate workDate,
            ProjectRef project,
            String description,
            int minutes,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record DailyTotal(LocalDate date, int minutes) {
    }

    /** totalMinutes 與 daily 只統計 from～to 範圍內、本人的紀錄。 */
    public record TimesheetListResponse(
            LocalDate from,
            LocalDate to,
            String timeZone,
            List<EntryResponse> entries,
            int totalMinutes,
            List<DailyTotal> daily) {
    }
}
