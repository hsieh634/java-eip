# T05 前置：API 設計（契約）

> **T05 前置子任務**：最小組織與員工管理。不包含請假。

承襲既有規則：寫入需 `X-XSRF-TOKEN`；錯誤格式 `{ "code", "message" }`；DTO 為 record，Entity 不對外；時間為 ISO 8601（UTC）。
所有端點都在 `/api/admin/**`：URL 規則要求 ROLE_ADMIN，Service 內再檢查；`ActiveAccountFilter` 確認帳號仍有效、角色未變。未登入 401；EMPLOYEE／MANAGER 403。
**任何回應都不含密碼或密碼雜湊。**

## 1. 部門

### GET /api/admin/departments → 200
全部部門（含停用），依代碼排序，附啟用員工數。
```json
{ "departments": [ { "id": 2, "code": "PROJECT", "name": "專案服務", "active": true, "activeEmployeeCount": 4, "version": 0,
                     "createdAt": "…", "updatedAt": "…" } ] }
```

### POST /api/admin/departments → 201
`{ "code": " project ", "name": "專案服務" }`
- 代碼去除前後空白並轉大寫後 2～32 字元，只允許 `A-Z 0-9 _ -`；名稱去除空白後 1～100 字。→ 400 `INVALID_DEPARTMENT`
- 代碼已存在 → 409 `DEPARTMENT_CODE_DUPLICATE`

### PUT /api/admin/departments/{id} → 200
`{ "name": "專案服務", "active": false, "expectedVersion": 0, "reason": "部門整併" }`
- 代碼不可修改（本文沒有 `code`）。
- 變更啟用狀態時 `reason` 必填 → 否則 400 `REASON_REQUIRED`；只改名稱不必填。
- 停用時仍有**啟用中**的員工隸屬 → 409 `DEPARTMENT_HAS_ACTIVE_EMPLOYEES`（附 `activeEmployeeCount`）。
- 沒有變更 → 400 `NO_CHANGE`；不存在 → 404 `DEPARTMENT_NOT_FOUND`；版本不符 → 409 `DEPARTMENT_CHANGED`。

### GET /api/admin/departments/{id}/history → 200
`{ "entries": [ OrganizationHistoryEntry ] }`，新到舊，最多 100。

## 2. 員工

### GET /api/admin/employees?q=&status=&page=&size= → 200
- `q`：顯示名稱、帳號、員工編號的部分比對（不分大小寫），選填。
- `status`：`ALL`（預設）／`ACTIVE`／`INACTIVE`。
- `page` 從 0 開始；`size` 預設 20、最大 50（超過以 50 計）。依員工編號排序。
```json
{ "items": [ EmployeeAdminResponse ], "page": 0, "size": 20, "total": 37 }
```

### GET /api/admin/employees/{id} → 200（`EmployeeAdminResponse`）／404 `EMPLOYEE_NOT_FOUND`

```json
{ "id": 9, "employeeNo": "STX-003", "username": "stmax_leo", "displayName": "Leo 陳柏宇", "role": "EMPLOYEE", "enabled": true,
  "department": { "id": 2, "code": "PROJECT", "name": "專案服務", "active": true },
  "manager": { "id": 6, "employeeNo": "STX-002", "displayName": "Iris 林怡安", "role": "MANAGER", "enabled": true },
  "subordinateCount": 0, "version": 1, "createdAt": "…", "updatedAt": "…" }
```

### GET /api/admin/employees/manager-options → 200
可被指定為主管的人：**啟用中**的 MANAGER 或 ADMIN（最多 200），依員工編號排序。
`{ "options": [ { "id": 6, "employeeNo": "STX-002", "displayName": "Iris 林怡安", "role": "MANAGER" } ] }`

### POST /api/admin/employees → 201
```json
{ "employeeNo": "stx-007", "username": " New.User ", "displayName": "新同事", "role": "EMPLOYEE",
  "departmentId": 2, "managerId": 6, "initialPassword": "…", "reason": "新進人員" }
```
| 規則 | 錯誤 |
|---|---|
| 員工編號：去除空白並轉大寫，2～20 字元，`A-Z 0-9 _ -` | 400 `INVALID_EMPLOYEE` |
| 帳號：與登入相同的正規化（去除空白、轉小寫），2～50 字元，`a-z 0-9 . _ -` | 400 `INVALID_EMPLOYEE` |
| 顯示名稱：去除空白後 1～100 字 | 400 `INVALID_EMPLOYEE` |
| 角色：`EMPLOYEE`／`MANAGER`／`ADMIN` | 400 `VALIDATION_FAILED` 或 `BAD_REQUEST` |
| 初始密碼：必填、不 trim、不截斷，最多 72 個 UTF-8 位元組（與登入相同） | 400 `VALIDATION_FAILED` |
| 員工編號或帳號已存在 | 409 `EMPLOYEE_NO_DUPLICATE`／`USERNAME_DUPLICATE` |
| 部門不存在／停用 | 404 `DEPARTMENT_NOT_FOUND`／409 `DEPARTMENT_INACTIVE` |
| 主管不存在／不是啟用中的 MANAGER 或 ADMIN | 404 `EMPLOYEE_NOT_FOUND`／409 `MANAGER_INVALID` |

部門與主管可以留空。建立一律為啟用狀態。`reason` 選填。密碼只以 PasswordEncoder 雜湊後保存。

### PUT /api/admin/employees/{id} → 200
```json
{ "displayName": "Leo 陳柏宇", "role": "EMPLOYEE", "enabled": true, "departmentId": 2, "managerId": 6,
  "expectedVersion": 1, "reason": "調整主管" }
```
- 員工編號、帳號、密碼不在本文中（本批不提供修改）。
- `departmentId`／`managerId` 為 null 表示「未指派」（可用來移除）。

| 規則 | 錯誤 |
|---|---|
| 角色、啟用、部門或主管有變更時 `reason` 必填（只改顯示名稱不必） | 400 `REASON_REQUIRED` |
| 沒有任何變更 | 400 `NO_CHANGE` |
| 版本不符 | 409 `EMPLOYEE_CHANGED` |
| 自己停用自己或把自己改成非 ADMIN | 403 `SELF_ADMIN_CHANGE_FORBIDDEN` |
| 停用或降級後會沒有任何啟用中的 ADMIN | 409 `LAST_ADMIN` |
| 仍是其他員工的直屬主管時停用，或改成 EMPLOYEE | 409 `MANAGER_HAS_SUBORDINATES`（附 `subordinateCount`） |
| 主管是自己 | 400 `MANAGER_SELF` |
| 主管會形成循環（直接或間接） | 409 `MANAGER_CYCLE` |
| 主管不是啟用中的 MANAGER／ADMIN | 409 `MANAGER_INVALID` |
| 新指派的部門停用；**重新啟用**員工時其部門已停用 | 409 `DEPARTMENT_INACTIVE` |
| 員工／部門／主管不存在 | 404 |

成功時版本 +1；停用或角色變更後，對方的舊 Session 在下一個受保護請求得到 401（`ActiveAccountFilter`）。

### GET /api/admin/employees/{id}/history → 200
`{ "entries": [ OrganizationHistoryEntry ] }`，新到舊，最多 100。

## 3. 操作歷程（OrganizationHistoryEntry）

```json
{ "id": 31, "action": "EMPLOYEE_UPDATED", "reason": "調整主管",
  "changes": [ { "field": "manager", "before": { "id": 1, "label": "STX-001 Max" }, "after": { "id": 6, "label": "STX-002 Iris 林怡安" } } ],
  "actor": { "id": 1, "employeeNo": "STX-001", "displayName": "Max" }, "source": "ADMIN", "occurredAt": "…" }
```
- `action`：`DEPARTMENT_CREATED`、`DEPARTMENT_UPDATED`、`EMPLOYEE_CREATED`、`EMPLOYEE_UPDATED`。
- `field`：`code`、`name`、`active`（部門）；`employeeNo`、`username`、`displayName`、`role`、`enabled`、`department`、`manager`（員工）。**不會出現密碼。**
- `source`：`ADMIN`（有操作者）或 `SYSTEM_DEMO_SEED`（dev 示範初始化，`actor` 為 null）。
- 沒有修改或刪除歷程的 API。

## 4. 不提供

刪除部門或員工、修改員工編號／帳號、修改或重設密碼、部門上下層、多位主管、主管查看部屬資料、請假。
