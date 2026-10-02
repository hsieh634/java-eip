# T04 架構

沿用模組化單體與 Controller → Service → Repository。新增兩個模組：

```
backend/src/main/java/com/renewflow/eip/
├─ project/                       ← T04
│  ├─ Project, ProjectRepository        專案 Entity（唯讀）；ON CONFLICT 新增、版本條件更新、FOR SHARE 鎖
│  ├─ ProjectService                    管理端（ADMIN）建立／清單／修改／停用；一般使用者的可填報清單
│  ├─ ProjectController                 /api/admin/projects、/api/projects
│  └─ ProjectDtos                       Create/Update 請求、管理端與一般使用者的回應
├─ timesheet/                     ← T04
│  ├─ TimesheetEntry, TimesheetEntryRepository   工時 Entity（唯讀）；員工列鎖、當日合計、ON CONFLICT 新增、版本條件更新
│  ├─ TimesheetService                  本人查詢／單筆／新增／修改（鎖定順序：員工 → 專案 → 紀錄）
│  ├─ TimesheetController               /api/timesheets/me
│  ├─ TimesheetViews                    Entity → DTO（專案名稱批次查詢）、每日合計
│  ├─ TimesheetRules                    15 分鐘、1440 上限、內容 1000 字
│  └─ TimesheetDtos
├─ attendance/DateRange（改為 public，共用）
└─ shared/web/ApiException（新）＋GlobalExceptionHandler（{code, message, …附加欄位}）

frontend/src/
├─ api/projects.ts, api/timesheets.ts
├─ lib/hours.ts（小時 ↔ 整數分鐘）、lib/requestId.ts（UUID）、lib/useLifetime.ts（掛載有效期間）
├─ pages/TimesheetPage.tsx（#/timesheets）
└─ pages/ProjectsAdminPage.tsx（#/admin/projects，僅 ADMIN 顯示入口）
```

依賴方向：`timesheet → project`（鎖住與讀取專案）、`timesheet → attendance.DateRange`；`project` 不依賴 `timesheet`。

## 完整資料流：新增一筆工時

畫面「送出」（防連按 ref；帶 requestId）→ `POST /api/timesheets/me` → Security（CSRF、Session、`ActiveAccountFilter`、`/api/** authenticated`）→
`TimesheetController.create`（`@Valid`；員工取自 Session）→ `TimesheetService.create`（`@Transactional`）：
驗證倍數、內容、未來日期 → `SELECT … FROM employee … FOR NO KEY UPDATE` → 查同一 requestId → `SELECT … FROM project … FOR SHARE`（停用 → 409）→
`SELECT sum(minutes) …`（超過 → 409）→ `INSERT … ON CONFLICT (employee_id, request_id) DO NOTHING RETURNING id` → 讀回 → DTO →
提交（釋放鎖）→ 201 → 畫面以後端資料重新查詢清單與合計，表單換新的 requestId。
