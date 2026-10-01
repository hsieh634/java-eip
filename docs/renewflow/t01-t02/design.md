# Design：T01＋T02

詳細的分層、資料、API、安全設計見上層文件（`../03` ~ `../06`）。本文件只記錄實作層的設計決定。

## 後端

1. **設定分層**
   - `application.yml`：預設即「正式安全」——Cookie `Secure=true`、`ddl-auto=validate`、不含任何密碼預設值；資料庫連線資訊一律來自環境變數。
   - `application-dev.yml`：僅放寬 Cookie `Secure`、開啟 SQL log 與 dev 初始化。
   - `application-test.yml`：連到 `renewflow_test`。
   - `.env`（不進版控）：`spring.config.import=optional:file:../.env[.properties]` 讓 Spring 直接讀；同一份檔案也供 `docker compose` 使用，避免兩處設定不一致。
2. **登入實作**：`AuthController` 只處理 HTTP；`AuthService.authenticate` 執行 `AuthenticationManager.authenticate`；成功後由 `AuthController.login` 呼叫 `SessionLoginSupport.establish`，依序：換發 Session ID → 輪替 CSRF token → 存入 `SecurityContext`。這三步等同 Spring 內建表單登入 Filter 在成功後做的事，因為我們自己寫 JSON 登入，必須自己做齊。**漏掉任何一步都是漏洞或功能缺陷**：漏換發＝Session Fixation；漏存＝下一個請求變回未登入；漏輪替＝登入前的 token 延續使用。
3. **登出**：使用 `http.logout(...)`，由框架的 `SecurityContextLogoutHandler`（失效 Session）與 `CsrfLogoutHandler`（清 token）處理；成功回 204。
4. **CSRF**：`CookieCsrfTokenRepository`（`httpOnly=false`）＋官方 SPA 範式的 `SpaCsrfTokenRequestHandler` ＋ `CsrfCookieFilter`。因為 Spring Security 6 起 token 是「延遲載入」，若沒有人讀它，Cookie 不會寫出，SPA 就拿不到 token；`CsrfCookieFilter` 強制每個請求讀一次。
5. **`/me`**：不直接回傳 Session 中的快照，而是以登入者 ID 重新查詢資料庫；員工已停用或被刪除則回 401 並使 Session 失效。代價是每次 `/me` 多一次主鍵查詢，換取「**下一次 `/me` 即發現停用**」。注意這**不是**「停用後 Session 立即失效」：停用後到下一次 `/me` 之間，既有 Session 仍存在；其他受保護 API 的逐請求檢查見 `../06-security-design.md`（T03 前置事項）。
6. **認證故障與帳密錯誤分開（審查修正 R2）**：`AuthService.authenticate` 把「帳號不存在／密碼錯誤／停用」統一成 `InvalidCredentialsException`（401 `BAD_CREDENTIALS`）；把 `InternalAuthenticationServiceException`（例如資料庫連不上）改丟 `AuthenticationUnavailableException`（**刻意不屬於 Spring Security 的 AuthenticationException**），由 `GlobalExceptionHandler` 記錄完整原因並回 500 `INTERNAL_ERROR`。原因：原本直接重新丟出的例外屬於 `AuthenticationException`，會被交還給 Security 的 entry point，最終變成 401。
7. **密碼位元組上限（審查修正 R4）**：自訂 Bean Validation 限制 `@MaxUtf8Bytes`（`shared/validation`）取代 `@Size(max=72)`，在進入 Service 之前以 `getBytes(UTF_8)` 計算（與 Spring Security BCrypt 同一種計算），超過回 400 `VALIDATION_FAILED`。不 trim、不截斷、不預雜湊。
8. **測試資料庫保護（審查修正 R1）**：整合測試啟動時的 Flyway `clean` 一律先經 `TestDatabaseGuard`：以資料庫實際回報的名稱（`Connection.getCatalog()`）與設定中明確允許的測試資料庫完整比對，並確認開發與測試資料庫不是同一個、測試庫名稱符合 `*_test` 命名；任何無法確認的情況都拒絕。
9. **開發帳號**：`DevSeedRunner`（`@Profile("dev")`）在 `employee` 沒有資料時建立 `admin`／`manager`／`employee` 三個帳號，密碼取自 `RENEWFLOW_DEV_SEED_PASSWORD`；未設定則不建立並記錄警告。

## 前端

- Vite＋React＋TypeScript，無其他執行期套件（開發相依：oxlint、puppeteer-core）。
- 登出不假設成功（審查修正 R3）：`AuthProvider.logout` 不論登出請求結果都重新問 `/me`，回傳 `LogoutResult`（永不 reject）；`HomePage` 在 UI 邊界顯示失敗提示並允許重試。
- 登入頁以 UTF-8 位元組提示密碼上限（審查修正 R4，`auth/passwordPolicy.ts`），僅為提早提示，不取代後端驗證。
- `src/api/http.ts`：統一的 `fetch` 包裝（同源 Cookie、寫入自動帶 CSRF、錯誤轉為 `ApiError`）。
- `src/auth/AuthProvider.tsx`：保存 `loading | anonymous | authenticated` 三態；啟動時呼叫 `/me`。
- `LoginPage`、`HomePage`（側邊選單＋頂欄＋卡片）；未啟用功能顯示「尚未啟用」，不顯示任何假數字。
- 開發代理：`vite.config.ts` 把 `/api` 轉給 `localhost:8080`。

## 設計模式

無新增（見 `../03-architecture.md` §3）。
