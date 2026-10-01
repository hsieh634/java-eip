# T03.1 安全設計

承襲 `../t03/04-security-design.md`（Session、HttpOnly Cookie、CSRF、`ActiveAccountFilter`）。

## 1. 授權矩陣

| 端點群 | 匿名 | EMPLOYEE | MANAGER | ADMIN |
|---|---|---|---|---|
| `/api/attendance/**`（自己的資料） | 401 | ✔（只能自己） | ✔（只能自己） | ✔（只能自己） |
| `/api/admin/**` | 401 | 403 | 403 | ✔（受職責分離限制） |

- URL 規則：`/api/admin/**` → `hasRole('ADMIN')`（`SecurityConfig`，放在 `/api/**` 之前）。
- `ActiveAccountFilter` 先於授權執行：角色被改掉（例如 admin 被降為 employee）後，舊 Session 的下一個請求直接 401，不會繼續使用舊角色的權限。
- 縱深防禦：`AttendanceAdminService` 每個公開方法開頭再檢查 `actor.getRole() == ADMIN`，否則丟 `AccessDeniedException`（→ 403）。
- 隱藏按鈕不是安全機制：前端只在 ADMIN 時顯示「出勤管理」，後端對直接呼叫一律驗證（測試以員工、主管、匿名直接呼叫）。

## 2. 職責分離

- 管理員不可核准或退回自己提交的申請（403 `SELF_REVIEW_FORBIDDEN`）。
- 管理員不可直接更正／作廢自己的出勤（403 `SELF_CORRECTION_FORBIDDEN`）。
- 開發測試：`developer`（ADMIN）可核准 `employee` 的申請，`admin` 與 `developer` 也可互相處理。

## 3. 資料所有權

- 員工端：所有查詢與寫入以 Session 的員工 ID 限定；別人的申請 ID 一律回 404（不洩漏是否存在）。
- 管理端：唯一可以指定受管理員工的地方（`employeeId` 參數），且必須通過 ADMIN 檢查。
- 操作者、審核人、時間：一律由後端依 Session 與 `Clock` 產生，請求本文中的同名欄位被忽略。

## 4. 一致性與完整性

- 核准／退回／撤回：條件更新（`WHERE status = 'PENDING'`），同一申請只會被處理一次。
- 核准與員工打卡並行：以 `version` 比對；不會覆蓋較新的打卡，也不會產生兩筆有效紀錄（部分唯一索引）。
- 修改、狀態、歷程在**同一交易**；歷程寫入失敗全部回滾。
- 歷程由資料庫觸發器保護為只新增。
- 系統故障維持 5xx，不轉成 409。

## 5. 開發帳號

- `developer`：ADMIN，**僅 dev profile** 初始化；密碼來自 `RENEWFLOW_DEV_DEVELOPER_PASSWORD`（獨立於一般員工共用密碼）。
- 沒有預設密碼：未設定時不建立，並在日誌明確提示變數名稱。`.env.example` 只放空白占位。
- 密碼（含隨機產生者）只存在不進 Git 的 `.env`，不寫入原始碼、報告或日誌。

## 6. E2E 隔離保護（補強 T03）

- 保護在**任何 migration 與初始化寫入之前**：`E2eFlywayConfig` 的 `FlywayMigrationStrategy` 檢查 Flyway 實際使用的 datasource 回報的資料庫名稱（`getCatalog()`），必須等於明確允許的測試庫且不同於開發庫；否則丟例外而**不呼叫 migrate**。種子寫入（`E2eSeedRunner`）仍有同樣檢查。
- 驗證：替身測試確認拒絕時 migrate／seed 皆未執行；另以一個**臨時的獨立資料庫**實際啟動 e2e 設定，確認啟動失敗且該資料庫沒有任何資料表（證明拒絕發生在 migration 之前），測試後刪除該臨時庫。不使用開發庫做任何破壞性驗證。

## 7. 已知限制

沒有多層審核、沒有通知、沒有登入失敗鎖定；歷程僅涵蓋申請與更正，不涵蓋一般打卡。
