# T04 API 設計（契約）

承襲既有規則：寫入需 `X-XSRF-TOKEN`；錯誤格式 `{ "code", "message" }`（部分錯誤附加欄位，見下）；DTO 為 record，Entity 不對外；時間點為 ISO 8601（UTC），業務日期為 `YYYY-MM-DD`（Asia/Taipei）。
所有受保護請求先經 `ActiveAccountFilter`（停用、刪除、角色變更 → 401）。

## 1. 管理端：專案（`/api/admin/projects`，需要 ROLE_ADMIN）

未登入 401；EMPLOYEE／MANAGER 403（URL 規則＋Service 內再檢查）。

### GET /api/admin/projects → 200

全部專案（含停用），依代碼排序。

```json
{ "projects": [
  { "id": 3, "code": "TP-WEB", "name": "TeamPortal 網站", "description": "前端與後端開發", "active": true, "version": 2,
    "createdAt": "2026-10-02T01:00:00Z", "updatedAt": "2026-10-02T03:00:00Z" } ] }
```

### POST /api/admin/projects → 201

```json
{ "code": " tp-web ", "name": "TeamPortal 網站", "description": "選填" }
```

| 規則 | 錯誤 |
|---|---|
| `code`、`name` 必填；`description` ≤ 1000 字 | 400 `VALIDATION_FAILED` |
| 代碼去除前後空白並轉大寫後：2～32 字元，只允許 `A-Z 0-9 _ -` | 400 `INVALID_PROJECT` |
| 名稱去除前後空白後 1～100 字 | 400 `INVALID_PROJECT` |
| 代碼已存在（資料庫唯一限制裁決，含並行建立） | 409 `PROJECT_CODE_DUPLICATE` |

新專案預設啟用、版本 0。回傳建立的專案。

### PUT /api/admin/projects/{id} → 200

修改名稱、說明、啟用狀態（停用／重新啟用也用這個端點）。**代碼不可修改**（本文沒有 `code` 欄位；多送會被忽略）。

```json
{ "name": "TeamPortal 網站", "description": null, "active": false, "expectedVersion": 2 }
```

| 規則 | 錯誤 |
|---|---|
| `name`、`active`、`expectedVersion` 必填 | 400 `VALIDATION_FAILED` |
| 名稱 1～100 字（去除空白後）、說明 ≤ 1000 | 400 `INVALID_PROJECT` |
| 與目前完全相同 | 400 `INVALID_PROJECT` |
| 專案不存在 | 404 `PROJECT_NOT_FOUND` |
| 版本不符（已被其他管理員修改） | 409 `PROJECT_CHANGED` |

成功時版本 +1。停用不刪除專案，也不影響既有工時。

## 2. 一般使用者：可填報的專案

### GET /api/projects → 200

已登入即可。只回傳**啟用中**的專案，依代碼排序。

```json
{ "projects": [ { "id": 3, "code": "TP-WEB", "name": "TeamPortal 網站", "description": "…" } ] }
```

## 3. 本人工時（`/api/timesheets/me`）

員工一律取自 Session；本文中的 `employeeId`、`createdAt`、`version` 等欄位都會被忽略，不能指定他人。

### GET /api/timesheets/me?from=&to= → 200

- `from`／`to` 同時提供或同時省略（省略＝台北「今天」所在月份）；起日不可晚於迄日；最多 31 個日曆日（含起訖）。否則 400 `INVALID_DATE_RANGE`。
- 紀錄依日期新到舊、同日期依 ID 新到舊。
- `totalMinutes` 與 `daily` 只統計查詢範圍內的紀錄；`daily` 只列出有紀錄的日期，新到舊。

```json
{
  "from": "2026-10-01", "to": "2026-10-31", "timeZone": "Asia/Taipei",
  "entries": [
    { "id": 12, "workDate": "2026-10-02", "project": { "id": 3, "code": "TP-WEB", "name": "TeamPortal 網站", "active": true },
      "description": "撰寫 API 文件", "minutes": 90, "version": 0,
      "createdAt": "2026-10-02T02:00:00Z", "updatedAt": "2026-10-02T02:00:00Z" } ],
  "totalMinutes": 90,
  "daily": [ { "date": "2026-10-02", "minutes": 90 } ]
}
```

### GET /api/timesheets/me/{id} → 200

單筆（含版本，給修改表單用）。不存在或**不是自己的** → 404 `TIMESHEET_NOT_FOUND`（不區分，避免洩漏他人紀錄是否存在）。

### POST /api/timesheets/me → 201

```json
{ "requestId": "5d9b2c8e-0e7a-4d5b-9a51-2f0e7b6a1c33", "workDate": "2026-10-02", "projectId": 3,
  "description": "撰寫 API 文件", "minutes": 90 }
```

`requestId`：前端在「開始一筆新的新增」時產生的 UUID。網路結果不明時，**同一次提交重送必須沿用同一個 requestId**。

### PUT /api/timesheets/me/{id} → 200

```json
{ "workDate": "2026-10-01", "projectId": 3, "description": "撰寫 API 文件（補充）", "minutes": 105, "expectedVersion": 0 }
```

成功時版本 +1。可換日期、換專案；**若維持原本的專案，該專案即使已停用也可修改**；改掛到其他專案時，目標必須啟用。

### 工時驗證與錯誤（POST／PUT 共用）

| 規則 | 錯誤 |
|---|---|
| 欄位缺少、`minutes` 不在 15～1440、工作內容只有空白 | 400 `VALIDATION_FAILED` |
| `minutes` 是小數（例如 90.5，不會被截斷成 90）、`requestId` 不是 UUID、日期或數字格式錯誤 | 400 `BAD_REQUEST` |
| `minutes` 不是 15 的倍數 | 400 `INVALID_TIMESHEET` |
| 工作內容去除前後空白後超過 1000 字 | 400 `INVALID_TIMESHEET` |
| 日期晚於台北「今天」 | 400 `INVALID_TIMESHEET` |
| 修改內容與目前完全相同 | 400 `INVALID_TIMESHEET` |
| 專案不存在 | 404 `PROJECT_NOT_FOUND` |
| 專案已停用（新增；或修改時改掛到停用專案） | 409 `PROJECT_INACTIVE` |
| 該日所有紀錄合計會超過 1440 分鐘 | 409 `DAILY_LIMIT_EXCEEDED`，附 `limitMinutes`、`otherMinutes`（該日其他紀錄合計）、`requestedMinutes` |
| 同一個 `requestId` 已建立過（含並行重送） | 409 `DUPLICATE_SUBMISSION`，附 `entryId`（本人已建立的那筆）；不新增第二筆、不當作修改 |
| 紀錄不存在或不是自己的（PUT） | 404 `TIMESHEET_NOT_FOUND` |
| 版本不符（已在其他分頁修改） | 409 `TIMESHEET_CHANGED` |
| 未登入／帳號失效 | 401 |
| CSRF 缺少或錯誤 | 403 |
| 資料庫或內部故障 | 500 `INTERNAL_ERROR`（不會被偽裝成上限、重複或 401） |

```json
{ "code": "DAILY_LIMIT_EXCEEDED", "message": "2026-10-02 已記錄 22 小時 30 分，再加 2 小時會超過每日 24 小時上限",
  "limitMinutes": 1440, "otherMinutes": 1350, "requestedMinutes": 120 }
{ "code": "DUPLICATE_SUBMISSION", "message": "這次提交已經建立過（紀錄 #12），沒有重複新增", "entryId": 12 }
```

### 檢查順序（POST）

鎖住本人員工列 → 同一 `requestId` 是否已建立（是 → 409 `DUPLICATE_SUBMISSION`）→ 日期不在未來 → 鎖住並檢查專案（不存在 404、停用 409）→ 該日合計 → 原子 INSERT（`ON CONFLICT (employee_id, request_id) DO NOTHING RETURNING id`，作為最後防線）。

### 檢查順序（PUT）

鎖住本人員工列 → 讀取本人紀錄（否則 404）→ 版本（409）→ 日期 → 專案規則 → 該日合計（排除這筆自己的舊分鐘數）→ 版本條件 UPDATE（0 筆 → 409）。

## 4. 不提供

刪除／作廢工時、查看或修改他人工時、專案成員、專案篩選、匯出、批次填寫、簽核狀態。
