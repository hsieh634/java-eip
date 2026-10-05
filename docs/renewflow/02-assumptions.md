# 假設與決策登錄

這些是學習專案的提案，**不代表任何公司的正式人事或資安規定**。正式使用前需另行確認。

## 假設登錄

| 編號 | 假設 | 影響範圍 | 狀態 |
|---|---|---|---|
| A-01 | 一位員工只有一個角色 | employee.role 單欄位 | 提案 |
| A-02 | 帳號為自訂 username（小寫英數），不強制 Email | 登入、唯一限制 | 提案 |
| A-03 | 固定白班、單一時區 | T03 之後 | 提案，本輪未用到 |
| A-04 | 本輪開發帳號由開發環境初始化（dev profile）建立，密碼來自本機 `.env` | 開發便利性 | 提案；正式環境不得啟用 |
| A-05 | Session 閒置 30 分鐘到期，記憶體內保存，後端重啟後需重新登入 | 單機學習情境 | 提案；多機部署時需 Spring Session 之類方案 |
| A-06 | 本輪不做登入失敗鎖定／速率限制 | 暴力破解風險 | 已知限制，後續階段或部署前補 |

T03（打卡）的學習假設 A-07～A-14 與決策 D-14～D-18 另記於 [`t03/02-assumptions.md`](t03/02-assumptions.md)；T03.1（更正、重新打卡、管理權限）的 A-15～A-22 與 D-19～D-26 記於 [`t03-1/02-assumptions.md`](t03-1/02-assumptions.md)。

> T05 前置的假設 A-70～A-78 與決策 D-60～D-69 見 [`t05-foundation/02-assumptions.md`](t05-foundation/02-assumptions.md)（組織鎖、版本號的限制、示範組織初始化）。

## 技術決策紀錄（ADR 簡版）

| 編號 | 決策 | 原因 | 替代方案與成本 |
|---|---|---|---|
| D-01 | 後端目錄 `backend/`、前端 `frontend/`，同一資料夾（monorepo） | 一份文件、一個版本；學習時不用在多個倉庫間切換 | 兩個倉庫：部署獨立，但本階段增加負擔 |
| D-02 | Spring Boot 4.1.1，Java 21 | 官方系統需求：Boot 4.1.1 支援 Java 17–26；Java 21 為 LTS 且已安裝 | Boot 3.5.x：較舊的主線 |
| D-03 | PostgreSQL 17，經 Docker Compose 啟動 | 本機未安裝 PostgreSQL，Docker Desktop 已安裝；容器可重建、不污染系統 | 原生安裝 PostgreSQL（README 附 winget 替代步驟） |
| D-04 | 整合測試用同一個 PostgreSQL 容器內的獨立資料庫 `renewflow_test`，測試前 Flyway clean＋migrate | 驗證真實 SQL／限制；不引入 Testcontainers 額外套件 | Testcontainers：隔離更好，但需新增套件與 Docker API 相容性 |
| D-05 | 認證採 Session＋HttpOnly Cookie，登入以 JSON `POST /api/auth/login` | 與指南一致；不引入 JWT | JWT：需處理撤銷、儲存位置，本案無必要 |
| D-06 | 登入用 Controller＋`AuthenticationManager`，登出用 Spring Security 內建 logout | 登入流程可從程式讀得懂；登出的 Session 失效與 CSRF 清除由框架完成較不易出錯 | 自訂 Filter：更貼近框架但較難入門 |
| D-07 | 前端不引入 react-router、狀態管理或 UI 套件 | 本輪僅兩個畫面（登入／首頁），以登入狀態切換即可 | T03 起畫面增多時再引入 react-router，並說明原因 |
| D-08 | 錯誤回應格式：`{ "code": "...", "message": "..." }` | 簡單、前端好處理；不暴露堆疊或內部細節 | RFC 9457 ProblemDetail：更標準，欄位較多 |
| D-09 | 密碼編碼用 `DelegatingPasswordEncoder`（預設 bcrypt，雜湊前綴 `{bcrypt}`） | Spring 建議作法，日後可平滑升級演算法 | 直接 `BCryptPasswordEncoder`：欄位較短但難升級 |
| D-10 | 角色不做階層（ADMIN 不自動包含 MANAGER） | 指南規定管理員不能任意審核 | 角色階層：與需求衝突 |
| D-11 | 整合測試清空資料庫前，以「資料庫實際回報的名稱」比對明確允許的測試庫，並確認與開發庫不同（審查修正 R1） | JDBC URL 字串結尾不等於實際連到的資料庫（query parameter 可偽裝） | 只靠命名慣例或 URL：可被偽裝或設定錯誤繞過 |
| D-12 | 密碼上限以 UTF-8 位元組計（自訂 `@MaxUtf8Bytes`），不 trim、不截斷、不預雜湊（審查修正 R4） | bcrypt 只用前 72 bytes；字元數無法保證位元組上限 | 預雜湊（SHA-256 再 bcrypt）可取消限制，但屬未規劃的設計變更，需另行決定 |
| D-13 | 認證機制故障用非 Security 的例外表示，回 500（審查修正 R2） | Security 的 AuthenticationException 最終一律變 401，會把故障偽裝成帳密錯誤 | 在 entry point 依例外型別分流：較分散、較難測 |
