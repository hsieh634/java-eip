# T05：資料模型（V7）

> 2026-10-13 最新擴充：[T05.1 設計](../t05-1/design.md)。V9 新增業務單／不可覆寫的提交版本、有效內容指標、持久化後續提交去重及限定出勤證據；新 API `POST /api/leave/me/{sourceId}/revisions`。核准後變更／銷假必須重新審核，核准前原有效內容不變；舊章節為原階段規格。

V1～V6 不修改；新增 `V7__leave_requests.sql`。不建立任何預設申請或設定，不修改既有資料。

## 1. leave_approver_setting（指定請假審核人）

| 欄位 | 型別 | 說明 |
|---|---|---|
| employee_id | bigint PK → employee | 申請人（每人最多一列） |
| approver_id | bigint NULL → employee | 指定審核人；NULL＝已移除指定、採直屬主管 |
| version | bigint NOT NULL | 第一次設定為 1，之後每次 +1；沒有列＝版本 0 |
| updated_by | bigint NOT NULL → employee | 最後修改者 |
| updated_at | timestamptz NOT NULL | |

`ck_leave_setting_not_self`：`approver_id IS NULL OR approver_id <> employee_id`。

## 2. leave_request

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | bigint identity PK | |
| employee_id | bigint NOT NULL → employee | 申請人（由登入身分決定） |
| request_id | uuid NOT NULL | 前端的提交識別碼；`uq_leave_request_employee_request (employee_id, request_id)` |
| leave_type | varchar(20) | CHECK IN (`PERSONAL`,`SICK`,`ANNUAL`) |
| work_date | date | 台北業務日期 |
| start_time／end_time | time | CHECK：秒為 0、分鐘為 0 或 30、`end_time > start_time` |
| minutes | integer | CHECK：`minutes = (end − start) 的分鐘數` 且 > 0，由後端計算 |
| reason | varchar(500) | CHECK：已去除前後空白且非空 |
| status | varchar(20) | CHECK IN (`PENDING`,`APPROVED`,`REJECTED`,`WITHDRAWN`) |
| approver_id | bigint NOT NULL → employee | 審核人快照；`ck_leave_request_not_self_approver`：`approver_id <> employee_id` |
| approver_source | varchar(20) | CHECK IN (`DESIGNATED`,`MANAGER`,`REASSIGNED`) |
| decided_by／decided_at／decision_reason | | 結案資訊；CHECK：PENDING 時三者皆為 NULL、終態時 `decided_by`／`decided_at` 必填；REJECTED 時原因必填 |
| version | bigint NOT NULL DEFAULT 0 | 核准、退回、撤回、改派各 +1 |
| created_at／updated_at | timestamptz | |

索引：
- `ix_leave_request_employee_date (employee_id, work_date)`：重疊查詢與本人清單。
- `ix_leave_request_approver_status (approver_id, status)`：審核清單。

**重疊**沒有資料庫限制（見 D-73），由後端在組織鎖內查詢：同員工、同日期、狀態 PENDING／APPROVED、`start_time < 新結束 AND end_time > 新開始`。

## 3. leave_history（只新增）

| 欄位 | 說明 |
|---|---|
| leave_request_id | 申請（申請相關動作） |
| subject_employee_id | 被設定的員工（`APPROVER_SETTING_CHANGED`） |
| action | `SUBMITTED`／`APPROVED`／`REJECTED`／`WITHDRAWN`／`REASSIGNED`／`APPROVER_SETTING_CHANGED` |
| from_status／to_status | 狀態前後（設定變更時為 NULL） |
| before_approver_id／after_approver_id | 審核人前後（外鍵） |
| reason | 原因 |
| actor_id | NOT NULL → employee |
| occurred_at | |

`ck_leave_history_target`：設定變更只有 `subject_employee_id`；其他動作只有 `leave_request_id`。
觸發器 `trg_leave_history_immutable` 沿用 `reject_append_only_change()`。沒有密碼或其他個資欄位。

## 4. 升級

`LeaveSchemaTest`：
- 乾淨庫可建立 V1～V7。
- 在有 V6 資料（員工含部門與主管、組織歷程、出勤、工時、專案…）的庫升級 V7 後，既有表逐列相同，新表為空。
