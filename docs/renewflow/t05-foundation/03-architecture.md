# T05 前置：架構

> **T05 前置子任務**：最小組織與員工管理。

## 1. 位置

模組化單體不變。組織資料屬於「身分與組織」領域：員工主資料仍在 `identity`（`Employee`、登入、`ActiveAccountFilter`），組織管理放在新套件 `com.renewflow.eip.organization`。

```
Controller（HTTP 邊界、DTO 驗證）
  └─ OrganizationController            /api/admin/departments/**、/api/admin/employees/**
Service（規則、交易、鎖）
  ├─ DepartmentService                 建立、清單、改名、停用／啟用、歷程
  ├─ EmployeeAdminService              清單、建立、修改（角色、啟用、部門、主管）、歷程
  ├─ OrganizationLock                  pg_advisory_xact_lock（交易級，固定鍵）
  ├─ OrganizationAuditLog              歷程的唯一寫入入口（同交易）
  ├─ OrganizationHistoryReader         歷程轉回應（補操作者、解析 JSON）
  └─ StmaxOrganizationSeedRunner       dev 專用示範組織初始化（@Profile("dev")、另一個開關）
Repository（條件式 SQL）
  ├─ DepartmentRepository              ON CONFLICT DO NOTHING RETURNING id、版本條件 UPDATE
  ├─ EmployeeAdminRepository           同一張 employee 表的組織管理讀寫
  └─ OrganizationHistoryRepository
```

建構子注入；Entity 不對外，回應一律是 `OrganizationDtos` 的 record。

## 2. 與既有模組的關係

| 既有部分 | 本批的使用方式 |
|---|---|
| `Employee` Entity | 新增 `departmentId`、`managerId`、`version` 三個**唯讀**欄位（`insertable/updatable = false`）。只有組織管理的原生 SQL 會寫，避免其他 JPA `save` 用舊值覆寫 |
| `SecurityConfig` | 不需修改：`/api/admin/**` 已要求 ROLE_ADMIN |
| `ActiveAccountFilter`／`AccountStatusService` | 不需修改：每個受保護請求都比對資料庫的啟用狀態與角色，停用或改角色後舊 Session 自動失效 |
| `PasswordEncoder` | 建立員工時雜湊初始密碼（與登入同一個 bean） |
| `StmaxDemoSeedRunner` | 加上 `@Order(100)`，確保帳號先建立；組織初始化為 `@Order(200)` |
| 出勤、工時 | 完全不變；它們不讀部門或主管，也不取組織鎖 |

## 3. 交易與鎖的流程（修改員工）

```
PUT /api/admin/employees/{id}
 → ActiveAccountFilter：操作者仍啟用且仍是 ADMIN
 → Controller：@Valid（必填、長度）
 → EmployeeAdminService.update（@Transactional）
     1. requireAdmin（縱深防禦）
     2. 正規化顯示名稱、原因
     3. OrganizationLock.acquire()          ← 其他組織寫入在這裡排隊
     4. 讀目標員工（最新）→ 版本比對（409 EMPLOYEE_CHANGED）
     5. 計算變更；沒有變更 400；需要原因 400
     6. 自己 403 → 部門 409 → 主管（自己 400、無效 409、循環 409）→ 有部屬 409 → 最後 ADMIN 409
     7. 版本條件 UPDATE（version + 1）
     8. OrganizationAuditLog.employee（同交易；失敗就整個回滾）
 → 交易提交，鎖自動釋放
```

## 4. 前端

`#/admin/organization`「組織與員工」（只對 ADMIN 顯示入口）：

| 檔案 | 內容 |
|---|---|
| `pages/OrganizationAdminPage.tsx` | 分頁（員工／部門），非 ADMIN 顯示「只有管理員可以使用」 |
| `organization/EmployeesPanel.tsx` | 搜尋、狀態篩選、分頁、清單、開啟對話框 |
| `organization/EmployeeDialogs.tsx` | 建立（含初始密碼與交付說明）、修改（變更摘要、原因、衝突保留輸入） |
| `organization/DepartmentsPanel.tsx` | 建立、清單、修改／停用／重新啟用 |
| `organization/OrgHistoryDialog.tsx` | 員工或部門的歷程 |
| `api/organization.ts` | API 呼叫與型別（沒有任何密碼回應欄位） |

不新增套件。
