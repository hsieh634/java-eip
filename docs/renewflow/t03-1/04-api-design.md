# T03.1 API 設計（最終契約）

承襲 T02／T03：寫入需 `X-XSRF-TOKEN`；錯誤格式 `{ "code", "message" }`；DTO 為 record，Entity 不對外。
通用狀態碼：401 未登入（含帳號失效）、403 無權限／CSRF、404 資源不存在或不屬於你、409 狀態衝突、400 格式或業務驗證錯誤、5xx 系統故障（不被偽裝成 409）。

## 員工端（Session 身份；只能操作自己的資料）

### POST /api/attendance/requests → 201

```json
{ "type": "RESET_TODAY", "reason": "測試功能時誤按上下班，希望重新打卡" }
{ "type": "CORRECT_TIME", "workDate": "2026-09-30", "clockInTime": "09:00:00", "clockOutTime": "18:00:00", "reason": "忘記打卡" }
```

- `reason` 必填（非空白、≤ 500）。CORRECT_TIME 必填 `workDate`（≤ 今日）與 `clockInTime`；`clockOutTime` 可省略（＝無下班時間，可用於撤銷誤按的下班）。時間是**台北當地時間**（`HH:mm` 或 `HH:mm:ss`）。請求不接受員工、審核人或時間戳等欄位。
- 400 `VALIDATION_FAILED`（原因空白／只有空白／超過 500 字、缺少 `type`）；400 `BAD_REQUEST`（JSON 或時間格式無法解析，例如 `"25:00"`）；400 `INVALID_REQUEST`（CORRECT_TIME 缺日期或上班時間、日期在未來、時間在未來、下班早於上班、與目前資料相同）；409 `NO_RECORD_TO_RESET`；409 `DUPLICATE_PENDING_REQUEST`。

### GET /api/attendance/requests → `{ "requests": [ AttendanceRequestResponse ] }`

本人，新到舊，最多 50。

### POST /api/attendance/requests/{id}/cancel → 200

撤回；僅本人、僅 PENDING。404 `REQUEST_NOT_FOUND`（不存在或不是你的）；409 `REQUEST_NOT_PENDING`。

### GET /api/attendance/me/history → `{ "entries": [ AttendanceHistoryEntryResponse ] }`

本人歷程，新到舊，最多 200。

### 既有端點的變動

- `GET /api/attendance/me` 的紀錄現在**包含已作廢的紀錄**（`status=VOIDED`，保留原時間），並多了 `id`、`source`、`corrected`、`voidedAt`。同日期可有多筆（作廢＋有效）。
- 今日／上下班只針對**有效**紀錄；作廢紀錄不會被視為仍在上班，也不會觸發「昨日未完成」提醒。

## 管理端（`/api/admin/attendance/**`，需要 ROLE_ADMIN）

未登入 401；EMPLOYEE／MANAGER 403。

| 端點 | 說明 |
|---|---|
| `GET /requests?status=PENDING` | 申請清單（status 省略＝PENDING；`ALL` 為全部），含員工資訊、原值、申請值、目前值、`stale` |
| `GET /requests/{id}` | 單筆詳情；404 `REQUEST_NOT_FOUND` |
| `POST /requests/{id}/approve` `{ "comment": "選填" }` | 核准。403 `SELF_REVIEW_FORBIDDEN`；409 `REQUEST_NOT_PENDING`／`REQUEST_STALE`／`RESET_DATE_PASSED` |
| `POST /requests/{id}/reject` `{ "reason": "必填" }` | 退回。400 原因空白；403 `SELF_REVIEW_FORBIDDEN`；409 `REQUEST_NOT_PENDING` |
| `GET /employees` | 可選擇的員工清單（id、員工編號、名稱；唯讀，不是員工管理） |
| `GET /records?employeeId=&from=&to=` | 指定員工的出勤（含作廢、含 `id`／`version`）；範圍規則同員工端（≤ 31 天、省略＝本月）；404 `EMPLOYEE_NOT_FOUND` |
| `POST /records/correct` `{ employeeId, workDate, expectedVersion?, clockInTime, clockOutTime?, reason }` | 直接更正。`expectedVersion` 省略＝建立（該日必須沒有有效紀錄）。400 原因空白／時間不合理；403 `SELF_CORRECTION_FORBIDDEN`；404 `EMPLOYEE_NOT_FOUND`；409 `RECORD_CHANGED` |
| `POST /records/void` `{ employeeId, attendanceId, expectedVersion, reason }` | 作廢該員工**今日**的有效紀錄以開放重新打卡。403 `SELF_CORRECTION_FORBIDDEN`；404 `RECORD_NOT_FOUND`；409 `RECORD_CHANGED`／`VOID_DATE_PASSED` |
| `GET /history?employeeId=&from=&to=` | 歷程 |

沒有修改或刪除歷程的端點；操作者與時間一律由後端產生。

## 核准／直接更正的語意

| 動作 | 行為 |
|---|---|
| 核准 RESET_TODAY | 今日仍是申請日、版本相符 → 原紀錄作廢（保留原時間）；今日回到 NOT_STARTED |
| 核准 CORRECT_TIME（已有紀錄） | 版本相符 → 更新上下班時間，標記已更正 |
| 核准 CORRECT_TIME（原本沒有紀錄） | 該日仍無有效紀錄 → 建立 `source=MANUAL` 的有效紀錄；否則 `REQUEST_STALE` |
| 過時 | 目標紀錄版本已變、被作廢，或原本沒有紀錄現在有了 → `REQUEST_STALE`（申請維持 PENDING，員工可撤回後重新提出） |

## 回應 DTO（摘要）

- `AttendanceRequestResponse`：`id, type, status, workDate, reason, employee{id,employeeNo,displayName}, original{clockInAt,clockOutAt}|null, requested{...}|null, current{...}|null, stale, createdAt, reviewedAt, reviewerName, reviewComment`。時間為 ISO 8601 UTC。
- `AttendanceHistoryEntryResponse`：`id, operation, workDate, requestId, requestType, reason, actorName, employeeName, original, requested, after{clockInAt,clockOutAt,voided}, result, occurredAt`。
