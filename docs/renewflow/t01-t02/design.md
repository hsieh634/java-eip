# Design：T01＋T02

詳細的分層、資料、API、安全設計見上層文件（`../03` ~ `../06`）。本文件只記錄實作層的設計決定。

## 後端

1. **設定分層**
   - `application.yml`：預設即「正式安全」——Cookie `Secure=true`、`ddl-auto=validate`、不含任何密碼預設值；資料庫連線資訊一律來自環境變數。
   - `application-dev.yml`：僅放寬 Cookie `Secure`、開啟 SQL log 與 dev 初始化。
   - `application-test.yml`：連到 `renewflow_test`。
   - `.env`（不進版控）：`spring.config.import=optional:file:../.env[.properties]` 讓 Spring 直接讀；同一份檔案也供 `docker compose` 使用，避免兩處設定不一致。
2. **登入實作**：`AuthController` 只處理 HTTP；`AuthService.login` 執行 `AuthenticationManager.authenticate`，成功後依序：換發 Session ID → 輪替 CSRF token → 存入 `SecurityContext`。這三步等同 Spring 內建表單登入 Filter 在成功後做的事，因為我們自己寫 JSON 登入，必須自己做齊。**漏掉任何一步都是漏洞或功能缺陷**：漏換發＝Session Fixation；漏存＝下一個請求變回未登入；漏輪替＝登入前的 token 延續使用。
3. **登出**：使用 `http.logout(...)`，由框架的 `SecurityContextLogoutHandler`（失效 Session）與 `CsrfLogoutHandler`（清 token）處理；成功回 204。
4. **CSRF**：`CookieCsrfTokenRepository`（`httpOnly=false`）＋官方 SPA 範式的 `SpaCsrfTokenRequestHandler` ＋ `CsrfCookieFilter`。因為 Spring Security 6 起 token 是「延遲載入」，若沒有人讀它，Cookie 不會寫出，SPA 就拿不到 token；`CsrfCookieFilter` 強制每個請求讀一次。
5. **`/me`**：不直接回傳 Session 中的快照，而是以登入者 ID 重新查詢資料庫；員工已停用或被刪除則回 401。代價是每次多一次主鍵查詢，換取「停用立即生效」。
6. **開發帳號**：`DevSeedRunner`（`@Profile("dev")`）在 `employee` 沒有資料時建立 `admin`／`manager`／`employee` 三個帳號，密碼取自 `RENEWFLOW_DEV_SEED_PASSWORD`；未設定則不建立並記錄警告。

## 前端

- Vite＋React＋TypeScript，無其他執行期套件。
- `src/api/http.ts`：統一的 `fetch` 包裝（同源 Cookie、寫入自動帶 CSRF、錯誤轉為 `ApiError`）。
- `src/auth/AuthProvider.tsx`：保存 `loading | anonymous | authenticated` 三態；啟動時呼叫 `/me`。
- `LoginPage`、`HomePage`（側邊選單＋頂欄＋卡片）；未啟用功能顯示「尚未啟用」，不顯示任何假數字。
- 開發代理：`vite.config.ts` 把 `/api` 轉給 `localhost:8080`。

## 設計模式

無新增（見 `../03-architecture.md` §3）。
