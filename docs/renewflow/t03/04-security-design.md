# T03 安全設計

承襲 `../06-security-design.md`（Session、HttpOnly Cookie、CSRF、登入／登出 token 處理皆不變）。本文件新增兩件事：資料所有權，以及 T02 記錄的「共用帳號有效性檢查」。

## 1. 資料所有權

- Controller 以 `@AuthenticationPrincipal EmployeeUserDetails` 取得員工 ID，傳給 Service。沒有任何端點接受 `employeeId`。
- Repository 的所有查詢與更新都以 `employee_id = :登入者` 為條件；沒有「依 ID 讀取別人紀錄」的方法。
- 測試以兩位員工驗證隔離，並嘗試在本文、查詢字串塞入別人的 ID（被忽略）。

## 2. 共用帳號有效性檢查（`ActiveAccountFilter`）

**問題**：T02 只在 `/me` 查資料庫。Session 內是登入當時的快照，被停用的員工在 Session 到期前仍能呼叫寫入 API。

**做法**：在 Spring Security Filter Chain 中、授權（`AuthorizationFilter`）之前，對每個「已驗證的請求」查一次資料庫：

| 狀態 | 處理 |
|---|---|
| 員工存在、啟用、角色與 Session 快照相同 | 放行 |
| 員工不存在或已停用 | 使 Session 失效、清除 SecurityContext，回 401 `UNAUTHENTICATED`；**不進入 Controller／Service，不讀寫任何資料** |
| 角色與快照不同 | 同上（Session 的權限已過時，需重新登入取得新權限） |
| 查詢時發生例外（資料庫故障） | 回 500 `INTERNAL_ERROR`；**不使 Session 失效**，也不誤報成「帳號停用」或「密碼錯誤」 |

- 不處理：未驗證（匿名）請求；`POST /api/auth/login`（讓有過期 Session 的使用者仍可重新登入）。登出由更早的 `LogoutFilter` 處理。
- **為什麼放在 Filter**：一個地方、所有受保護 API 自動涵蓋，後續階段不必記得在每個 Service 呼叫；也確保「被拒絕的請求不會進入交易」。
- **查詢成本**：每個已驗證請求多一次以主鍵查詢的單列讀取（`select enabled, role from employee where id=?`），約毫秒級。刻意**不快取**：快取會讓停用生效延遲。若未來成為瓶頸，再評估極短 TTL 並明確記錄延遲。
- **角色變更的處理範圍**：只做到「角色與 Session 快照不同就要求重新登入」。T03 沒有任何依角色而異的端點；真正的角色授權（`hasRole`、審核關係）留到 T05。
- **與 `/me` 的關係**：`/me` 保留自己的查詢與作廢邏輯（它還要回傳最新資料），因此該路徑會多查一次，行為不變。
- 位置：`shared/security/ActiveAccountFilter`、`identity/AccountStatusService`。

## 3. 錯誤分類原則

只有「預期的業務衝突」才會變成 409。資料庫連不上、外鍵錯誤、時鐘倒退等都維持 5xx，避免被誤解成使用者的重複操作。
