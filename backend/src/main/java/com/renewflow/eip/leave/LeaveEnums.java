package com.renewflow.eip.leave;

/** 請假的固定列舉（資料庫以字串保存，見 V7 的 CHECK）。 */
public final class LeaveEnums {

    private LeaveEnums() {
    }

    /** STMAX 示範假別（沒有額度；不代表正式人事制度）。 */
    public enum LeaveType { PERSONAL, SICK, ANNUAL }

    /** PENDING 之外都是終態，不再改變。 */
    public enum LeaveStatus { PENDING, APPROVED, REJECTED, WITHDRAWN }

    /** 審核人的來源：送出時是指定設定或直屬主管；ADMIN 改派後為 REASSIGNED。 */
    public enum ApproverSource { DESIGNATED, MANAGER, REASSIGNED }

    /** 清單篩選。 */
    public enum StatusFilter { ALL, PENDING, APPROVED, REJECTED, WITHDRAWN }

    public enum HistoryAction { SUBMITTED, APPROVED, REJECTED, WITHDRAWN, REASSIGNED, APPROVER_SETTING_CHANGED }
}
