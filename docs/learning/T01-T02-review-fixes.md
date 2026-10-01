# T01＋T02 審查修正（R1～R4）學習說明

審查基準：commit `d72fb814f97bb4e4da2e2b61794cefd15a61dd9f`。本文件說明審查指出的四個問題，每一項都回答：原本怎麼寫、為什麼有問題、改完怎麼運作、測試如何證明、涉及哪些概念、面試重點。最後有**一題**練習。

狀態：四項皆為**已驗證**（作者在本機實際執行，見第 6 節）；**尚未經對話助理審查**。

| 項目 | 一句話 |
|---|---|
| R1 | 清空測試資料庫前，要問資料庫「你實際是哪一個」，而不是看連線字串長什麼樣 |
| R2 | 「系統壞了」不能回成「密碼錯了」 |
| R3 | 登出失敗要讓使用者看得到，而且要以後端確認的狀態為準 |
| R4 | bcrypt 的 72 限制是「位元組」不是「字元」 |

---

## R1：測試資料庫清空保護

### 原本怎麼寫，為什麼有問題

`TestFlywayConfig`（`backend/src/test/java/com/renewflow/eip/support/`）在 `flyway.clean()` 前只檢查 JDBC URL 字串是否以 `_test` 結尾：

```java
if (url == null || !url.endsWith("_test")) { throw ... }   // 舊寫法
flyway.clean();                                              // 會刪除資料庫內所有物件
```

URL 的結尾不等於「實際連到的資料庫」。這個 URL 實際連到開發庫 `renewflow`，卻會通過檢查：

```
jdbc:postgresql://localhost:5432/renewflow?ApplicationName=eip_test
```

另外，若開發庫與測試庫被設成同一個以 `_test` 結尾的名稱，舊檢查也不會發現。`clean` 是破壞性操作，防護寧可誤擋、不可誤放。

### 修改後怎麼運作

1. 連線建立後，用 `Connection.getCatalog()` 向 PostgreSQL 取得**實際連到的資料庫名稱**（`TestDatabaseGuard.actualDatabase`）。
2. `TestDatabaseGuard.assertSafeToClean(actual, allowedTest, dev)` 逐項檢查，**任何一項不成立就丟 `IllegalStateException`，不執行 clean**：
   - 三個名稱都必須存在（無法確認 → 拒絕）。
   - 明確允許的測試庫名稱必須符合命名慣例 `[a-z][a-z0-9_]*_test`。
   - 測試庫與開發庫名稱不可相同（不分大小寫）。
   - 實際名稱必須與明確允許的測試庫**完整相等**。
   - 實際名稱不可等於開發庫。
3. 「明確允許的測試庫」與「開發庫」來自 `application-test.yml` 的 `renewflow.test.database`（`DB_TEST_NAME`）與 `renewflow.test.dev-database`（`DB_NAME`）。
4. `TestFlywayConfig.cleanThenMigrate` 先取得名稱並呼叫 guard，通過才 `clean()` 再 `migrate()`；取得連線或名稱失敗也一律拒絕（fail closed）。

### 測試如何證明

| 測試 | 證明 |
|---|---|
| `TestDatabaseGuardTest`（11 個，純單元、不連資料庫） | 正常測試庫放行；開發庫、其他 `_test` 庫、開發＝測試設定相同、命名不符、名稱缺失都拒絕；策略層用 Mockito 替身確認**拒絕時 `clean()` 從未被呼叫**、正常時順序為 clean → migrate、查名稱失敗或連線失敗也不 clean |
| `TestDatabaseGuardConnectionTest`（2 個） | 用真實 JDBC 連線：測試庫回報名稱通過；對**開發庫**加上 `?ApplicationName=eip_test` 偽裝，先斷言舊的 `endsWith("_test")` 會被騙過，再證明資料庫實際回報 `renewflow` 而 guard 拒絕。只讀取名稱，不 clean、不寫入 |

「驗證防護時不得真的清空開發資料庫」的做法：策略層測試用替身 `Flyway`（沒有真實資料庫）；真實連線的測試只呼叫 `getCatalog()`；重跑後我確認開發庫仍有 3 筆開發帳號與 Flyway V1 紀錄。

變異檢查：暫時拿掉 `TestFlywayConfig` 中的 guard 呼叫，`strategyNeverCleansDevDatabase`、`strategyRefusesWhenDevAndTestConfiguredTheSame` 轉為失敗；已還原。

### 概念與面試重點

- **Fail closed**：無法確認安全時預設拒絕。破壞性操作的防護不能是「看起來沒問題就放行」。
- **信任來源**：字串（URL）是「宣告」，資料庫回報的名稱是「事實」。要驗證事實。
- **JDBC URL 的 query parameter**：`?key=value` 不改變連到哪個資料庫，只傳連線屬性。
- **Flyway `clean`**：刪除 schema 內所有物件；預設被停用（`clean-disabled=true`），測試設定才開啟。
- 面試問法：「你怎麼避免測試把正式資料庫清掉？」→ 分離設定、明確允許名單、以實際連線事實驗證、預設拒絕、保護本身也要有測試。

---

## R2：認證故障被轉成 401

### 原本怎麼寫，為什麼有問題

`AuthService.authenticate`（`backend/src/main/java/com/renewflow/eip/identity/AuthService.java`）：

```java
catch (InternalAuthenticationServiceException e) { throw e; }   // 想表達：這是系統故障
catch (AuthenticationException e) { throw new InvalidCredentialsException(); }
```

但 `InternalAuthenticationServiceException` 本身**也是** `AuthenticationException`。重新丟出後，`GlobalExceptionHandler` 為了讓 Security 處理 401／403，會把所有 `AuthenticationException` 原樣丟回 Security Filter，最後進入固定回 401 的 entry point。結果：資料庫掛掉，使用者看到的卻是「請先登入」（401），故障被偽裝成使用者問題，也不會出現在 500 的監控上。

### 修改後怎麼運作

- 新增 `AuthenticationUnavailableException`（`identity/`），**刻意不繼承 Spring Security 的任何例外**。
- `AuthService.authenticate` 對 `InternalAuthenticationServiceException` 改丟它（保留原例外為 cause）。
- `GlobalExceptionHandler.onAuthenticationUnavailable`：`log.error("Authentication backend failure", e)`（完整原因只進伺服器日誌），回 **500** `{"code":"INTERNAL_ERROR","message":"系統發生錯誤，請稍後再試"}`——訊息是固定文字，不含 SQL、連線字串或帳密。
- 其餘行為不變：帳號不存在／密碼錯誤／停用 → 401 `BAD_CREDENTIALS`；未登入 → 401；CSRF → 403。

### 測試如何證明

`AuthFailureIntegrationTest`（6 個）把 `EmployeeUserDetailsService` 換成 Mockito 替身（`@MockitoBean`），其餘（真的 Tomcat、完整 Security Filter Chain、真的 HTTP）不變，驗證**最終 HTTP status 與 JSON code**：

| 測試 | 結果 |
|---|---|
| 替身拋出 `DataAccessResourceFailureException("... jdbc:postgresql://secret-db-host ...")` | 500 `INTERNAL_ERROR`；回應不含 `secret-db-host`、`secret-db-user`、`jdbc`、`SQL`、密碼、`Exception`；沒有登入；伺服器日誌**有** `Authentication backend failure` 與底層原因，且日誌不含密碼 |
| 對照：`UsernameNotFoundException` | 401 `BAD_CREDENTIALS` |
| 對照：密碼錯誤、帳號停用 | 401 `BAD_CREDENTIALS` |
| 對照：缺 CSRF | 403 `CSRF_REJECTED`，且完全沒有呼叫 UserDetailsService |

變異檢查：把新例外改回 `throw e;`，故障測試失敗（得到 401，不是 500）；已還原。

### 概念與面試重點

- **例外階層的陷阱**：子類別關係決定它最終被誰處理。`catch` 的順序（子類別先）只決定「誰先接」，不決定「接了之後去哪」。
- **401 / 403 / 500 的語意**：401＝不知道你是誰；403＝知道但不允許；500＝我們壞了。把 500 說成 401 會誤導使用者與監控。
- **`@RestControllerAdvice` 與 Security Filter 的邊界**：Filter 在 Controller 之前，Advice 管不到；Controller 內拋出的 Security 例外要原樣丟回，否則會被萬用處理器吞掉變 500（`rethrowSecurityExceptions`）。
- **安全日誌**：伺服器 log 要能排查，回應不可洩漏內部資訊。
- 面試問法：「帳號不存在跟資料庫掛掉，使用者看到的有什麼不同？為什麼？」

---

## R3：登出失敗沒有 catch 與提示

### 原本怎麼寫，為什麼有問題

```ts
// AuthProvider.logout（舊）
try { await authApi.logout() } finally { await refresh() }
// HomePage.handleLogout（舊）
try { await logout() } finally { setLoggingOut(false) }
```

`finally` 只保證收尾會執行，**不會吞掉例外**。登出請求回 403（或網路中斷、5xx）時，例外仍往外傳，`HomePage` 的 async 事件函式 reject，變成**未處理的 Promise 拒絕**；使用者也看不到任何失敗提示。

### 修改後怎麼運作

- `AuthProvider.loadState()`：向 `/me` 確認並回傳新狀態，**永不 reject**（所有錯誤轉成狀態：401→`anonymous`，其餘→`unavailable`）。
- `AuthProvider.logout()`：先 `try/catch` 登出請求（記下錯誤），**不論成敗都重新問 `/me`**，再依「後端確認的真實狀態」回傳 `LogoutResult`：
  - `/me` 回 401 → `{ ok: true }`，狀態變 `anonymous`，App 顯示登入頁（即使登出請求報錯，例如 Session 剛好過期）。
  - `/me` 仍是已登入 → `{ ok: false, error }`。
  - `/me` 連不上 → 狀態變 `unavailable`，App 顯示無法連線畫面與「重試」。
- `HomePage.handleLogout`（UI 邊界）：用 `describeLogoutFailure`（`auth/logoutFailureMessage.ts`）把 403／網路／5xx／其他轉成可讀提示，顯示在頂欄下方的 `role="alert"` 橫幅，結束 loading，按鈕改為「重試登出」。外層另有 `try/catch` 防禦未預期例外。
- 關鍵設計：**不假設登出成功**，以後端為唯一事實來源。

### 測試如何證明

`frontend/e2e/logout-failure.mjs`（`npm run e2e:logout`）用 puppeteer 的請求攔截，讓登出或 `/me` 受控地失敗，真實後端照常運作；每個情境都檢查「沒有未處理的 Promise 拒絕」（以 `unhandledrejection` 事件直接記錄）與「沒有未捕捉的 JS 例外」：

| 情境 | 驗證 |
|---|---|
| A 登出回 403、`/me` 仍登入 | 登出中按鈕停用；顯示含「安全驗證」「仍在登入狀態」的提示；仍停留首頁；按鈕恢復為「重試登出」；排除故障後重試回登入頁 |
| B 網路中斷 | 顯示「無法連線」提示；按鈕可再按 |
| C 回 500 | 顯示「伺服器暫時無法處理」提示；按鈕可再按 |
| D 登出報錯但 `/me` 回 401 | 以後端確認為準，回登入頁 |
| E 登出失敗且 `/me` 也連不上 | `unavailable` 畫面與重試；重試後 Session 仍有效，回首頁 |

變異檢查：把 `AuthProvider`、`AuthContext`、`HomePage` 暫時換回審查基準 commit 的版本，`e2e:logout` 在 A 情境因「找不到失敗提示橫幅」失敗；已還原。

### 概念與面試重點

- **`try/finally` 不是 `try/catch`**：finally 用於收尾，例外會繼續傳遞。
- **未處理的 Promise 拒絕**：`async` 函式內的例外會變成 rejected Promise；若呼叫端沒 `await`／`catch`（例如 React 事件處理函式），就成為 unhandled rejection。
- **React 狀態與事實來源**：前端狀態只是快取，會與後端不一致；失敗後要重新向後端確認。
- **錯誤處理的邊界**：在能決定「怎麼讓使用者知道」的那一層 catch（UI），底層回傳結果或轉成狀態。
- 面試問法：「按下登出但請求失敗，畫面該怎麼辦？為什麼不能直接清掉前端狀態？」

---

## R4：密碼上限要驗證 UTF-8 位元組

### 原本怎麼寫，為什麼有問題

- 後端 `@Size(max = 72)`、前端 `maxLength={72}`，數的都是**字元**。
- bcrypt 只使用密碼前 **72 個位元組**。UTF-8 中，英數字 1 byte、中文字 3 bytes、emoji 4 bytes。
- 「中」×24＝72 bytes；再加「文」＝75 bytes，但只有 25 個字元，通過舊限制；而 bcrypt 忽略超過 72 bytes 的部分，這個 75 bytes 的輸入會與前者的雜湊相符——**不同的密碼被視為相同**（審查報告已用 Spring Security 7.1.1 驗證）。

### 修改後怎麼運作

- 新增自訂 Bean Validation 限制 `@MaxUtf8Bytes`（`backend/.../shared/validation/MaxUtf8Bytes.java`＋`MaxUtf8BytesValidator.java`）。**為什麼自訂**：內建的 `@Size` 只能數字元，沒有「位元組」版本；把規則集中成一個可重用、可單獨測試的註解，比在 Service 裡手寫判斷更清楚，也讓 `@Valid` 在進入認證**之前**就拒絕。
- 驗證邏輯：`value.toString().getBytes(UTF_8).length <= max`（與 Spring Security BCrypt 使用相同的計算）；字元數已超過上限時直接判定失敗（每個字元至少 1 byte），避免為超大請求配置同樣大的陣列；`null` 視為通過（必填交給 `@NotBlank`）。
- `LoginRequest.password`：`@NotBlank @MaxUtf8Bytes(PASSWORD_MAX_BYTES)`（72）。**不 trim、不截斷、不預雜湊**；既有必填政策不變。超過 → 400 `VALIDATION_FAILED`（只回欄位名）。
- 前端 `LoginPage`：以 `TextEncoder` 計算位元組（`auth/passwordPolicy.ts`），超過時顯示「目前 N 位元組，上限 72」並停用送出；移除了以字元計的 `maxLength`。這只是提早提示，**後端仍是唯一強制點**。

### 測試如何證明

| 測試 | 證明 |
|---|---|
| `LoginRequestValidationTest`（7 個，純單元） | ASCII 72 通過／73 拒絕；中文 24 字（72）通過／25 字（75）拒絕；36 個中文字（字元數在 72 內、108 bytes）拒絕；emoji 18 個（72，UTF-16 為 36 字元）通過／加 1 個 ASCII（73）拒絕／19 個拒絕；混合 72／73；空字串、全空白、null 仍拒絕；前後空白不被 trim；帳號政策不變 |
| `PasswordByteLimitIntegrationTest`（4 個，真 HTTP＋bcrypt＋資料庫） | **重現審查情境**：資料庫密碼為「中」×24，輸入「中」×24 可登入（200），輸入「中」×24＋「文」得 400 且沒有登入（舊規則下會 200）；ASCII 72 可登入、73 為 400；錯誤訊息不回傳輸入的密碼 |
| `AuthFailureIntegrationTest` 中一項 | 75 bytes 密碼 → 400，且 UserDetailsService 完全沒被呼叫（證明在認證之前拒絕） |
| `frontend/e2e/password-limit.mjs`（`npm run e2e:password`，10 項） | 中文 24／25 字、emoji 18／18+1、ASCII 72／73 的提示與送出狀態；不截斷輸入；**繞過前端直送 75 bytes，後端回 400** |

變異檢查：把 `@MaxUtf8Bytes` 暫時換回 `@Size(max = 72)`，7 個測試轉為失敗；已還原。

### 概念與面試重點

- **字元 vs 位元組**：Java `String.length()` 是 UTF-16 code unit 數；網路與 bcrypt 看的是 UTF-8 位元組。emoji 在 Java 是 2 個 `char`、在 UTF-8 是 4 bytes。
- **bcrypt 72 位元組限制**：超過的部分被忽略。常見處理有拒絕、或預雜湊（如先 SHA-256 再 bcrypt）；後者是設計變更，本專案未規劃，故採拒絕。
- **自訂 Bean Validation**：`@Constraint(validatedBy = ...)` ＋ `ConstraintValidator`；與 `@NotBlank` 組合時各管各的（null 交給必填）。
- **前後端驗證分工**：前端提示改善體驗；後端驗證才是安全邊界（因此要測試「繞過前端」）。
- 面試問法：「為什麼密碼上限用 `@Size(max=72)` 不夠？你會怎麼處理超過 72 bytes？」

---

## 6. 驗證證據（本次修正後實際重跑）

環境：Windows 11、Temurin 21.0.12、Maven 3.9.12、Node 24.14.0、PostgreSQL 17（Docker）、Edge。

| 項目 | 指令 | 實際結果 |
|---|---|---|
| 後端全部測試 | `cd backend; .\mvnw.cmd test` | **47 個，0 失敗、0 錯誤、0 略過，BUILD SUCCESS**（`AuthFailureIntegrationTest` 6、`AuthFlowIntegrationTest` 17、`LoginRequestValidationTest` 7、`PasswordByteLimitIntegrationTest` 4、`TestDatabaseGuardConnectionTest` 2、`TestDatabaseGuardTest` 11）。原有 17 個仍全過；新增 30 個 |
| 前端建置與檢查 | `npm run build`、`npm run lint` | 通過，0 警告 |
| 瀏覽器 E2E | `npm run e2e`（需先啟動資料庫、後端 dev profile、前端） | smoke **16/16**、logout-failure **23/23**、password-limit **10/10** |
| 變異檢查 | 暫時還原修正後執行對應測試，再還原 | R1：2 個測試失敗；R2：1 個失敗；R4：7 個失敗；R3：E2E 在 A 情境失敗 |
| README 的補建測試庫指令 | `docker compose exec db psql ... "CREATE DATABASE renewflow_test"` | 回報 `already exists`（符合預期，證明指令能連到並被資料庫執行） |

**未執行／環境限制**
- README 中「只刪三個開發帳號」的 `DELETE` 指令**沒有執行**（會改動開發資料）；只執行了相同 `WHERE` 條件的 `SELECT` 確認語法與三筆資料存在。
- 沒有測試：HTTPS 下 `Secure` Cookie、Session 30 分鐘逾時、Chrome／Firefox、行動版畫面。
- E2E 需要手動先啟動服務，不在 `mvnw test` 內；測試輸出的 Mockito 自我附加警告來自測試框架，不影響結果。
- 「停用員工」後既有 Session 的逐請求檢查**尚未實作**，已記錄為 T03 前置事項。

---

## 練習（只有這一題）

**背景**：`TestDatabaseGuard.assertSafeToClean(actual, allowedTest, dev)` 目前要求測試庫名稱符合 `[a-z][a-z0-9_]*_test`，且與開發庫不同。假設有人把 `.env` 設成：

```
DB_NAME=renewflow
DB_TEST_NAME=renewflow_test
```

但後來又在測試設定中把 JDBC URL 的資料庫部分手動改成 `renewflow`（開發庫），`renewflow.test.database` 與 `renewflow.test.dev-database` 維持上面的值。

**問題**：
1. 執行測試時，這四個名稱（actual、allowedTest、dev，以及 URL 中的資料庫）各是什麼？guard 會在哪一個條件拒絕？
2. 如果拿掉 `TestDatabaseGuard` 裡「實際名稱必須與明確允許的測試庫完整相等」這個條件、其餘保留，這個情境會被擋下嗎？請說明原因，並指出哪一個條件在這裡**不夠**。

請把你的回答帶回 ChatGPT 對話，由它批改並決定下一題。
