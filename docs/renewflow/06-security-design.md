# 安全設計（T02）

## 威脅與對應

| 威脅 | 對應 | 位置 |
|---|---|---|
| 竊取憑證（XSS 讀 Cookie） | Session Cookie `HttpOnly`；前端不存任何憑證 | `application.yml` |
| 跨站請求偽造（CSRF） | 所有寫入要求 `X-XSRF-TOKEN`；Cookie `SameSite=Lax` 當第二層 | `SecurityConfig`、`SpaCsrfTokenRequestHandler`、`CsrfCookieFilter` |
| Session Fixation | 登入成功時換發 Session ID（`ChangeSessionIdAuthenticationStrategy`） | `AuthService` |
| 明文密碼外洩 | 只存 `{bcrypt}` 雜湊；回應與 log 不含密碼 | `PasswordEncoder` |
| 帳號列舉 | 帳號不存在／密碼錯誤／停用都回同一個 401 訊息；Spring 的 `DaoAuthenticationProvider` 對不存在的帳號仍會執行一次雜湊以減少時間差 | `AuthController` |
| 越權取得他人資料 | `/me` 的員工 ID 取自 Session 內的登入身份，不接受 ID 參數 | `AuthService` |
| 傳輸竊聽 | 正式設定 Cookie `Secure=true`（只經 HTTPS 傳送）；dev profile 才關閉 | `application.yml` / `application-dev.yml` |
| 暴力破解 | **本輪未實作**（假設 A-06） | — |

## 認證流程

1. 前端 `GET /api/auth/me` → 401，但取得 `XSRF-TOKEN` Cookie。
2. 前端 `POST /api/auth/login`（帶 `X-XSRF-TOKEN`）。
3. `CsrfFilter` 驗證 token；失敗 → `AccessDeniedHandler` → 403 JSON。
4. `AuthController` → `AuthService.login`：`AuthenticationManager.authenticate` → `DaoAuthenticationProvider` → `EmployeeUserDetailsService` → Repository → 比對雜湊。
5. 成功：換發 Session ID、輪替 CSRF token、把 `SecurityContext` 存入 Session、回 200。
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
| 輸入格式錯誤 | 400 | `VALIDATION_FAILED` |
| 未預期錯誤 | 500 | `INTERNAL_ERROR`（不含細節，細節只記在伺服器 log） |

## Cookie 設定

| Cookie | HttpOnly | Secure | SameSite | 說明 |
|---|---|---|---|---|
| JSESSIONID | 是 | 正式 true／dev false | Lax | Session 識別 |
| XSRF-TOKEN | **否**（SPA 必須讀它） | 同上 | Lax | CSRF token；它不是登入憑證，單獨取得無法冒用身份 |

## 已知限制

- 無登入失敗鎖定、無密碼政策、無改密碼流程。
- Session 存在記憶體，重啟即失效；多機部署需外部 Session 儲存。
- 無稽核紀錄。
