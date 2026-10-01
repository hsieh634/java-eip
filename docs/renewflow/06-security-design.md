# 安全設計（T02）

## 威脅與對應

| 威脅 | 對應 | 位置 |
|---|---|---|
| 竊取憑證（XSS 讀 Cookie） | Session Cookie `HttpOnly`；前端不存任何憑證 | `application.yml` |
| 跨站請求偽造（CSRF） | 所有寫入要求 `X-XSRF-TOKEN`；Cookie `SameSite=Lax` 當第二層 | `SecurityConfig`、`SpaCsrfTokenRequestHandler`、`CsrfCookieFilter` |
| Session Fixation | 登入成功時換發 Session ID（`ChangeSessionIdAuthenticationStrategy`） | `SessionLoginSupport.establish` |
| 明文密碼外洩 | 只存 `{bcrypt}` 雜湊；回應與 log 不含密碼 | `PasswordEncoder` |
| 帳號列舉 | 帳號不存在／密碼錯誤／停用都回同一個 401 訊息；Spring 的 `DaoAuthenticationProvider` 對不存在的帳號仍會執行一次雜湊以減少時間差 | `AuthService.authenticate`、`GlobalExceptionHandler` |
| 越權取得他人資料 | `/me` 的員工 ID 取自 Session 內的登入身份，不接受 ID 參數 | `AuthController.me`、`AuthService.getCurrentUser` |
| 密碼被 bcrypt 截斷（不同密碼視為相同） | 密碼上限以 **UTF-8 位元組 72** 驗證（不是字元數），在認證之前拒絕；不 trim、不截斷、不預雜湊 | `LoginRequest`、`@MaxUtf8Bytes` |
| 認證機制故障被誤判成帳密錯誤 | 資料庫等內部故障回 500 `INTERNAL_ERROR`，不回 401；伺服器 log 記錄原因，回應不含細節 | `AuthService.authenticate`、`AuthenticationUnavailableException`、`GlobalExceptionHandler` |
| 傳輸竊聽 | 正式設定 Cookie `Secure=true`（只經 HTTPS 傳送）；dev profile 才關閉 | `application.yml` / `application-dev.yml` |
| 暴力破解 | **本輪未實作**（假設 A-06） | — |

## 認證流程

1. 前端 `GET /api/auth/me` → 401，但取得 `XSRF-TOKEN` Cookie。
2. 前端 `POST /api/auth/login`（帶 `X-XSRF-TOKEN`）。
3. `CsrfFilter` 驗證 token；失敗 → `AccessDeniedHandler` → 403 JSON。
4. `AuthController.login` → 先經 Bean Validation（含密碼位元組上限）→ `AuthService.authenticate`：`AuthenticationManager.authenticate` → `DaoAuthenticationProvider` → `EmployeeUserDetailsService` → Repository → 比對雜湊。
5. 成功：`SessionLoginSupport.establish` 換發 Session ID、輪替 CSRF token、把 `SecurityContext` 存入 Session，回 200。
6. 之後請求帶 `JSESSIONID` Cookie，`SecurityContextHolderFilter` 由 Session 還原登入者。

## 授權規則

```
/api/auth/login, /api/auth/logout   permitAll（仍受 CSRF 保護）
/api/**                              authenticated
其他路徑                             denyAll（SPA 靜態檔由 Vite／日後同源部署提供，不經此後端）
```

本輪沒有「需要特定角色」的端點，因此沒有 `hasRole` 規則；角色已進入 `GrantedAuthority`（`ROLE_EMPLOYEE` 等），後續階段使用。**不為了展示而新增假的管理員端點。**

## 錯誤回應（皆為 JSON）

| 情況 | 狀態 | code |
|---|---|---|
| 未登入存取受保護路徑 | 401 | `UNAUTHENTICATED` |
| 帳密錯誤 | 401 | `BAD_CREDENTIALS` |
| CSRF 失敗／權限不足 | 403 | `CSRF_REJECTED`／`FORBIDDEN` |
| 輸入格式錯誤（含密碼超過 72 UTF-8 bytes） | 400 | `VALIDATION_FAILED` |
| 認證機制故障／未預期錯誤 | 500 | `INTERNAL_ERROR`（不含細節，細節只記在伺服器 log） |

## Cookie 設定

| Cookie | HttpOnly | Secure | SameSite | 說明 |
|---|---|---|---|---|
| JSESSIONID | 是 | 正式 true／dev false | Lax | Session 識別 |
| XSRF-TOKEN | **否**（SPA 必須讀它） | 同上 | Lax | CSRF token；它不是登入憑證，單獨取得無法冒用身份 |

## 員工停用與 Session：目前的實際行為

- 停用（`enabled=false`）後**不能再登入**。
- 已登入者的既有 Session **不會被主動中斷**；它會在**下一次 `GET /api/auth/me`** 重新查資料庫時被發現並作廢（回 401 並使 Session 失效）。
- （T02 當時）除 `/me` 外沒有其他受保護的業務 API，所以尚無「逐請求檢查帳號有效性」的機制。**T03 起已改為：每個已驗證的請求都會由 `ActiveAccountFilter` 重新確認，停用／刪除／角色變更後的下一個受保護請求即被拒絕並作廢 Session。**

### T03 前置事項——**已於 T03 完成**（見 [`t03/04-security-design.md`](t03/04-security-design.md)：`ActiveAccountFilter`）

以下是 T02 當時記錄的需求（保留作為背景）：

T03 起會有**寫入**型 API（打卡等）。若只依賴 Session 內的快照，被停用的員工在 Session 到期前仍可寫入資料。T03 需要一個**所有受保護 API 共用**的帳號有效性檢查（候選做法：Security Filter／`HandlerInterceptor` 依 Session 內的員工 ID 查詢 `enabled`，或由共用的 `CurrentEmployeeService.requireActive()` 在每個 Service 入口呼叫），並為「停用後立即無法寫入」補整合測試。做法在 T03 的 design 再決定，需同時考慮每請求多一次查詢的成本。

## 已知限制

- 無登入失敗鎖定、無密碼政策、無改密碼流程。
- Session 存在記憶體，重啟即失效；多機部署需外部 Session 儲存。
- 無稽核紀錄。
