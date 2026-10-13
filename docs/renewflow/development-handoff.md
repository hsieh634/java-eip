# TeamPortal 共用開發交接

## 2026-10-13 最新：T05.1 第1輪 R1／R2＋STMAX 客戶 Demo v1

接手基準`47f7fdfec33513bde50396e2864575f4c3f6e054`，初始main／origin／遠端一致、工作區乾淨。使用者帶回ChatGPT34項受控前端通過及R1／R2問題；本輪作者修正完成，**等待ChatGPT複審，真人與客戶Demo待Max；T05.1未結案**。實作與作者驗證版本`233e6bb7184569d7a69e305c3b48856a045c7257`已一般推送main；其後僅補本交接SHA／完成標記，完整交付HEAD見最終回報／Git。

R1審核入口包含ADMIN只看所選及必要前序，document／revision／history／快照／出勤同範圍，介面明示歷史基礎；本人／ADMIN管理完整。R2撤回列ID、操作、時段、結案及原有效保留，未改交易。無新migration，V1～V9不改；無套件／映像／常駐服務或共用HTTP／認證／useLoad修改。

作者後端349／349、五支E2E252項、R2函式基準0／5→修正5／5、build／lint通過；R1真核心舊4案中3失敗→修正4通過。來源與限制：[規格](t05-1/review-round-1.md)、[verification](t05-1/review-round-1-verification.md)、[學習](../learning/T05-1-review-scope-and-customer-demo.md)。

本機已啟用：http://localhost:5173／後端8080／renewflow V9，seed/reset啟動時停用，另有8081保留。D＝2026-10-13，demo_max／iris／leo／mia／ryan／zoe，共六員工、三專案、10工時1560分、8MANUAL、10業務假單17提交。[客戶指南](demo-stmax-customer.md)有實際ID及10～15分鐘流程。用編輯器讀本機.env的TEAMPORTAL_DEMO_PASSWORD，獨立變數、無預設；其他設定不變，不將值貼到聊天或Git。

載入前V9備份／新庫還原16表相同；載入後原16表舊列相同；兩次重跑各新增0且全部16表相同，六帳號登入／GET後亦相同。備份／證據在`C:/Users/10739/.codex/local-backups/java-eip/t051r1_20261013_673957f8/`；manifest在`C:/Users/10739/.codex/local-state/java-eip/stmax-customer-v1.json`，保留以續跑，不恢復人工資料。核心、新Demo、還原測試庫及容器暫存archive已清除；本機備份與8080／5173保留。

本輪一般commit／push main依附件明確授權，不force push、不部署、不進下一階段。以下歷史狀態矛盾時以上方最新區段為準。

更新：2026-10-13，Codex；以下最新狀態優先，後面的2026-10-07／前批章節保留歷史。

## 最新接續：T03.3 本機啟用＋T05.1

基準HEAD／origin/main／遠端main均72ba3265037a7cd09c49f3f018b0ee408170f87e，接手時工作區乾淨；前輪R1實作eb5d6840ca5793e06dfc4e2782fdba20bd4757c4不重做。本輪使用者新附件已授權規格、實作、測試、資料庫備份／升級、本機服務啟停與一般commit／push main。沒有force push或部署。

本輪實作commit：`1ea6c625894d4645e07e5cafd70ee454d6956eb3`，一般push後已與遠端main核對一致；其後只補本段SHA與交付勾選，沒有改程式。完整交付HEAD以`git rev-parse HEAD`及`git ls-remote origin refs/heads/main`核對，最終回報提供實際值。

T03.3 R1收到使用者提供的ChatGPT程式複審通過；實際表單邏輯受控20項基準11／20→修正20／20，BUG-TP-001前輪通過。不是ChatGPT重跑完整後端／E2E；前輪作者29項是另一份證據。真人仍未完成。

| 工作 | 本輪實際狀態 | 下一步 |
|---|---|---|
| T03.3 R1與BUG | 使用者帶回複審通過；本機已載入 | Max執行M33真人案例 |
| T05.1規格／實作 | 獨立提交版本、待審修改／退回重送、核准後變更、整段／單側部分銷假、出勤快照完成 | ChatGPT複審 |
| 作者驗證 | 後端345，build／lint，11支E2E共435，5個變異失敗且還原通過 | 詳見[verification](t05-1/verification.md) |
| 本機備份／升級 | 三份custom本機備份／新庫還原確認；V8→V9十五張舊表完全相同 | 保留備份，勿clean／repair或改V1～V9 |
| 網站 | 8080 PID7260、5173 PID516正在執行；三帳號認證與新版GET，業務寫入0 | http://localhost:5173 |
| 真人驗收 | M33／M51全部未執行 | 由Max依[教材](t05-1/manual-verification.md)親自操作 |
| 簽核設定／設計器 | 流程發布、職稱解析、流程版本、拖拉、分支、代理未實作 | 另立規格、另行授權 |

目前有效內容唯一來源leave_document.effective_request_id，effective_version在核准才+1；歷史APPROVED保留，不能全相加。待審變更預留新區間並保留原有效占用，銷假不提前釋放；單一pending、來源與有效快照、同員工UUID先確認，鎖／交易／歷程及出勤摘要一起成功或回滾。出勤Service只讀相關日期、不含GPS，不自動核准。詳細API／資料／安全見[t05-1/design](t05-1/design.md)、[api](t05-1/api.md)，學習見[T05.1](../learning/T05-1-leave-revisions-and-cancellation.md)。

最後備份：`C:/Users/10739/.codex/local-backups/java-eip/20261013_final_9521edcd/development-v8.dump`；還原、升級與登入後舊欄位比較皆通過，初始化2業務單、錯誤0。起初實查已V8，沒有重套V8或退版。啟動CLI停用seed／reset／STMAX初始化，.env與帳號密碼／角色／主管／指定設定未改。Leo→Iris，Iris→Max；Max無主管或指定審核人，沒有替Max設定Iris。密碼從.env的RENEWFLOW_DEMO_STMAX_PASSWORD取得，文件不列值。

已正常Ctrl+C停止起初已辨識的本專案8080／5173控制台，新啟動helper49140／48256隱藏執行；只保留開發新版。其他專案8081 PID35604未動。隔離8082／5175已停止，本輪專用測試庫已確認catalog及無連線後清除；三個container /tmp備份檔已清理，三份本機備份及非敏感證據保留。沒有操作共用renewflow_test、其他容器或down -v。

執行證據：backend/target/t05-1-backend-final.log、t05-1-mutations.json／migration-evidence.txt；frontend/e2e/output/t05-1-*.log及mobile PNG。僅提交摘要與合成測試，沒有.env／密碼／Session／CSRF／備份／執行log／截圖／建置產物。沒有新增工具、套件、映像、外部服務或費用。

T05.1 作者自測完成，等待 ChatGPT 複審；真人驗收依實際結果記錄。可設定簽核流程與拖拉設計器尚未實作。停止在本批，不自動開始後續階段。

## 以下為前輪歷史交接


更新：2026-10-07，Codex。目錄 `C:\Users\10739\java-eip`，repository `hsieh634/java-eip`，分支 `main`。

## 最新接續：T03.3 第 1 輪 R1

本輪基準 HEAD／origin/main／遠端 main 查證皆 `d3d2d52761f9cb8e958828022d6b2f67d633ef5f`，工作區乾淨，原目標失效 fallback 尚未修正；依使用者 AGENTS 指示及 R1 附件執行。修正實作 commit：`eb5d6840ca5793e06dfc4e2782fdba20bd4757c4`；其後只補交接 SHA。完整交付 HEAD 用 git rev-parse HEAD，遠端用 git ls-remote origin refs/heads/main 核對，最終回報提供實際 SHA。

已完成 CorrectionForm 局部目標 union、失效待選／原 ID 提示、明選目標或補登、日期切換清空與過期回應保護。通用入口及微秒保留正常。沒有改 AttendancePage（原本傳 ID 正確）、後端、API、migration、共用 HTTP／認證／元件或相依套件。

| 工作 | 本輪狀態 | 接續 |
|---|---|---|
| R1 修正與逐案測試 | 完成：同支腳本基準 21／29 → 修補 29／29 | ChatGPT 複審 |
| build／lint 與既有回歸 | 完成：多組 17、更正審核 74、基本出勤 37，全部通過 | 不需重跑未影響模組 |
| 規格、教材、作者 Review／Archive | 完成，[review-round-1](t03-3/review-round-1.md) 記錄完整證據 | 真人驗收尚未完成 |
| 一般 commit／push main | 按本輪附件授權，最終 Git 紀錄核對 | 不 force push、不部署 |
| T03.3／T05.1 | T03.3 等待複審，未結案；T05.1 尚未開始 | 停在本輪交付點 |

本輪 log：frontend/e2e/output/t03-3-r1-before.log、after.log、build.log、lint.log、multiple-attendance.log、corrections.log、attendance.log（相同 t03-3-r1- 前綴）；受控畫面 r1-target-invalid.png 已檢視。R1 用真表單、POST、隔離 PostgreSQL；過期清單／延遲 GET 為受控，不能當真人驗收。先核對 actual catalog guard，唯讀資料比較只限本輪新員工。腳本完整原版／修補的 SHA-256 相同，細節見複審文件。

前批後端 322／30、migration 14 表與其他模組 E2E 本輪沿用。沒有重跑後端全套，沒有新 migration、沒有套 V8 至開發庫，沒有操作 Max／STMAX 或改定位／示範組織。原 8080 PID 41304 保留、5173 未啟動、既有 renewflow-db healthy，開發庫仍 V7；隔離 8081／5174 按腳本 finally 停止本次 PID，專用申請撤回，測試資料與歷程保留供追溯。不清開發庫、不 down -v。

真人驗收前必須由使用者依 README 備份／重啟新版後端套 V8，再啟動新版前端；目前舊 8080／V7 尚不能當新版驗收。操作：兩組中保留過期列表、另由管理員作廢第一組，從舊列打開更正，應維持待選與原 ID 提示；明選第二組或新補登後才送出；切日期也需重新明選。教材：[T03.3 R1](../learning/T03-3-target-selection-review-fix.md)。下一步只做 ChatGPT 複審／必要回饋，不自行進入 T05.1。

## 前批實作交接（保留歷史，以下「本輪」指前批）

## 基準與完成版本

接手時 HEAD、origin/main 與實際遠端 main 均為 `af596813c4a8c1d89afcedce1d14cc0ee0db5508`。工作區乾淨，無暫存、未暫存或未追蹤成果；搜尋未發現 repo AGENTS.md／CLAUDE.md、t03-3 或舊交接。已閱讀 README、工程方法、roadmap、產品方向、T03.1／T03.2／T04 規格及相關程式；本輪依使用者提供的 AGENTS 指示與附件執行。

完成實作 commit：`db5125b9785471aab9ccced0d2eebd2c8fa87f6e`。其後只有補實作 SHA 的交接／版本紀錄 commit；目前完整交付 SHA 請執行 `git rev-parse HEAD`，遠端用 `git ls-remote origin refs/heads/main` 核對。commit 不寫入自身無法預先得知的 SHA；工具最終回報提供完整交付 SHA。

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
