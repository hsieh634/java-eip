# TeamPortal 共用開發交接

更新：2026-10-07，Codex。目錄 `C:\Users\10739\java-eip`，repository `hsieh634/java-eip`，分支 `main`。

## 基準與完成版本

接手時 HEAD、origin/main 與實際遠端 main 均為 `af596813c4a8c1d89afcedce1d14cc0ee0db5508`。工作區乾淨，無暫存、未暫存或未追蹤成果；搜尋未發現 repo AGENTS.md／CLAUDE.md、t03-3 或舊交接。已閱讀 README、工程方法、roadmap、產品方向、T03.1／T03.2／T04 規格及相關程式；本輪依使用者提供的 AGENTS 指示與附件執行。

完成實作 commit：`IMPLEMENTATION_SHA_PENDING`。其後只有補實作 SHA 的交接／版本紀錄 commit；目前完整交付 SHA 請執行 `git rev-parse HEAD`，遠端用 `git ls-remote origin refs/heads/main` 核對。commit 不寫入自身無法預先得知的 SHA；工具最終回報提供完整交付 SHA。

所有修改來自本輪 Codex，沒有覆蓋 Claude 或使用者未提交成果。修改檔案完整列表見 [changed-files](t03-3/changed-files.md)，目前未提交成果以 `git status --short` 為準，勿只依文件推測。

## 完成與尚缺

| 工作 | 已完成 | 尚缺 | 接續動作 |
|---|---|---|---|
| BUG-TP-001 | 舊版重現；成功 JSON／讀取／結構／本文不可用保留原快照；防止切換修改卸載追蹤 | ChatGPT 複審 | 閱讀逐案證據與差異 |
| T03.3 | 多組、合計、UUID 去重、ID＋版本、共同鎖、交易、多組更正與作廢、定位相容、未知結果手動確認 | ChatGPT 複審、真人／實機／負載驗證 | 以實作 SHA 審查，回饋另開最小修正 |
| Migration | V8 與隔離升級逐表比較、乾淨庫測試 | 開發庫尚未升級 | 使用者依既有備份與重啟流程套 V8 |
| 文件 | 規格、tasks、作者 Review／Archive、操作教材、證據 | 複審後更新結案狀態 | 不自行宣稱通過 ChatGPT |
| T05 | 沿用使用者提供 R1～R3 複審與 80 項受控檢查；本輪重跑請假 E2E | 不代表全部 138 案人工完成 | 不重做已通過的請假修正 |
| T05.1／.2／.3 | 只更新已確認方向 | 全部未實作 | 分批另行授權需求與規格 |

本輪授權只有 BUG-TP-001、T03.3、既有出勤相容、規格／驗證／教材／交接，及驗證與敏感檢查後一般 commit／push main。不 force push、不部署、不改工具 OAuth 或產品認證密碼。不提前實作假單修改／銷假、通用簽核、拖拉設計器、薪資、排班、跨日班、T06。

## Migration 與不可覆蓋資料

V1～V7 原檔不改。V8 移除每日有效出勤唯一索引，改為每日有效未完成唯一索引；新去重表 attendance_clock_submission 保存員工＋UUID、動作、本文指紋、出勤外鍵及原成功回應。舊 ID、時間、微秒精度、版本、來源、作廢、所有申請／歷程／原事件與其他模組資料保留。

本輪最後唯讀查證：開發 catalog `renewflow` 為 V7，七筆 Flyway history success=true。未重啟既有 8080 服務，沒有套 V8 至開發庫。隔離 `renewflow_test` 已套 V8；在實際 catalog allowlist 保護下執行，專用 schema migration_probe_v8 做 V7→V8、14 表筆數與逐列 JSON MD5 一致，測試後只清理該 schema。證據見 [migration-evidence](t03-3/migration-evidence.md)。V8 首次套測試庫後內容不再修改；若需修正追加 V9，不能 repair／clean 開發庫或改已套 migration。

## 驗證與 log

本輪後端全套 322／322（失敗 0、錯誤 0、略過 0），build／lint exit 0；最終僅調整註解與測試顯示名稱，相關三類別 30／30 再通過。12 支指定 E2E 全部 exit 0、484 個檢查點通過。詳細逐案前置／資料／步驟／副作用／結果與限制見 [verification](t03-3/verification.md)。

- 後端：backend/target/t03-3-backend-final.log、t03-3-label-verification.log、t03-3-migration-evidence.txt。
- E2E：frontend/e2e/output/t03-3-<script>.log；build／lint 為 t03-3-build-final.log、t03-3-lint-final.log。
- 工時基準缺陷：bug-tp-001-before.log 11／17；修補同批 after 17／17；最後 t03-3-timesheets-success-body.log 21／21（新增四項防丟快照）。
- 手機模擬：multiple-attendance-mobile.png，390×844，無橫向溢出，已讀截圖。

log／執行產物被 Git 忽略，只提交摘要與合成資料雜湊；不提交 Session／CSRF 或秘密。成功本文故障是受控 Response 替身，但真實 POST、後端建立與查詢是真的；不稱為網路設備故障實測。手機模擬不代表實機；未跑真人全案例、正式環境、負載、全138案或本輪變異驗證。未改共用 HTTP／認證，smoke／logout／password E2E 本輪未跑，影響由全套後端與指定回歸驗證。

## 服務、資源與操作

最後查證：renewflow-db（既有 postgres:17-alpine）healthy；原開發後端 8080 PID 41304 未動，5173 沒有監聽；本輪 E2E 建立的 8081／5174 已停止，沒有殘留監聽。沒有操作其他容器，沒有 down -v，沒有新增工具、套件、映像檔、外部服務或改 .env。

開發服務尚未載入新版本。使用者要操作新版時，在原後端終端正常停止既有服務，依 README 既有 dev 啟動方式重啟（會套 V8，保留原資料）；再於 frontend 執行 `npm run dev`。本輪不擅自終止 PID 41304。不要為測試清開發庫；JUnit 與 E2E 勿同時跑，避免互相清隔離資料。

今日上班→下班→再次上班會保留各組；更正先選 ID 或新補登，今日作廢只針對所選組；有後續有效組不可撤銷前組下班。未知打卡用「重送原打卡確認」，不換操作／鍵／本文；未知工時用「重新送出原內容」，只有確認或警告後明確放棄才能解除。同頁記憶體追蹤不跨重新整理持久化；重新進頁先看真實資料，沒有自動重送。每天最多一筆待審出勤更正仍保留。教材見 [learning](../learning/T03-3-multiple-attendance-and-submit-results.md)。

下一步：ChatGPT 以完成實作 SHA 核對規格及程式；通過後才更新結案。後續假單修改／退回重送、核准變更／銷假與有限簽核設定另立 T05.1～T05.3 規格，保留原核准內容直到變更核准、不自動改既有主管／指定設定，設定發布權與審核權分開。本輪沒有實作。
