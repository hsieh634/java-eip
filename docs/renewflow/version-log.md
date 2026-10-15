# 版本紀錄

## 2026-10-15：T05.2 第一批請假單關卡設定與版本

比較基準e6397659df9628362774f2438c2cfa60db293825，本批全新實作，沒有重做T05.1／開發展示。T05.1針對性程式複審完成是使用者帶回，真人與全面品質驗收仍待執行。

完成ADMIN設定／預覽／發布、單草稿與單目前發布指標、不可變版本／append-only操作歷程、同UUID查證與重試、新提交原子快照／個人優先／改派保留。沿用共同鎖與R1範圍，無通用BPM或依賴套件。V10新增三表及nullable快照／流程FK，V1～V9不改；舊schema測試只更新latest版本清單及測試清理表。

本輪真完整後端367／367、六支E2E285、build／lint無警告；四個針對性變異失敗且還原後全套通過。開發V9備份／還原16表、V10舊欄位保留及唯讀smoke通過，未發布／提交／重建demo。初始化CLI守門只增加已驗證V10相容；沒有新工具安裝、映像、服務或費用。

本批驗證臨時後端已關閉以交還Max自行PowerShell啟動，原5173保留，其他8081不動，測試庫／容器暫存archive清除，本機備份保留。詳見[verification](t05-2/verification.md)、[操作](t05-2/manual-verification.md)、[教材](../learning/T05-2-approval-settings-and-versions.md)。交付SHA見共用交接及Git；作者自測完成、等待ChatGPT複審，真人待Max，不進下一批。

## 2026-10-13：T05.1 第1輪 R1／R2＋STMAX 客戶展示 v1

接手main／遠端基準`47f7fdfec33513bde50396e2864575f4c3f6e054`，初始乾淨；前批實作`1ea6c625894d4645e07e5cafd70ee454d6956eb3`。使用者帶回ChatGPT34項受控前端通過，R1／R2需修正；本輪不是ChatGPT複審通過。

R1將審核入口（含ADMIN）限制為所選提交及必要前序，版本、歷程、決定摘要、有效基礎及出勤一致，介面明示歷史範圍；本人／合法ADMIN管理完整。R2修撤回ID／操作／時段及原有效保留提示，後端撤回交易不變。

新增本機原生CLI預設關閉、dev／catalog／備份及碰撞核對、固定D、逐步持久化續跑與人工保護；六demo_*、三專案、10工時1560分、8MANUAL、10業務單17提交，展示CLOCK／GPS新增0。獨立.env展示密碼不入Git／日誌。無新migration、套件、映像或常駐服務；V1～V9及共用認證不變。

作者R1真核心舊3失敗／4→修正4／4；R2函式0／5→5／5；後端349／349、五支E2E252、build／lint通過。V9備份／還原16表相同，載入原列相同、兩次重跑及唯讀展示後全表相同；本機新版已啟用，測試資源已清除。見[規格](t05-1/review-round-1.md)、[驗證](t05-1/review-round-1-verification.md)、[客戶指南](demo-stmax-customer.md)、[教材](../learning/T05-1-review-scope-and-customer-demo.md)。

狀態：作者自測完成，等待ChatGPT複審；真人與客戶Demo待Max；T05.1未結案，不進下一階段。實作SHA `233e6bb7184569d7a69e305c3b48856a045c7257`已推送main；後續只補交接文件，完整交付SHA見最終回報／Git。

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

## 第 10 輪：T04 第 2 輪複審（R1 重試邊界：R1-A、R1-B）

- 審查基準：`00593e78ba4294c185a56ead7808ab5571c4b4fa`。上一輪 R2 已被接受；STMAX 初始化未發現阻擋問題。
- 本批 commit：標題為 `fix: T04 review round 2 ...` 的 commit，完整 SHA 見交付報告。
- 只修改前端 `TimesheetPage.tsx`：未確認快照（`pending`）決定「原提交是否尚未確認」，重試失敗不解鎖（R1-A）；確認原提交不受「沒有啟用專案」限制（R1-B）。後端、API、migration、共用元件、相依套件、示範帳號都未修改。
- 測試：新增 `timesheets-retry-boundaries.mjs`（基準版本 13/24、修正後 25/25）；`timesheets-review.mjs` 24/24、`timesheets.mjs` 49/49。後端 246 沿用上一輪證據（本次未重跑，因後端未修改）。
- 上一輪登入頁偶發逾時（`attendance-corrections.mjs` 中一次 `#username` 等待逾時）：本輪未重跑該腳本、也未再重現，**原因仍未確定**。
- 狀態：已實作／作者自測完成／**等待 ChatGPT 複審**。
- 規格：`docs/renewflow/t04/review-round-2.md`；學習文件：`docs/learning/T04-retry-state-boundaries.md`。

## 第 11 輪：T04 結案＋T05 前置——最小組織與員工管理

- 審查基準：`f7204277033b3d85ab99a6539a5a15b4239d856b`。
- **T04 已通過 ChatGPT 複審，結案。** 複審證據的性質：ChatGPT 完成程式審查及實際函式的受控驗證；完整 E2E、build／lint 是作者自測證據。**不是** ChatGPT 重新執行了全部測試。
- 本批 commit：標題為 `feat: T05 foundation ...` 的 commit，完整 SHA 見交付報告。
- 定位：**T05 前置子任務**（不更動 T01～T06 編號）；不包含請假。
- 新增相依套件、工具、映像檔、服務：**無**。新增 migration：`V6__organization_foundation.sql`（新表 `department`、`organization_history`；`employee` 新增 `department_id`、`manager_id`、`version`；V1～V5 未修改；不建立任何部門或員工資料）。
- 後端：新套件 `organization`（部門、員工管理、組織鎖 `pg_advisory_xact_lock`、只新增的組織歷程、dev 專用 `StmaxOrganizationSeedRunner`）；`Employee` 新增三個 JPA 唯讀欄位；`StmaxDemoSeedRunner` 加上 `@Order(100)`；設定 `renewflow.demo.stmax.org.enabled`（`RENEWFLOW_DEMO_STMAX_ORG_ENABLED`，預設 `false`）。
- 前端：「組織與員工」頁（`#/admin/organization`，只有 ADMIN 有入口）；側邊選單的管理入口改為清單渲染（行為不變）。
- 既有測試調整（保留原驗收意義）：`TestData.reset` 加入新表；乾淨庫與 V1 升級的版本清單加入 6；測試設定的連線池常駐連線改為 2、閒置 10 秒釋放（上限不變；原因是全套測試實際出現 `too many clients already`，見 D-69）。
- 測試：後端 246 → 282；E2E 新增 `organization.mjs`（54/54）；回歸 smoke 18、logout 23、password 10、attendance 37、corrections 74、geofence 59、races 22、timesheets 49、timesheets-review 24、timesheets-retry 25 全部通過。變異驗證 5 項（組織鎖、循環、最後 ADMIN、Service 權限、部屬）都使對應測試失敗。
- 開發庫：依授權在本機 `.env` 開啟 `RENEWFLOW_DEMO_STMAX_ORG_ENABLED`，重啟後端套用 V6 與 STMAX 示範組織（3 個部門、6 人的部門與主管、9 筆系統來源歷程）。非 STMAX 員工的 V5 欄位、STMAX 的 V5 欄位（不含 `updated_at`）、其他所有表逐列雜湊相同；第二次重啟沒有任何變更。
- 上一輪記錄的偶發登入逾時：本輪全部 E2E 未再出現，**原因仍未確定**。
- 狀態：已實作／作者自測完成／**等待 ChatGPT 複審**。
- 規格：`docs/renewflow/t05-foundation/`；學習文件：`docs/learning/T05-foundation-organization-and-employees.md`。

## 第 12 輪：T05 前置結案＋T05 請假申請與主管審核

- 審查基準：`5187dec7292a5e357504f8c86759a51e526a521d`。
- **T05 前置已通過 ChatGPT 本輪複審，結案。** 複審證據的性質：
  - ChatGPT 核對指定 commit 的實際程式、規格、migration 與相關測試。
  - ChatGPT 擷取實際後端方法完成 26 項受控檢查、前端函式完成 8 項受控檢查，全部通過。
  - 受控檢查使用 Repository、鎖、API 等替身，**沒有**驗證真正的 Spring／PostgreSQL 整合行為。
  - 完整後端 282 項測試、11 支 E2E、build／lint 是**作者自測證據**，不是 ChatGPT 完整重跑。
  - 前置通過複審**不代表**正式環境或商用品質已全面驗收。
- 本批 commit：標題為 `feat: T05 leave requests and approvals ...` 的 commit，完整 SHA 見交付報告。
- 新增相依套件、工具、映像檔、服務：**無**。
- 新增 migration：`V7__leave_requests.sql`（`leave_request`、`leave_approver_setting`、`leave_history`；V1～V6 未修改；不建立任何假單或設定、不修改既有資料）。
- 後端：
  - 新模組 `leave`：本人送出／清單／詳情／撤回；審核人清單／核准／退回；ADMIN 查詢／改派／指定請假審核人。
  - 共用變更：`OrganizationLock.acquireFor(actor)`。取鎖後重新確認操作者，部門與員工寫入也改用它。
  - 受影響的舊測試 O-09 改為驗證 401；`LAST_ADMIN` 改由新的 O-09b 以 Spy 驗證，保留為第二道防線。
- 前端：「請假」「請假審核」「請假管理」三頁；側邊選單改為清單渲染（行為不變）；首頁的「待審核（尚未啟用）」卡片換成請假入口；smoke／attendance E2E 的導覽檢查隨之更新。
- 測試：
  - 後端 282 → 310。
  - 新增 E2E `leave.mjs` 56/56；12 支 E2E 全部通過。
  - 變異驗證 8 項：重疊、送出取鎖、審核人範圍、自我改派、版本、requestId 內容比對、歷程回滾、取鎖後重新確認操作者。全部使對應測試失敗。
- 開發庫：重啟前已說明原因與影響，之後套用 V7。既有 11 張表 md5 逐列相同，新表 0 筆。STMAX 帳號與組織初始化皆「已符合」，沒有寫入。**沒有**代為建立 Max → Iris 設定，也沒有送出任何假單。
- 狀態：**T05 作者自測完成，等待 ChatGPT 複審**。
- 規格：`docs/renewflow/t05/`；學習文件：`docs/learning/T05-leave-and-approval.md`。

## 第 13 輪：T05 第 1 輪複審修正（R1～R3）

- 審查基準：`77dac92235c2f85388ece4c33e7ce1b2dcc53a92`。
- **ChatGPT 本輪證據的性質**：
  - 核對指定 SHA、實際程式、V7、規格與測試設計；以實際前端函式與 API 函式做 12 項受控檢查，確認三個問題。
  - hooks、fetch 等使用受控替身，沒有完整重跑瀏覽器 E2E 或 Spring／PostgreSQL 整合測試。
  - 310 項後端測試、12 支 E2E、build／lint 仍是作者自測證據。
- 本批 commit：標題為 `fix: T05 review round 1 ...` 的 commit，完整 SHA 見交付報告。
- **只修改前端**：
  - R1：新增 `leave/submitOutcome.ts`。成功回應在執行期驗證；POST 送出後凡是無法確認的結果（含 JSON 損壞、本文讀取中斷、缺欄位、requestId／內容不符）都保存原快照並鎖定。`submitLeave` 回傳 `unknown`。
  - R2：`LeaveList` 每次有效的查詢都遞增 `searchSeq`。
  - R3：`SettingForm` 對失效的原指定審核人提供 disabled 保留選項與警告。
- **沒有修改**：後端、API、V1～V7 migration、共用元件（`http.ts`、`useLoad`、認證）。新增相依套件：**無**。
- 測試：新增 `leave-review.mjs`（`npm run e2e:leave-review`）。
  - 用最終版測試跑基準程式：24／43。R1、R2、R3 的行為檢查失敗；R1-0（新模組不存在）與 R1-6（前置情境無法成立）不算舊缺陷證據。
  - 修正後：46／46。
  - 回歸：leave 56、timesheets 49、timesheets-review 24、timesheets-retry 25、organization 54。lint、build 通過。
  - 後端 310 項沿用上一輪證據（本輪沒有後端變更）；其他 7 支 E2E 本輪沒有重跑。
- 開發環境：沒有重啟開發後端，沒有操作開發庫。測試過程中曾有一次腳本崩潰留下隔離堆疊（8081／5174），已確認是本次的 e2e 行程並終止；8080／5173 沒有動。
- 觀察（本輪不修改）：工時表單 `TimesheetPage` 的結果判定與請假的 R1 原寫法相同，可能有同類問題，記錄於學習文件練習題。
- 狀態：**T05 第 1 輪修正作者自測完成，等待 ChatGPT 複審**。
- 規格：`docs/renewflow/t05/review-round-1.md`；學習文件：`docs/learning/T05-review-fixes.md`。


## 第 14 輪：T03.3 多組打卡與 BUG-TP-001

- 基準：`af596813c4a8c1d89afcedce1d14cc0ee0db5508`；完成實作 SHA：`db5125b9785471aab9ccced0d2eebd2c8fa87f6e`（其後僅補交接版本）。
- 接手時 main／工作區乾淨，沒有 Claude 未提交成果。本輪 Codex 完成需求至 Archive 文件與實作。
- 多組出勤、穩定 requestId、持久化原成功回應、精確下班 ID＋版本、共同鎖與交易、多組更正／補登／作廢相容；V8 保留既有資料，V1～V7 不改。
- 工時 BUG-TP-001：原版 11／17 → 同批修補後 17／17；追加上層表單鎖保護後最終 21／21。成功回應執行期檢查，未知提交保留原本文，重試被拒不清快照。
- 本輪新執行證據：後端 322／322、名稱調整後相關 30／30、build／lint、12 支 E2E 共 484 檢查點通過；隔離 V7→V8 14 表逐列比較相同。詳細結果與範圍見 [verification](t03-3/verification.md)。
- 人工讀截圖與差異；手機是模擬尺寸。真人／實機／正式環境／負載／全138案未驗證。
- 沿用證據：T05 R1～R3 通過 ChatGPT 程式複審與 80 項受控前端函式檢查（使用者提供），不等同完整 E2E 或 Spring／PostgreSQL 整合；本輪不重做已通過修正。
- 未新增套件、工具、映像檔或服務；未修改共用 HTTP／認證／請假實作；未動開發 8080，開發庫仍 V7。測試堆疊已清理。依本輪明確授權一般 commit／push main，不部署。
- 狀態：T03.3 與 BUG 修補作者自測完成，等待 ChatGPT 複審；T05.1～T05.3 與可設定簽核未實作，只更新方向文件。
- 規格：[t03-3](t03-3/)；教材：[T03.3](../learning/T03-3-multiple-attendance-and-submit-results.md)；共用交接：[development-handoff](development-handoff.md)。


## 第 15 輪：T03.3 第 1 輪複審 R1

- 基準：`d3d2d52761f9cb8e958828022d6b2f67d633ef5f`；修正實作 SHA：`eb5d6840ca5793e06dfc4e2782fdba20bd4757c4`（其後僅補交接版本）。
- CorrectionForm 指定 ID 查不到時保持待選，顯示原 ID，不改另一組或補登；使用者明選後才提交。日期切換清空原快照與時間，指定入口重新待選，通用入口正常預設保留。
- 本輪原版／修補使用同支 R1 腳本：21／29 → 29／29；build／lint、多組 17／17、更正審核 74／74、基本出勤 37／37 通過。真 POST 與 renewflow_test 資料比較；受控舊清單／延遲 GET 不代表真人或真網路故障。
- 未改後端、API／DTO、migration、共用元件／HTTP／認證或相依套件。前批後端與 migration 證據沿用；本輪不重跑後端全套。未重啟開發服務、未升級開發 V7，未替真使用者操作。
- 依本輪附件明確授權一般 commit／push main，不 force push、不部署。狀態：作者自測完成，等待 ChatGPT 複審；T03.3 未結案，T05.1 尚未開始。
- 文件：[review-round-1](t03-3/review-round-1.md)、[教材](../learning/T03-3-target-selection-review-fix.md)、[共用交接](development-handoff.md)。

## 2026-10-13：T03.3本機啟用與T05.1

- 基準72ba3265037a7cd09c49f3f018b0ee408170f87e，初始乾淨；前輪R1 eb5d6840ca5793e06dfc4e2782fdba20bd4757c4不重做。實作／完整交付SHA由後續交接紀錄與git rev-parse HEAD核對。
- 使用者帶回T03.3 R1程式複審通過，ChatGPT受控實際表單20項11／20→20／20；BUG-TP-001前輪通過。與作者整合／E2E／29項證據分開，真人未完成。
- 新版本與有效內容、修改／重送／變更／整段或單側銷假、重新解析並凍結審核人、持久化去重、重疊、共同鎖、全交易回滾、限定出勤Service與凍結審核歷程；前端unknown保持原快照／UUID、明確ID、鎖定與警告放棄。
- 新V9，V1～V8不改；開發原已V8，三份備份／臨時還原確認，最終V9十五舊表投影相同、2業務單初始化零錯誤；不修改.env或既有帳號／密碼／角色／主管設定。
- 本輪實跑：後端345／345，build／lint exit0，新E2E52＋10支回歸383＝435檢查點，5項變異各Failure且還原通過；手機詳情模擬補強後重跑52通過，不稱實機。
- 8080 PID7260、5173 PID516已載入新版，認證與GET三帳號檢查業務寫入0；其他8081保留。隔離服務與本輪測試庫／container archive已清理，本機備份保留。
- 詳細需求、API、安全、逐案證據、限制、Review／Archive及教材在[t05-1/](t05-1/)。真人M33／M51全部未執行；舊138案不宣稱人工完成。
- 一般commit／push main依附件授權；敏感／無關產物檢查後提交，不force push、不部署。無新相依、工具或費用。
- 狀態：T05.1 作者自測完成，等待 ChatGPT 複審；真人驗收依實際結果記錄。可設定簽核流程與拖拉設計器尚未實作。

T05.1實作commit：`1ea6c625894d4645e07e5cafd70ee454d6956eb3`，已一般push main並核對遠端一致；後續只有交接SHA與Tasks更新。完整交付SHA以最終Git紀錄為準。
