# T05：API 設計（契約）

> 2026-10-13 最新擴充：[T05.1 設計](../t05-1/design.md)。V9 新增業務單／不可覆寫的提交版本、有效內容指標、持久化後續提交去重及限定出勤證據；新 API `POST /api/leave/me/{sourceId}/revisions`。核准後變更／銷假必須重新審核，核准前原有效內容不變；舊章節為原階段規格。

沿用既有規則：
- 寫入需要 `X-XSRF-TOKEN`。
- 錯誤格式為 `{ code, message, …必要附加欄位 }`。
- DTO 是 record，不公開 Entity。
- 時間戳為 ISO 8601（UTC）；`workDate` 是台北業務日期 `YYYY-MM-DD`；`startTime`／`endTime` 是 `HH:mm`。
- 所有端點都要登入（401），並經 `ActiveAccountFilter` 確認帳號仍有效。
- 申請人、操作者、時間、狀態、審核人都由後端決定；本文中任何 `employeeId`、`status`、`minutes` 等欄位一律忽略或拒絕。

## 0. 共用

### LeaveRequestResponse
```json
{ "id": 12, "requestId": "6f1c…", "leaveType": "SICK", "workDate": "2026-10-07", "startTime": "09:00", "endTime": "12:30",
  "minutes": 210, "reason": "身體不適",
  "status": "PENDING",
  "applicant": { "id": 9, "employeeNo": "STX-003", "displayName": "Leo 陳柏宇" },
  "approver":  { "id": 6, "employeeNo": "STX-002", "displayName": "Iris 林怡安", "valid": true },
  "approverSource": "MANAGER",
  "decidedBy": null, "decidedAt": null, "decisionReason": null,
  "version": 0, "createdAt": "…", "updatedAt": "…" }
```
- `approverSource`：送出時審核人的來源，`DESIGNATED`（指定）或 `MANAGER`（直屬主管）。改派後為 `REASSIGNED`。
- `approver.valid`：審核人**目前**是否仍是啟用中的 MANAGER／ADMIN。給管理員判斷是否需要改派。
- `decisionReason`：退回原因；撤回時為撤回原因（選填）。

### LeaveHistoryEntry
```json
{ "id": 30, "action": "REASSIGNED", "fromStatus": "PENDING", "toStatus": "PENDING",
  "beforeApprover": { "id": 6, "employeeNo": "STX-002", "displayName": "Iris 林怡安" },
  "afterApprover":  { "id": 1, "employeeNo": "STX-001", "displayName": "Max" },
  "reason": "Iris 休假中", "actor": { "id": 1, "employeeNo": "STX-001", "displayName": "Max" }, "occurredAt": "…" }
```
`action`：`SUBMITTED`、`APPROVED`、`REJECTED`、`WITHDRAWN`、`REASSIGNED`、`APPROVER_SETTING_CHANGED`。

### 清單查詢參數（三種清單共用）
| 參數 | 說明 |
|---|---|
| `status` | `ALL`（預設）／`PENDING`／`APPROVED`／`REJECTED`／`WITHDRAWN` |
| `from`、`to` | 業務日期，同時提供或都不提供（不提供＝不限日期）；`from ≤ to`；最多 366 天 → 否則 400 `INVALID_DATE_RANGE` |
| `page`、`size` | 從 0 開始；預設 20、最多 50（超過以 50 計） |

回應：`{ "items": [LeaveRequestResponse], "page": 0, "size": 20, "total": 3 }`。排序：日期新到舊、開始時間新到舊。

## 1. 本人（任何已登入員工）

### GET /api/leave/me → 200
本人的申請（清單參數如上）。

### GET /api/leave/me/approver-preview → 200
```json
{ "approver": { "id": 6, "employeeNo": "STX-002", "displayName": "Iris 林怡安" }, "source": "MANAGER", "problem": null }
```
- `source`：`DESIGNATED`、`MANAGER` 或 `NONE`。
- `problem`：無法送出的原因文字；可送出時為 null。
- 只是提示，送出時後端重新判斷。

### POST /api/leave/me → 201（新建立）／200（相同 requestId＋相同內容的重送）
```json
{ "requestId": "6f1c…(UUID)", "leaveType": "SICK", "workDate": "2026-10-07", "startTime": "09:00", "endTime": "12:30", "reason": " 身體不適 " }
```
回應 `{ "replayed": false, "request": LeaveRequestResponse }`。

| 規則 | 錯誤 |
|---|---|
| 欄位缺漏、`requestId` 不是 UUID、假別不在三種內、時間格式錯誤 | 400 `VALIDATION_FAILED`／`BAD_REQUEST` |
| 時間不在半小時刻度、結束 ≤ 開始、原因去除空白後為空或超過 500 字、日期超出今天 ±365 天 | 400 `INVALID_LEAVE` |
| 同一 `requestId`、內容不同 | 409 `REQUEST_ID_CONFLICT`（不修改原申請） |
| 沒有合格審核人（無主管且無指定、指定審核人已無效） | 409 `NO_VALID_APPROVER` |
| 與本人 PENDING／APPROVED 申請重疊 | 409 `LEAVE_OVERLAP`（附 `conflictingRequestId`） |
| 取鎖後發現帳號已停用或角色已變更 | 401 `UNAUTHENTICATED`（沒有寫入） |

相同 `requestId`＋相同內容：回傳原申請（任何狀態，包括已撤回），不新增、不寫歷程，也不重新判斷審核人或重疊。

### GET /api/leave/me/{id} → 200
`{ "request": LeaveRequestResponse, "history": [LeaveHistoryEntry] }`。不是本人的 → 404 `LEAVE_NOT_FOUND`。

### POST /api/leave/me/{id}/withdraw → 200
`{ "expectedVersion": 0, "reason": "行程取消" }`（原因選填，最多 500 字）
- 不是本人 → 404。
- 已不是 PENDING → 409 `LEAVE_NOT_PENDING`（附 `status`）。
- 版本不符 → 409 `LEAVE_CHANGED`。

## 2. 審核人（目前角色是 MANAGER 或 ADMIN）

角色是 EMPLOYEE → 403 `FORBIDDEN`（清單）／404（單筆）。

### GET /api/leave/approvals → 200
`approver_id = 我` 的申請（清單參數如上；常用 `status=PENDING`）。

### GET /api/leave/approvals/{id} → 200
`{ "request", "history" }`；不是指派給我的（含已改派走的）→ 404。

### POST /api/leave/approvals/{id}/approve → 200
`{ "expectedVersion": 0 }`

### POST /api/leave/approvals/{id}/reject → 200
`{ "expectedVersion": 0, "reason": "當天有客戶會議，請改期" }`（必填、1～500 字 → 否則 400 `REASON_REQUIRED`）

核准、退回共用的錯誤：
- 不是指派給我 → 404。
- 不是 PENDING → 409 `LEAVE_NOT_PENDING`。
- 版本不符 → 409 `LEAVE_CHANGED`。

**ADMIN 也必須是目前審核人**；沒有「以 ADMIN 身分核准」的端點。

## 3. ADMIN 管理

### GET /api/admin/leave/requests → 200
所有申請。清單參數，另加 `employeeId`（選填）。

### GET /api/admin/leave/requests/{id} → 200
`{ "request", "history" }`；不存在 → 404。

### POST /api/admin/leave/requests/{id}/reassign → 200
`{ "approverId": 1, "expectedVersion": 2, "reason": "Iris 休假中" }`

| 規則 | 錯誤 |
|---|---|
| 原因必填 | 400 `REASON_REQUIRED` |
| 申請人是自己 | 403 `SELF_REASSIGN_FORBIDDEN`（請另一位 ADMIN 操作） |
| 新審核人是申請人 | 400 `APPROVER_IS_APPLICANT` |
| 新審核人與目前相同 | 400 `NO_CHANGE` |
| 新審核人不存在 | 404 `EMPLOYEE_NOT_FOUND` |
| 新審核人不是啟用中的 MANAGER／ADMIN | 409 `APPROVER_INVALID` |
| 不是 PENDING | 409 `LEAVE_NOT_PENDING` |
| 版本不符 | 409 `LEAVE_CHANGED` |

成功後狀態不變、版本 +1，並新增 `REASSIGNED` 歷程，記錄原審核人、新審核人、原因與操作者。

### GET /api/admin/leave/approver-settings → 200
目前所有「已指定」的設定：`{ "settings": [ApproverSettingResponse] }`。

### GET /api/admin/leave/approver-settings/{employeeId} → 200
```json
{ "employee": { "id": 1, "employeeNo": "STX-001", "displayName": "Max", "role": "ADMIN", "enabled": true },
  "designatedApprover": { "id": 6, "employeeNo": "STX-002", "displayName": "Iris 林怡安", "valid": true },
  "manager": null,
  "effective": { "approver": { "id": 6, "employeeNo": "STX-002", "displayName": "Iris 林怡安" }, "source": "DESIGNATED", "problem": null },
  "version": 1, "updatedAt": "…" }
```
- 沒有設定：`designatedApprover: null`、`version: 0`。
- `manager`：組織的直屬主管（唯讀；不會被這個設定修改）。`effective`：此刻送出時會採用的審核人（與 `approver-preview` 相同規則）。
- 員工不存在 → 404 `EMPLOYEE_NOT_FOUND`。

### PUT /api/admin/leave/approver-settings/{employeeId} → 200
`{ "approverId": 6, "expectedVersion": 0, "reason": "Max 沒有直屬主管，由 Iris 審核" }`（`approverId: null`＝移除指定，改回採用直屬主管）

| 規則 | 錯誤 |
|---|---|
| 原因必填 | 400 `REASON_REQUIRED` |
| 指定自己為自己的審核人 | 400 `APPROVER_IS_APPLICANT` |
| 審核人不是啟用中的 MANAGER／ADMIN | 409 `APPROVER_INVALID`（不存在則 404） |
| 與目前相同 | 400 `NO_CHANGE` |
| 版本不符 | 409 `APPROVER_SETTING_CHANGED` |

只影響之後送出的申請，不改既有申請，也不改 `employee.manager_id`。

### GET /api/admin/leave/approver-settings/{employeeId}/history → 200
`{ "history": [LeaveHistoryEntry] }`（`action = APPROVER_SETTING_CHANGED`）。

## 4. 不提供

刪除申請、修改申請內容、取消已核准的申請、ADMIN 代替審核人核准、代他人送出、額度查詢、附件。
