# 版本紀錄

## 第 1 輪：T01＋T02

- Repository：`https://github.com/hsieh634/java-eip`，分支 `main`。
- 審查基準 commit：標題為 `feat: T01-T02 ...` 的 commit，完整 SHA 見交付報告（文件不自我引用 SHA）。
- 狀態：已驗證（作者自測，見學習文件第 9 節）；**尚未經對話助理審查**。

### 實際版本

| 元件 | 版本 | 來源 |
|---|---|---|
| Java（Temurin） | 21.0.12 | `java -version`；`pom.xml` 設 `java.version=21` |
| Maven（Wrapper） | 3.9.12（Wrapper 3.3.4） | `backend/.mvn/wrapper/maven-wrapper.properties` |
| Spring Boot | 4.1.1 | `pom.xml` parent |
| Spring Framework／Security | 7.0.9／7.1.1 | Boot 4.1.1 BOM |
| Hibernate ORM／Flyway／PostgreSQL JDBC／Tomcat | 7.4.5.Final／12.4.0／42.7.13／11.0.24 | Boot 4.1.1 BOM |
| PostgreSQL（Docker `postgres:17-alpine`） | 17.11 | 啟動日誌 |
| Node.js／npm | 24.14.0／11.9.0 | `node -v`、`npm -v` |
| React／react-dom | 19.3.0（MIT） | `frontend/package-lock.json` |
| TypeScript | 6.0.3（**Apache-2.0**） | `package-lock.json`；授權取自套件的 `package.json` |
| Vite／@vitejs/plugin-react | 8.3.1／6.1.1（MIT） | `package-lock.json` |
| oxlint／puppeteer-core | 1.86.0（MIT）／25.12.0（Apache-2.0） | `package-lock.json` |

`package.json` 內的範圍（如 `^19.2.8`、`^1.81.0`）只是允許的最低版本；實際安裝版本以 lockfile 為準。

### 官方依據

Spring Boot 4.1.1 系統需求（支援 Java 17–26、Maven 3.6.3+、Spring Framework 7.0.9+，2026-10-01 查閱）；Spring Security CSRF 文件（SPA 範式）。

### 已知未解決事項

登入失敗鎖定、改密碼、員工管理、HTTPS／`Secure` Cookie 實測、Session 逾時實測、正式環境同源部署（T06）。（資料庫中斷的故障注入測試已於第 2 輪補上。）

### 本輪對先前口頭報告的更正

1. 先前寫「oxlint 1.81」是 `package.json` 的最低版本範圍，實際安裝為 1.86.0；React 實際為 19.3.0。
2. 先前寫「瀏覽器腳本不在專案內」；現已整理為 `frontend/e2e/smoke.mjs`（`npm run e2e`），並新增開發相依套件 `puppeteer-core`。
3. 先前寫「資料夾不是 git repository」；現已初始化並推送。

## 第 2 輪：審查修正 R1～R4

- 審查基準：`d72fb814f97bb4e4da2e2b61794cefd15a61dd9f`。
- 修正 commit：標題為 `fix: T01-T02 review fixes ...` 的 commit，完整 SHA 見交付報告。
- 新增相依套件：**無**（Mockito 與 Bean Validation 來自既有的 Spring Boot starter；E2E 沿用 puppeteer-core）。
- 後端測試 17 → 47；前端 E2E 16 → 16＋23＋10。證據與限制見 `docs/learning/T01-T02-review-fixes.md`。
- 更正：TypeScript 授權為 Apache-2.0（先前口頭報告誤列為 MIT）；安裝說明改用 `npm ci`；停用員工後 Session 的實際行為是「下一次 `/me` 才作廢」，T03 前需補共用檢查。
- 狀態：已驗證（作者自測）；**尚未經對話助理審查**。

## 第 3 輪：T03 上下班打卡與本人出勤紀錄（TeamPortal）

- 審查基準（前一輪）：`3bb5299d0e220ed514c76fd9034a9f19020809c6`。
- T03 commit：標題為 `feat: T03 ...` 的 commit，完整 SHA 見交付報告。
- 新增相依套件：**無**（Mockito、Flyway API、Bean Validation 皆來自既有依賴；E2E 沿用 puppeteer-core）。新增 Flyway migration：`V2__create_attendance.sql`（V1 未修改）。
- 產品名稱：TeamPortal（企業員工入口平台）；Repository、資料夾、Java package、資料庫、容器、環境變數沿用既有名稱。
- 測試：後端 47 → 94；E2E：dev 環境 18＋23＋10，隔離堆疊 37。
- 狀態：已驗證（作者自測）；**尚未經對話助理審查**。
- 規格：`docs/renewflow/t03/`；學習文件：`docs/learning/T03-attendance.md`。

## 第 4 輪：T03.1 出勤更正、重新打卡申請與管理權限

- 審查基準（前一輪）：`3616464b38715e4a72a0c1813479892678a13361`。
- T03.1 commit：標題為 `feat: T03.1 ...` 的 commit，完整 SHA 見交付報告。
- 新增相依套件：**無**。新增 Flyway migration：`V3__attendance_corrections.sql`（V1、V2 未修改）。
- 新增本機開發帳號 `developer`（ADMIN，僅 dev profile；密碼 `RENEWFLOW_DEV_DEVELOPER_PASSWORD`，無預設值）。
- 補強 T03：E2E 隔離堆疊的資料庫檢查提前到 migration 之前（`E2eFlywayConfig`）。
- 測試：後端 94 → 151；E2E：dev 環境 18＋23＋10，隔離堆疊 37＋52。
- 狀態：已驗證（作者自測）；**尚未經對話助理審查**。
- 規格：`docs/renewflow/t03-1/`；學習文件：`docs/learning/T03-1-attendance-corrections.md`。

## 第 5 輪：T03.1 審查修正（R1–R3）＋打卡申請操作調整

- 審查基準：`f16b5f0e5c6d9a6dc2d3ac9aa5a844d032bc4763`。
- 修正 commit：標題為 `fix: T03.1 review round 1 ...` 的 commit，完整 SHA 見交付報告。
- 新增相依套件、工具、映像檔：**無**。新增 migration：**無**（V1–V3 未修改；V3 已有所需欄位）。
- R1：直接更正同時比對紀錄 ID＋版本。R2：相同條件再查詢會重新取得。R3：E2E 保護測試只清理本次建立的臨時庫（`TemporaryDatabases`）。
- 新操作：申請撤銷下班打卡、申請整組作廢並重新打卡、申請更正／補登時間；申請送出時核對表單快照；回應新增 `kind`／`requestKind`、今日回應新增 `recordId`／`version`。
- 測試：後端 151 → 166；E2E：dev 環境 18＋23＋10，隔離堆疊 37＋74。
- 狀態：已實作／作者自測完成／**等待 ChatGPT 複審**。
- 規格：`docs/renewflow/t03-1/review-round-1.md`；學習文件：`docs/learning/T03-1-review-fixes.md`。

## 第 6 輪：T03.2 公司範圍定位打卡與管理員設定

- 審查基準（前一輪）：`4f14ee4e757fa43a0496c4a98d0371095342a0d5`。
- T03.2 commit：標題為 `feat: T03.2 ...` 的 commit，完整 SHA 見交付報告。
- 新增相依套件、工具、映像檔、服務：**無**。新增 migration：`V4__geofence_attendance.sql`（只新增資料表；V1～V3 未修改）。
- 指南第 2 節「GPS 暫不加入」標記為原始範圍，補充 T03.2 已授權（保留歷史脈絡）。
- 既有測試調整（保留原驗收意義）：V2→V3 升級測試固定 `target("3")`；乾淨庫版本清單加入 4；打卡服務方法多一個定位參數（測試傳 `null`）。
- 測試：後端 166 → 209；E2E：dev 環境 18＋23＋10，隔離堆疊 37＋74＋59（新增 `geofence.mjs`）。
- 開發庫已套用 V4：既有員工、出勤、申請、歷程逐列相同；定位限制保持關閉、公司中心未設定。
- 狀態：已實作／作者自測完成／**等待 ChatGPT 複審**。
- 規格：`docs/renewflow/t03-2/`；學習文件：`docs/learning/T03-2-geofenced-attendance.md`。

## 第 7 輪：T03.2 複審修正（R1、R2）

- 審查基準：`2d6f33359edb8198a450a98051e3407592f72d1d`。
- 修正 commit：標題為 `fix: T03.2 review round 1 ...` 的 commit，完整 SHA 見交付報告。
- 只修改前端 `useTodayAttendance`（打卡操作生命週期、今日狀態查詢序號）；後端、API、migration 未修改；新增相依套件：**無**。
- 新增 E2E `geofence-races.mjs`（`npm run e2e:races`，隔離堆疊），抽出共用假定位 `e2e/geo.mjs`。修正前 11/22 失敗、修正後 22/22。
- 測試：E2E 隔離堆疊 37＋74＋59＋22；dev 環境 18＋23＋10。後端 209 沿用上一輪證據（本次未重跑，因後端未修改）。
- 狀態：已實作／作者自測完成／**等待 ChatGPT 複審**。
- 規格：`docs/renewflow/t03-2/review-round-1.md`；學習文件：`docs/learning/T03-2-review-fixes.md`。

## 第 8 輪：T04 最小專案管理與個人工時回報

- 審查基準（前一輪）：`93048f35886d8bf6eb51cc7e1dccc310d52e6efc`。
- T04 commit：標題為 `feat: T04 ...` 的 commit，完整 SHA 見交付報告。
- 新增相依套件、工具、映像檔、服務：**無**。新增 migration：`V5__projects_and_timesheets.sql`（只新增 `project`、`timesheet_entry`；V1～V4 未修改；不建立預設資料）。
- 共用變更：`attendance.DateRange` 改為 public；新增 `shared/web/ApiException`；全域設定 `spring.jackson.deserialization.accept-float-as-int: false`（整數欄位收到小數改回 400，不再默默截斷——由 T04 測試發現）。
- 既有測試調整（保留原驗收意義）：測試基底類別改為 public／protected 以便新套件共用；`TestData.reset` 加入新表；乾淨庫與 V1 升級的版本清單加入 5；smoke／打卡 E2E 的導覽檢查改為「工時回報已啟用、請假與設定仍未啟用」。
- 測試：後端 209 → 239；E2E：dev 環境 18＋23＋10，隔離堆疊 37＋74＋59＋22＋49（新增 `timesheets.mjs`）。
- 開發庫已套用 V5：員工、出勤、申請、歷程、定位設定與歷程、打卡事件逐列雜湊相同；定位維持關閉、中心未設定；專案與工時皆為 0 筆。
- 狀態：已實作／作者自測完成／**等待 ChatGPT 複審**。
- 規格：`docs/renewflow/t04/`；學習文件：`docs/learning/T04-projects-and-timesheets.md`。

## 第 9 輪：T04 複審修正（R1、R2）＋STMAX 示範初始化＋產品方向文件

- 審查基準：`3b1e7962da6dc201e48935d41b0cc0732764e5d4`。
- 本批 commit：標題為 `fix: T04 review round 1 ...` 的 commit，完整 SHA 見交付報告。
- 前端：`TimesheetPage` 的提交快照與鎖定（R1）、頁內專案清單載入（R2）；共用 `useLoad` 未修改。
- 後端：新增 `StmaxDemoSeedRunner`（dev 限定、預設關閉、沒有預設密碼、冪等、碰撞時安全停止）與 `EmployeeRepository.findByEmployeeNo`；設定 `renewflow.demo.stmax.*`。沒有新的 migration（仍為 V1～V5）。新增相依套件：**無**。
- 測試：後端 239 → 246；E2E 新增 `timesheets-review.mjs`（修正前 12/20、修正後 24/24）；`timesheets.mjs` 中 W2、W5 期望隨新規則更新（49/49）。
- 既有測試修正：`attendance-corrections.mjs` E5 原本把上班時間寫死為 08:00，在台北 08:00 前執行會被前端「下班早於上班」擋下（與本批修改無關、取決於執行時刻）；改為 00:00:00。
- 開發庫：依使用者授權，在本機 `.env` 產生隨機示範密碼並開啟開關，重啟後端建立六個 STMAX 示範帳號（`stmax_max`、`stmax_iris`、`stmax_leo`、`stmax_mia`、`stmax_ryan`、`stmax_zoe`）。其餘員工、出勤、申請、歷程、定位設定與歷程、打卡事件、專案、工時逐表比對不變。
- 文件：`product-direction.md`、`roadmap.md`、`demo-stmax.md`、`t04/review-round-1.md`、學習文件 `T04-review-fixes-and-stmax-demo.md`。
- 狀態：已實作／作者自測完成／**等待 ChatGPT 複審**。
