# T03.3 與 BUG-TP-001 作者驗證

日期：2026-10-07。執行者：Codex。基準 `af596813c4a8c1d89afcedce1d14cc0ee0db5508`；完成實作 commit 見 [交接](../development-handoff.md)，其後僅補交接 SHA。以下為本輪作者證據，尚未經 ChatGPT 複審。

## 最新：T03.3 第 1 輪 R1（本輪新證據）

比較基準 `d3d2d52761f9cb8e958828022d6b2f67d633ef5f`。同一 R1 腳本基準 21／29（八項缺陷失敗）→ 修補 29／29；build／lint、多組 17／17、更正審核 74／74、基本出勤 37／37 通過，exit 0。逐案前置／步驟／副作用／版本／log 與作者 Review／Archive 見 [review-round-1](review-round-1.md)。受控舊清單及延遲 GET，真表單提交與隔離 DB 比較；真人驗收尚未執行。本輪未改後端、API、migration、共用元件或套件，沒有重跑後端全套。

## 前批實作證據（本輪沿用）

以下為前批 af5968 → db5125b 的作者執行紀錄，不改稱本輪重新執行。前批後端 322／30、migration 14 表及其他模組 E2E 沿用；本輪四支 E2E 的新紀錄以上節為準。

## 環境與執行方式

後端採既有 Maven wrapper、Spring Boot、PostgreSQL；前端採既有 Node、Puppeteer、build／lint。沒有新增相依套件、工具、映像檔或服務。隔離資料庫為 `renewflow_test`；`TestDatabaseGuard` 讀實際 catalog 並比對 allowlist，JUnit 與 E2E 依序執行，避免互相清除資料。E2E 自行啟動 8081／5174 堆疊，使用腳本專用員工及專案，結束後只清理自己建立的 PID；不操作真實使用者或開發庫資料。

| 驗證 | 指令／證據 | 結果 |
|---|---|---|
| 後端全套 | `backend/.\mvnw.cmd -B test`；`backend/target/t03-3-backend-final.log` | 322 項，失敗 0、錯誤 0、略過 0，BUILD SUCCESS，程序 exit 0 |
| 前端建置 | `npm run build`；`frontend/e2e/output/t03-3-build-final.log` | 通過，exit 0 |
| 前端靜態檢查 | `npm run lint`；`frontend/e2e/output/t03-3-lint-final.log` | 通過，exit 0 |
| V7 → V8 | `MultipleAttendanceSchemaTest`；[逐表比較](migration-evidence.md) | 14 張既有表筆數及逐列內容雜湊相同，新去重表 0 筆 |
| 乾淨庫與索引 | `AttendanceSchemaTest`、`LeaveSchemaTest`、`OrganizationSchemaTest` | V1～V8 migration 與既有 FK／唯一索引測試通過 |

瀏覽器逐支結果（指令為 `node e2e/<名稱>.mjs`，工作目錄 `frontend`；紀錄在 `e2e/output/t03-3-<名稱>.log`）：

| 腳本 | 結果 |
|---|---|
| multiple-attendance | 17／17 |
| timesheets-success-body | 21／21 |
| attendance | 37／37 |
| attendance-corrections | 74／74 |
| geofence | 59／59 |
| geofence-races | 22／22 |
| timesheets | 49／49 |
| timesheets-review | 24／24 |
| timesheets-retry-boundaries | 25／25 |
| leave | 56／56 |
| leave-review | 46／46 |
| organization | 54／54 |

12 支全部 exit 0，合計 484 個腳本檢查點；檢查點可能涵蓋同一情境的不同斷言，不等同 484 個獨立需求案例。最後只調整註解與測試顯示名稱，再針對相關後端類別重跑，追加紀錄：`backend/target/t03-3-label-verification.log`，30 項通過，失敗／錯誤／略過皆 0，BUILD SUCCESS、exit 0（12:09:10）。

## 逐案資料、動作與實際結果

配合 [specification](specification.md) 閱讀；下列後端案例都在本輪全套中通過。測試方法保存可重跑的精確資料與 assert；瀏覽器 check 名稱帶相應 ID。

| ID | 前置與動作 | 預期及實際資料副作用 | 本輪證據 |
|---|---|---|---|
| M01 | 專用員工；Clock 為台北 2026-10-01；09～12、14～18、19～20 三組依序打卡，第三組先查未完成合計 | 200；三個不同 ID；兩組合計 7 小時、第三組未完成不算，完成後 8 小時；每次打卡一事件與去重快照 | `MultipleAttendanceIntegrationTest.twoAndThreeSessionsTotalsAndOldReplay`；multiple-attendance |
| M02 | 工作中再以新鍵上班；無上班或已完成時用新鍵下班 | 409 ALREADY_CLOCKED_IN／CLOCK_IN_REQUIRED／ALREADY_CLOCKED_OUT；原資料不變 | 同上；`AttendanceIntegrationTest.duplicateClockIn/clockOutWithoutClockIn/duplicateClockOut` |
| M03 | 第一組已下班並另開新組，再送第一組原上班或下班本文與 UUID | 200 原成功快照；不新增時段、事件、去重列，不關閉新組 | `twoAndThreeSessionsTotalsAndOldReplay/replayOutAndRejectChangedContentOrAction`；multiple-attendance |
| M04 | 已成功的下班鍵改上班，或改 expectedVersion；另用未成功舊下班意圖對新組提交 | 409 CLOCK_REQUEST_MISMATCH／RECORD_CHANGED；事件筆數不變，新組仍工作中 | `replayOutAndRejectChangedContentOrAction/staleOutCannotCloseNewSession` |
| M05 | 兩個 HTTP 同鍵上班；另測新鍵並行上／下班及打卡與直接補登未完成競爭；核准與下班競爭 | 同鍵兩個回應相同且只有一筆；新鍵最多一成功；跨路徑恰 200＋409；資料與版本一致 | `sameKeyConcurrentCreatesOnlyOneEvent/clockAndManualOpenSessionCompeteUnderSameLock`；`AttendanceConcurrencyIntegrationTest`；`AttendanceCorrectionConcurrencyIntegrationTest` |
| M06 | 兩組之間補登 12～14，再申請 11～13；一般相等時間、微秒、倒退 Clock、台北跨日與昨日未完成 | 相鄰成功、重疊 409；時間倒退 5xx 零寫入；昨日仍 INCOMPLETE，不自動補閉；合計 9 小時 | `supplementGapAdjacentAndOverlapRejected`；`AttendanceIntegrationTest`；`ReviewRound1IntegrationTest`；既有更正驗證 |
| M07 | 先建立兩組，指定第一組 ID＋版本更正 09:30～12，再只作廢第一組；前端多組開啟更正表單 | 第一組 version 遞增；第二組 version 不變；原事件 4 筆不變；今日 COMPLETED、合計 4 小時；表單須明選目標或新補登 | `correctionOldIdAndVoidOnlyOneSession`；multiple-attendance；attendance-corrections |
| M08 | 已有後續有效時段，嘗試撤銷前組下班 | 409；不能恢復會重疊的未完成時段，不改其他組 | 多組規則＋`laterSessionMakesRevokeApprovalStaleAndRollsBack`；既有撤銷下班測試 |
| M09 | 先送第一組更正為未完成的申請，再開始後續組，最後核准 | 409 REQUEST_STALE；申請仍 PENDING；出勤、歷程不變 | `laterSessionMakesRevokeApprovalStaleAndRollsBack` |
| M10 | 完成原組後開啟定位；新上班無定位被拒，重送舊上班仍確認原操作；注入事件／歷程／去重寫入後故障 | 拒絕零寫入；重送不另定位、不增事件；故障全交易回滾，原 UUID 可重試成功 | `everyNewSessionChecksCurrentPolicyButReplayKeepsOriginalEvidence`；`MultipleAttendanceRollbackIntegrationTest`；既有 ClockEvent／歷程回滾整合測試；geofence |
| M11 | 未登入、缺 CSRF、停用、角色變更、另一員工目標、自改／自審 | 依既有契約 401／403／404／409，資料隔離與禁止規則保留 | 全套 Security／Organization／AttendanceAdmin／Request 測試；organization；attendance-corrections |
| M12 | 切頁、定位延遲、舊 GET 晚到、POST 回應遺失，以及成功 JSON／本文讀取／結構／requestId 不可用 | 過期操作不新送出；最新查詢勝出；未知結果保存原動作及本文，停用新打卡；手動重送鍵與本文完全相同，僅一份資料 | attendance；geofence-races；multiple-attendance |
| B01 | 真實工時 POST 成功建立後，受控製造 JSON 解析及讀取 TypeError | 保持 unknown、原欄位及 requestId 鎖定，後端僅一筆；重試同本文後確認 duplicate | timesheets-success-body 的 json／read |
| B02 | 真實工時成功回應缺必要結構，或 minutes 與原提交不符 | 同 B01；不得按修改其他紀錄卸載原表單 | timesheets-success-body 的 structure／mismatch |
| B03 | 未確認工時重試收到 4xx、清單失敗、專案全停用、401、切頁、點放棄 | 重試被拒仍鎖原快照；首次明確拒絕可修正；放棄需警告確認；卸載後不更新新頁 | timesheets-review；timesheets-retry-boundaries；timesheets |
| S01 | 隔離 schema 建立 V7，每個既有模組表填代表資料，套 V8 | 14 表筆數及逐列內容相同、所有外鍵保留、新去重表空 | MultipleAttendanceSchemaTest；migration-evidence |
| U01 | Puppeteer 390×844 視窗；今日多組列表、表單 | 無橫向溢出，截圖文字與時段可讀 | multiple-attendance；`e2e/output/multiple-attendance-mobile.png` |

## 舊缺陷重現與證據界線

先用原基準程式跑工時缺陷腳本得到 **11／17**（`bug-tp-001-before.log`），JSON／讀取／結構／本文不符的 unknown 鎖定失敗。修補後同批為 **17／17**（`bug-tp-001-after.log`）；再補「不能按修改其他紀錄丟掉追蹤」四項，本輪最終為 **21／21**。原始 11／17 與最後 21／21 不是完全相同項目數，不能直接當同一分母比較。

故障注入使用瀏覽器中受控 `Response.json()`／本文替身；登入、POST、後端建立、去重與資料查詢是真的。這證明成功回應不可用時的 UI 邏輯，未實測網路設備故障。後端 rollback 使用 Spy 在真實 SQL 寫入後拋錯，驗證真 PostgreSQL 的交易效果。

人工檢視已讀程式差異及手機模擬截圖。未安排真人逐案操作、手機實機、正式環境、效能／鎖等待負載或故障設備驗證；不宣稱全部規格均已人工驗收。本輪沒有重新做變異測試，也沒有跑 smoke／logout／password E2E；共用 HTTP、認證、登入密碼與請假實作未修改，相關後端全套及指定回歸已涵蓋本輪影響。

T05 R1～R3 通過 ChatGPT 程式複審與 80 項受控函式檢查是使用者提供的沿用證據；本輪 leave／leave-review 為作者重新跑的瀏覽器證據。兩者都不表示全部 138 案人工完成。

初期後端測試曾因每日單筆預期、exact version 競態與 schema creation 的 null 版本列而失敗；已修正過時測試契約及證據查詢，保留並行／權限／版本／回滾驗收。本頁採最終成功結果，早期 log 留在 target，沒有刪除或改稱通過。
