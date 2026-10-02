package com.renewflow.eip.timesheet;

/**
 * 工時的工程假設（T04 第一版規則，不是公司正式人事制度），見 docs/renewflow/t04/02-assumptions.md。
 * 資料庫 ck_timesheet_minutes 使用相同數字。
 */
public final class TimesheetRules {

    /** 以 15 分鐘為單位。 */
    public static final int STEP_MINUTES = 15;
    public static final int MIN_MINUTES = 15;
    /** 單筆上限（一天）。 */
    public static final int MAX_MINUTES_PER_ENTRY = 1440;
    /** 每人每日所有紀錄合計上限：資料合理性上限，不代表正常工時或加班規則。 */
    public static final int DAILY_LIMIT_MINUTES = 1440;
    public static final int DESCRIPTION_MAX = 1000;

    private TimesheetRules() {
    }

    /** 90 → 「1 小時 30 分」；用在錯誤訊息。 */
    static String describe(int minutes) {
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) {
            return rest + " 分";
        }
        return rest == 0 ? hours + " 小時" : hours + " 小時 " + rest + " 分";
    }
}
