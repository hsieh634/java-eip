# T05 前置：安全設計

> **T05 前置子任務**。

## 1. 權限矩陣

| 操作 | 未登入 | EMPLOYEE | MANAGER | ADMIN |
|---|---|---|---|---|
| 部門清單／建立／修改／歷程 | 401 | 403 | 403 | ✔ |
| 員工清單／明細／主管選項／歷程 | 401 | 403 | 403 | ✔ |
| 建立員工 | 401 | 403 | 403 | ✔ |
| 修改員工（名稱、角色、啟用、部門、主管） | 401 | 403 | 403 | ✔（不可停用或降級自己 → 403） |
| 刪除部門或員工、修改帳號或員工編號、修改密碼 | — | — | — | 不提供 |

兩層檢查：`/api/admin/**` 的 URL 規則（`hasRole("ADMIN")`）＋ Service 的 `requireAdmin`。角色以資料庫為準：`ActiveAccountFilter` 在每個受保護請求比對最新的啟用狀態與角色，所以「剛被降級的 ADMIN」用舊 Session 也打不進來。

**角色 ≠ 資料範圍**：MANAGER 只是角色；被指定為某人的直屬主管，本批**不會**給他任何查看或修改對方資料的權限。

## 2. 密碼

| 規則 | 作法 |
|---|---|
| 必填、無預設 | `@NotBlank`；前端也先檢查 |
| 不 trim、不截斷 | 原樣交給 `PasswordEncoder.encode`；前端原樣送出 |
| 上限 72 UTF-8 位元組 | 沿用 `@MaxUtf8Bytes(LoginRequest.PASSWORD_MAX_BYTES)` |
| 不回傳 | 回應 DTO 沒有密碼或雜湊欄位；測試檢查回應不含 `{bcrypt}`、`password` |
| 不記錄 | `CreateEmployeeRequest.toString()` 遮蔽；歷程只記欄位清單中的值，密碼不在其中；驗證錯誤只回欄位名稱 |
| 交付方式 | 由 ADMIN 自行交付；畫面明確說明沒有邀請信、沒有強制首次改密碼 |
| 測試與 E2E | 測試用固定的測試字串；E2E 每次隨機產生，不輸出、不寫檔、不截圖（輸入框預設遮蔽） |

## 3. 帳號完整性

- 至少一位啟用中的 ADMIN（並行時也成立：組織鎖）。
- ADMIN 不可停用或降級自己（避免誤把自己鎖在外面）。
- 仍是別人主管的人不可停用或降成 EMPLOYEE。
- 停用、改角色後舊 Session 立即失效（下一個受保護請求 401）；重新啟用也要重新登入。

## 4. 輸入與回應

- 清單分頁上限 50；搜尋用 `LIKE` 參數化查詢，`%`、`_` 跳脫為一般字元。
- 錯誤回應只有 `code`、`message` 與必要數字（`activeEmployeeCount`、`subordinateCount`），不含 SQL、堆疊或其他人資料。
- 寫入需要 CSRF token（沿用）。

## 5. dev 示範初始化

- 只在 `dev` profile 有這個元件；另需 `RENEWFLOW_DEMO_STMAX_ORG_ENABLED=true`，預設關閉。
- 不建立帳號、不改密碼、角色或啟用狀態；歷程來源 `SYSTEM_DEMO_SEED`、沒有操作者（不假冒 Max）。
