# T01＋T02：專案基礎與登入——技術文件

狀態標記說明：**已驗證**＝我在本機實際執行並看到結果（證據見第 9 節）；**已實作待驗證**＝程式已寫但沒有對應的實際檢查；**尚未由對話助理審查**——本文件所有內容都只是開發代理的自述，等你帶回 ChatGPT 審查後才算「已審查」。

| 功能 | 狀態 |
|---|---|
| 專案建置、設定、DB 連線、V1 migration | 已驗證 |
| 登入／登出／目前使用者 API | 已驗證 |
| Session＋HttpOnly Cookie、CSRF | 已驗證（HTTP 環境；HTTPS＋`Secure` Cookie 未實測） |
| 登入頁、首頁框架 | 已驗證（瀏覽器自動化腳本＋截圖，Edge） |
| 開發帳號初始化 | 已驗證 |

版本資訊：repository `https://github.com/hsieh634/java-eip`，分支 `main`。審查基準是 `main` 上標題為 `feat: T01-T02 ...` 的 commit；完整 SHA 記載在交付報告中（文件不記錄自己所在 commit 的 SHA，避免循環）。各元件的確切版本見 [docs/renewflow/version-log.md](../renewflow/version-log.md)。
**更正**：最初交付時此資料夾尚未是 git repository；之後才初始化並推送。

---

## 1. 快速摘要

- **用途**：讓使用者用帳號密碼登入，取得「我是誰」，並看到登入後的首頁框架。這是後續所有功能（打卡、工時、請假）的身份基礎。
- **技術**：Java 21、Spring Boot 4.1.1（Spring Framework 7.0.9、Spring Security 7.1.1、Hibernate 7.4.5、Flyway 12.4.0、Tomcat 11.0.24）、PostgreSQL 17、React 19.3.0＋TypeScript 6.0.3＋Vite 8.3.1。版本取自 `backend/pom.xml`、`frontend/package.json` 與實際建置輸出。
- **主要流程**：瀏覽器 → Spring Security Filter（CSRF、Session）→ Controller → Service → Repository → PostgreSQL → 回應。
- **最重要的規則**：
  1. 身份來自 Session，不來自前端傳的參數。
  2. 密碼只存雜湊。
  3. 寫入請求必須帶 CSRF token。
  4. 登入成功必須換發 Session ID。
  5. 「帳號不存在」與「密碼錯誤」對外完全一樣。

官方依據：Spring Boot 4.1.1 系統需求頁（支援 Java 17–26、Maven 3.6.3+、Spring Framework 7.0.9+）；Spring Security CSRF 文件（SPA 範式 `CookieCsrfTokenRepository` 與自訂 request handler）。

## 2. 操作入口

| 操作 | 入口 | 輸入 → 結果 |
|---|---|---|
| 登入 | 瀏覽器 `http://localhost:5173` 的登入頁 | 帳號＋密碼 → 成功進首頁；失敗顯示「帳號或密碼錯誤」 |
| 看目前使用者 | 首頁載入時自動呼叫 `GET /api/auth/me` | 頂欄顯示姓名與角色，「我的資訊」卡片顯示員工編號、帳號、角色 |
| 登出 | 首頁右上角「登出」按鈕 | 回到登入頁；原 Session 失效 |

首頁的「今日打卡／本週工時／待審核」與側邊選單的其他項目只顯示「尚未啟用」，**沒有任何假資料**。

## 3. 實際程式碼定位

### 後端（`backend/src/main/java/com/renewflow/eip/`）

| 檔案 | 角色 |
|---|---|
| `EipApplication.java` | 啟動入口，`@SpringBootApplication` |
| `identity/AuthController.java` | `login()`、`me()`：HTTP 邊界 |
| `identity/AuthService.java` | `authenticate()`、`getCurrentUser()`：使用案例 |
| `identity/Employee.java`、`Role.java` | Entity 與角色列舉 |
| `identity/EmployeeRepository.java` | `findByUsername`、`existsByUsername` |
| `identity/EmployeeUserDetails(Service).java` | 與 Spring Security 的橋接 |
| `identity/LoginRequest.java`、`CurrentUserResponse.java` | 請求／回應 DTO |
| `identity/DevSeedRunner.java` | 僅 dev profile 的開發帳號 |
| `shared/security/SecurityConfig.java` | 整個安全規則 |
| `shared/security/SessionLoginSupport.java` | 登入後的 Session／CSRF 收尾 |
| `shared/security/SpaCsrfTokenRequestHandler.java`、`CsrfCookieFilter.java` | SPA 的 CSRF 機制 |
| `shared/security/JsonSecurityHandlers.java` | 401／403／登出成功的 JSON 回應 |
| `shared/web/GlobalExceptionHandler.java`、`ApiError.java` | Controller 層的統一錯誤 |

### 資源與測試

| 檔案 | 說明 |
|---|---|
| `backend/src/main/resources/application.yml`、`application-dev.yml` | 設定（正式安全基準＋開發放寬） |
| `backend/src/main/resources/db/migration/V1__create_employee.sql` | 初始 migration |
| `backend/src/test/java/.../identity/AuthFlowIntegrationTest.java` | 17 個整合測試 |
| `backend/src/test/java/.../support/TestBrowser.java`、`TestFlywayConfig.java` | 測試輔助 |
| `docker-compose.yml`、`docker/postgres-init/01-create-test-db.sh`、`.env.example` | 本機資料庫 |

### 前端（`frontend/src/`）

| 檔案 | 說明 |
|---|---|
| `api/http.ts` | 所有後端呼叫的入口：同源 Cookie、自動帶 CSRF、錯誤轉 `ApiError` |
| `api/auth.ts` | `fetchMe`、`login`、`logout` |
| `auth/AuthProvider.tsx`、`AuthContext.ts`、`useAuth.ts` | 登入狀態（loading／anonymous／authenticated／unavailable） |
| `pages/LoginPage.tsx`、`pages/HomePage.tsx`、`App.tsx` | 畫面 |
| `vite.config.ts` | 開發代理 `/api` → `localhost:8080` |

## 4. 完整資料流

### 登入

```
LoginPage 送出表單
 → AuthProvider.login → api('/api/auth/login', POST)        [http.ts 從 Cookie 讀 XSRF-TOKEN，放進 X-XSRF-TOKEN 標頭]
 → Vite 代理轉給 :8080
 → Spring Security Filter Chain
     CsrfFilter：比對標頭與 Cookie 的 token ── 不符 → JsonSecurityHandlers.accessDeniedHandler → 403 CSRF_REJECTED
     CsrfCookieFilter：確保 XSRF-TOKEN Cookie 存在
     授權規則：POST /api/auth/login 為 permitAll
 → DispatcherServlet → AuthController.login
     @Valid 驗證 LoginRequest ── 失敗 → GlobalExceptionHandler → 400 VALIDATION_FAILED
 → AuthService.authenticate
     AuthenticationManager(ProviderManager) → DaoAuthenticationProvider
       → EmployeeUserDetailsService.loadUserByUsername → EmployeeRepository.findByUsername → PostgreSQL
       → PasswordEncoder.matches(輸入, 雜湊)
     任何驗證失敗 → InvalidCredentialsException → 401 BAD_CREDENTIALS
 → SessionLoginSupport.establish：換發 Session ID → 輪替 CSRF token → SecurityContext 存入 Session
 → AuthService.getCurrentUser(id) → EmployeeRepository.findById → CurrentUserResponse
 → 200 JSON ＋ Set-Cookie: JSESSIONID(HttpOnly)、XSRF-TOKEN
 → AuthProvider 設為 authenticated → App 切換到 HomePage
```

### 目前使用者

```
頁面載入 → AuthProvider 的 useEffect → GET /api/auth/me（帶 JSESSIONID Cookie）
 → SecurityContextHolderFilter 由 Session 還原 SecurityContext（EmployeeUserDetails）
 → 授權：/api/** 需 authenticated；未登入 → entryPoint → 401（仍帶 XSRF-TOKEN Cookie）
 → AuthController.me → AuthService.getCurrentUser(principal.employeeId) → 資料庫 → 200
```

### 登出

```
HomePage 登出鈕 → api('/api/auth/logout', POST，帶 CSRF)
 → CsrfFilter → LogoutFilter（Spring 內建，請求不會到 Controller）
     SecurityContextLogoutHandler：Session 失效
     CsrfLogoutHandler：清掉舊 token
     logoutSuccessHandler：發新 XSRF-TOKEN、回 204
 → 前端重新呼叫 /me → 401 → 回到登入頁
```

## 5. 核心程式解說

### 5.1 設定：預設就是「正式安全」

`application.yml` 的重點（輸入：環境變數；副作用：決定整個應用的安全基準）：

```yaml
spring.config.import: optional:file:../.env[.properties]   # 讀根目錄 .env；找不到不報錯
spring.datasource.password: ${DB_PASSWORD}                  # 刻意不給預設值
spring.jpa.hibernate.ddl-auto: validate                     # Hibernate 只驗證，不改表
server.servlet.session.cookie.secure: true                  # 正式：只經 HTTPS 送；dev 才改 false
```

- **原因**：把「安全的值」當預設，開發才需要主動放寬（`application-dev.yml`）。反過來（預設寬鬆、正式再收緊）一旦忘記就是漏洞。
- `${DB_PASSWORD}` 沒有 `:預設值`，缺少時啟動就失敗——寧可啟動失敗，也不要悄悄用弱密碼。
- `ddl-auto=validate`：表結構只由 Flyway migration 管理。Hibernate 啟動時檢查 Entity 與資料表是否對得上，對不上就拒絕啟動。

### 5.2 Migration：`V1__create_employee.sql`

重點是約束而不只是欄位：`UNIQUE(username)`、`CHECK (username = lower(username))`、`CHECK (role IN (...))`。**應用程式會有 bug，資料庫約束是最後一道防線**（測試 `databaseConstraintsEnforced` 直接用 SQL 插入非法資料，確認被資料庫拒絕）。已套用的 migration 不可修改；Flyway 會記錄檢查碼，改了就拒絕啟動。

### 5.3 `SecurityConfig.securityFilterChain`

```java
.csrf(csrf -> csrf.csrfTokenRepository(repo).csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
.requestCache(AbstractHttpConfigurer::disable)
.authorizeHttpRequests(auth -> auth
    .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/logout").permitAll()
    .requestMatchers("/api/**").authenticated()
    .anyRequest().denyAll())
```

- **CSRF 是什麼**：瀏覽器會自動把 Cookie 附在發往該網站的任何請求上，包括惡意網站偷偷發的。CSRF token 要求請求必須額外帶一個「只有本站頁面讀得到」的值。
- **為什麼要 `SpaCsrfTokenRequestHandler` 與 `CsrfCookieFilter`**：Spring Security 6 起 token 延遲載入，沒人讀它就不產生 Cookie；而且預設把 token 編碼成每次不同的值，SPA 從 Cookie 讀到的原始值會對不上。這兩個類別是官方文件的 SPA 範式，用註解說明了每一步的原因。
- **`requestCache` 關閉**：預設會把被擋下的請求存進 Session 以便登入後導回，API 用不到，還會讓每個匿名 401 都建立 Session。
- **`denyAll` 當預設**：沒有明確開放的路徑一律拒絕。
- 授權規則依序比對，第一個符合的生效，所以具體規則在前、`anyRequest()` 在最後。

### 5.4 `SessionLoginSupport.establish`——自己寫登入就要自己做齊

```java
sessionFixationStrategy.onAuthentication(authentication, request, response); // 1 換發 Session ID
csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response); // 2 輪替 token
securityContextRepository.saveContext(context, request, response); // 3 存入 Session
```

Spring 內建表單登入會自動做這三件事；我們為了 JSON 登入自己寫，就必須自己補齊。測試 S-15（換發 ID）、S-14（輪替）、S-10（存入）各對應一步，而且我做過**變異檢查**：暫時拿掉 1 和 2，確實有 3 個測試轉為失敗（見第 9 節），證明測試真的在檢查它。

### 5.5 `AuthService.authenticate`

```java
try { return authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(u, p)); }
catch (InternalAuthenticationServiceException e) { throw e; }          // 系統故障，不是密碼錯誤
catch (AuthenticationException e) { throw new InvalidCredentialsException(); } // 其餘一律同一種結果
```

- 帳號不存在（`UsernameNotFoundException`）、密碼錯誤（`BadCredentialsException`）、停用（`DisabledException`）全部變成同一個 `InvalidCredentialsException`，避免被用來探測帳號。測試 S-05 逐字比對兩種回應。
- 注意 catch 順序：`InternalAuthenticationServiceException` 是 `AuthenticationException` 的子類別，必須先 catch，否則資料庫掛掉會被誤報成「帳號或密碼錯誤」。

### 5.6 `GlobalExceptionHandler.rethrowSecurityExceptions`

萬用的 `@ExceptionHandler(Exception.class)` 會攔截 Spring Security 的 `AccessDeniedException`，把本該是 403 的情況變成 500。所以要先宣告一個把它們原樣丟回去的處理器，交給 Security 的 `ExceptionTranslationFilter`。這是常見陷阱。

### 5.7 `AuthController.me` 與 `getCurrentUser`

員工 ID 來自 `@AuthenticationPrincipal`（Session 內的登入身份）；`getCurrentUser` **重新查資料庫**，若員工已停用或刪除就拋 `UnauthenticatedException` 並順手讓 Session 失效。代價是每次多一次主鍵查詢，換來「停用立即生效」（測試 S-16）。之後所有功能都應照這個模式：**員工 ID 從登入身份取，不接受前端傳 employeeId**。

### 5.8 `EmployeeUserDetails implements CredentialsContainer`

登入驗證完成後框架會呼叫 `eraseCredentials()`，我們把雜湊設成 null，避免密碼雜湊跟著 Session 長期留在記憶體。Session 內存的是「快照」，所以 `/me` 才要回資料庫查最新狀態。

### 5.9 `DevSeedRunner`（`@Profile("dev")`）

密碼只來自環境變數 `RENEWFLOW_DEV_SEED_PASSWORD`；原始碼與 migration 都不含密碼，未設定就不建立。已確認資料庫內存的是 `{bcrypt}$...`，不是明文。

### 5.10 前端重點

- `http.ts`：**每次呼叫即時從 Cookie 讀 `XSRF-TOKEN`**，不快取。因為登入／登出後後端會換發，快取舊值會讓下一個寫入被 403。`JSESSIONID` 是 HttpOnly，JavaScript 讀不到——這正是目的（測試腳本驗證了 `document.cookie` 看不到它）。
- `AuthProvider`：登入狀態有四種而非兩種。`loading` 避免一開頁就閃登入頁；`unavailable` 避免「後端沒開」被誤判成「未登入」。
- `LoginPage` 的 `if (submitting) return` 與停用按鈕只是改善操作體驗；後端不依賴它（這是指南第 8 節強調的觀念）。
- 前端的畫面切換不是安全機制：就算有人強行顯示首頁，沒有 Session 也拿不到任何資料。

## 6. 設計理由與取捨

| 決定 | 原因 | 替代方案／成本 |
|---|---|---|
| 登入寫在 Controller，登出用內建 | 登入流程可從程式讀懂；登出的 Session 失效與 CSRF 清除框架做得更可靠 | 全用內建 Filter：程式少，但需自訂 JSON 處理，較難入門 |
| Cookie 型 CSRF（雙重提交） | 業界對 SPA 的標準作法；前端好處理 | 伺服器端 Session token：更嚴格，但前端要多一個取 token 的端點。**限制**：雙重提交下伺服器不記憶 token，所以「舊 token 失效」不成立，輪替只是不沿用登入前的值 |
| `/me` 查資料庫 | 停用立即生效 | 直接回 Session 快照：快，但停用要等 Session 到期 |
| 角色用字串＋CHECK | 增減角色只需 migration | PG enum：型別變更麻煩 |
| 整合測試用真 Tomcat＋真 PostgreSQL | 能驗證 Cookie 屬性、Filter 順序、SQL 約束 | MockMvc：快，但驗證不到 Set-Cookie；Testcontainers：隔離更好但多一個套件 |
| 不引入 react-router／UI 套件 | 只有兩個畫面 | T03 起畫面變多再加 |
| 沒有套用 GoF 設計模式 | 沒有具體問題需要它 | — |

## 7. 資料模型與 SQL

資料表見 `docs/renewflow/04-data-model.md`。以下 SQL 是**實際觀察**到的（dev profile 開啟 `org.hibernate.SQL` 後從後端日誌取得）：

```sql
-- 登入：EmployeeUserDetailsService
select e1_0.id, e1_0.created_at, e1_0.display_name, e1_0.employee_no, e1_0.enabled,
       e1_0.password_hash, e1_0.role, e1_0.updated_at, e1_0.username
  from employee e1_0 where e1_0.username=?
-- /me 與登入後組回應：findById
select ... from employee e1_0 where e1_0.id=?
-- 開發帳號初始化：existsByUsername（只取 id，限一筆）與 insert
select e1_0.id from employee e1_0 where e1_0.username=? fetch first ? rows only
insert into employee (created_at,display_name,employee_no,enabled,password_hash,role,updated_at,username) values (?,?,?,?,?,?,?,?)
```

觀察：每次登入查 2 次（依帳號、依 ID），`/me` 查 1 次；`username` 與主鍵都有索引。目前沒有關聯所以沒有 N+1 問題。

## 8. 安全與錯誤

| 情境 | 結果 | 測試 |
|---|---|---|
| 未登入取 `/me` 或任何 `/api/**` | 401 `UNAUTHENTICATED`（含不存在的路徑，不洩漏） | S-08、S-09 |
| 帳密錯誤／帳號不存在／帳號停用 | 401 `BAD_CREDENTIALS`，訊息完全相同 | S-04、S-05、S-06 |
| 欄位空白 | 400 `VALIDATION_FAILED`（只回欄位名，不回輸入值） | S-07 |
| 缺少／錯誤的 CSRF token | 403 `CSRF_REJECTED`，且不會因此登入或登出 | S-12、S-12b、S-13 |
| 登出後舊 Session ID | 401 | S-11 |
| 登入換發 Session ID，舊 ID 失效 | 通過 | S-15 |
| 登入後員工被停用 | 下一次 `/me` 即 401 | S-16 |
| 資料庫連不上 | 500 `INTERNAL_ERROR`（不顯示成密碼錯誤）——**程式有處理，但未做故障注入測試** | 未驗證 |
| 重複請求 | 登入／登出是冪等的，無資料一致性問題；T03 起才需要處理 | — |
| 暴力破解 | **未防護**（假設 A-06） | — |

## 9. 驗證證據

環境：Windows 11、Temurin 21.0.12、Maven 3.9.12（Wrapper 同版）、Node 24.14.0、PostgreSQL 17.11（Docker）。

| 項目 | 指令／方式 | 結果 |
|---|---|---|
| 後端整合測試 | `cd backend; .\mvnw.cmd test` | **17 個測試，0 失敗，BUILD SUCCESS**（全綠兩次：Git Bash 的 `./mvnw` 與 PowerShell 的 `mvnw.cmd`；另有一次刻意破壞程式的變異檢查，見下一列） |
| 乾淨資料庫 migration | 測試啟動時 `TestFlywayConfig` 先 clean 再 migrate；測試 S-02 查 `flyway_schema_history` | 通過；日誌顯示 `Successfully applied 1 migration ... now at version v1` |
| 變異檢查 | 暫時移除 Session ID 換發與 CSRF 輪替 | 3 個測試轉為失敗（`sessionIdChangesOnLogin`、`csrfTokenRotatesOnLoginAndLogout`、`loginSuccess`）；已還原並重跑全綠 |
| 後端啟動（dev） | `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"` | `Started EipApplication in 3.0 seconds`，建立 3 個開發帳號 |
| API 手動 | curl（bash 與 PowerShell `curl.exe` 各一遍） | 401 帶 `XSRF-TOKEN` → 登入 200 → `/me` 200 → 登出 204 → `/me` 401；無 CSRF 登入得 403；資料庫雜湊開頭為 `{bcrypt}$` |
| 前端建置／檢查 | `npm run build`、`npm run lint` | 通過，0 警告 |
| 瀏覽器操作（S-17） | `cd frontend; npm run e2e`（腳本 `frontend/e2e/smoke.mjs`，puppeteer-core 驅動 Edge，對 `localhost:5173`） | **16/16 通過**：登入頁、空白時按鈕停用、錯誤密碼訊息、登入成功、首頁內容、HttpOnly、重新整理仍登入、登出、登出後直接以另一角色再登入、無未捕捉例外。（首次驗證時用暫存腳本跑 17 項；整理進專案時合併了兩項重複的「尚未啟用」檢查，所以是 16 項，並已用專案內版本重跑） |
| 後端關閉時畫面 | 一次性腳本（未納入專案） | 顯示「伺服器暫時無法使用，請確認後端已啟動」與「重試」按鈕，已在瀏覽器驗證 |

**誠實說明**
- 專案內的前端驗證只有上述 E2E 冒煙腳本，沒有 Vitest 等單元測試；它需要資料庫、後端、前端先啟動，且不在 `mvnw test` 內。為了這支腳本，前端新增了開發相依套件 `puppeteer-core`（Apache-2.0，不下載瀏覽器，使用本機 Edge／Chrome）。
- 未驗證：HTTPS 下 `Secure` Cookie 實際行為、Session 逾時（30 分鐘）、後端資料庫中斷、行動版畫面、Chrome／Firefox。
- 測試輸出有 Mockito／ByteBuddy 的「動態載入 agent」警告，來自測試框架內建的 Mockito，我們的測試沒有使用 Mockito，不影響結果。

## 10. 技術問答與練習

### 可能被問的題目

**Q1. 登入成功後 Session 與 Cookie 是怎麼運作的？**
伺服器在記憶體保存 Session（內含登入者），只把無意義的 ID 放進 `JSESSIONID` Cookie 給瀏覽器；之後每個請求瀏覽器自動帶上，伺服器由 ID 找回身份。`HttpOnly` 讓 JavaScript 讀不到，降低 XSS 偷走 ID 的風險。追問：Session 存記憶體有什麼限制？（重啟即失效、多機要共享儲存。）

**Q2. CSRF 是什麼？為什麼 SPA 還要防？**
瀏覽器自動帶 Cookie，惡意網站可以讓使用者的瀏覽器對本站發出請求，本站會誤以為是使用者本人操作。防法是要求請求帶一個惡意網站拿不到的 token。只要用 Cookie 認證就需要，與是否前後端分離無關。追問：為什麼 CSRF Cookie 不是 HttpOnly？（前端要讀它；它不是登入憑證。）`SameSite` 能取代 CSRF token 嗎？（可當第二層，但不應單獨依賴。）

**Q3. 為什麼登入成功後要換發 Session ID？**
防 Session Fixation：攻擊者先讓受害者使用一個攻擊者已知的 Session ID，若登入後仍沿用，攻擊者就能共用已登入的 Session。

**Q4. 為什麼「帳號不存在」和「密碼錯誤」要回一樣的訊息？**
避免帳號列舉。追問：還有哪些管道會洩漏？（回應時間、HTTP 狀態碼、錯誤欄位。Spring 的 `DaoAuthenticationProvider` 對不存在的帳號也會跑一次雜湊以縮小時間差。）

**Q5. 401 與 403 的差別？**
401＝不知道你是誰（未認證）；403＝知道你是誰但不允許（或 CSRF 失敗）。本專案兩者都用 JSON。追問：409 呢？（狀態衝突，T05 會用到。）

**Q6. 為什麼不直接回傳 Entity？**
API 契約與資料庫結構耦合；可能洩漏 `passwordHash` 等欄位；之後有關聯時序列化會觸發延遲載入問題。本專案的 `CurrentUserResponse` 只含前端需要的欄位。

**Q7. `@Valid` 夠嗎？**
只驗證格式（空白、長度）。帳密對不對、帳號是否停用是業務判斷，在 Service／Security。更不能取代權限檢查。

**Q8. 為什麼用建構子注入？**
必要相依一目了然、物件建立後一定完整、測試時可直接傳入替代物件；欄位注入做不到這些。

**Q9. Flyway 是什麼？為什麼 `ddl-auto` 要是 `validate`？**
Flyway 以版本編號的 SQL 檔管理資料庫變更並記錄於 `flyway_schema_history`，環境可由零重建、每個環境一致。`validate` 讓 Hibernate 只檢查不修改，避免 ORM 悄悄改表造成環境不一致。

**Q10. 為什麼整合測試要用真的資料庫？**
H2 等替代品的 SQL 行為與 PostgreSQL 不完全相同，也驗證不到真實的 CHECK／UNIQUE 約束與 migration。代價是測試需要資料庫在執行。

### 練習（一次一題）

**題目**：目前 `GET /api/auth/me` 每次都會查資料庫。請**只修改一個地方**，讓回應多一個欄位 `enabled`——先回答：你需要改哪幾個檔案？哪個測試會需要調整或新增？為什麼 `passwordHash` 不能比照辦理？

建議把答案（文字即可）連同這份文件帶回 ChatGPT 對話，由它批改並給下一題。

---

## 附：下一階段（T03）預告，不屬於本輪

打卡：`attendance` 模組、員工＋業務日期唯一限制、注入 `Clock`、交易與重複請求。T03 會用到本輪的 `EmployeeUserDetails.getEmployeeId()` 取得員工身份。
