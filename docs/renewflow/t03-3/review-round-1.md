# T03.3 第 1 輪複審修正 R1

日期：2026-10-07；基準 `d3d2d52761f9cb8e958828022d6b2f67d633ef5f`。本輪只修正更正表單的目標選擇，尚未結案；不開始 T05.1。接手時 HEAD／origin/main／遠端一致，工作區乾淨，R1 尚未修正。

## Requirement → Domain Analysis

每列入口代表「更正指定 ID」，通用入口代表「選擇更正或補登」。重新取得最新資料不得改變原本的指定目標。原指定 ID 已作廢或查不到時，顯示原 ID 與中文失效提示，維持待選、阻止送出；使用者才能明選另一組或補登。

原程式 `find(initialRecordId) ?? 唯一有效組` 把「原目標失效」誤解為「可採用預設」。沒有有效組時又自動選 new。後端只看到新的合法 ID＋版本，因此無法辨識前端把使用者意圖換掉。

## Architecture／Data Model／API／Security／Proposal

維持 AttendancePage → CorrectionForm → submitRequest 的既有呼叫與 API。AttendancePage 已傳 initialDate＋initialRecordId，無需修改。後端、DTO、資料模型、V1～V8、權限、共同鎖、重疊／每日待審限制及原事件保留，沿用 T03.3 design.md 與既有整合測試。本輪不改共用 HTTP、認證、時間工具或相依套件。

以表單內局部 union 表示待選／新補登／指定紀錄；最新紀錄快照與選擇放在同一 ready 狀態。指定入口只能預填查到的同 ID；失效時不 fallback。這個小修正不需要新的共用框架。

## Design

| 入口／事件 | 查詢後選擇 | 送出 |
|---|---|---|
| 指定 ID，仍有效 | 該 ID 最新紀錄 | 該 ID＋最新版本 |
| 指定 ID，失效 | 待選，保留原 ID 提示 | 按鈕及 handler 都阻止 |
| 使用者明選有效 ID | 所選紀錄；預填其時間 | 所選 ID＋版本 |
| 使用者明選 new | 新補登；清空原與申請時間 | ID／版本皆 null，沿用既有契約 |
| 指定入口切日期（含切回原日期） | 清空舊快照及時間；查新日期後仍待選 | 必須明選新日期目標或補登 |
| 通用入口／通用入口切日期 | 無有效組預設 new；一組預填；多組待選 | 保留既有正常流程 |

日期切換立即清空原快照、時間與錯誤；沿用 effect cleanup 的 active 旗標，過期成功／失敗不採用。未修改的預填值仍用 submittedTime 保留完整小數秒。取得快照後再變動，交給後端 RECORD_CHANGED；不自動改選。

補登提示區分「確實無有效紀錄」和「另有其他組，正在補登缺少時段」。待選不能顯示成沒有紀錄。

## Specification：逐案驗收

隔離 E2E 建立專用員工 A／C 與 ADMIN。#11／#22 是案例代稱，實際用資料庫產生的 ID；A 今日兩組 00:10～00:20（含微秒）、00:30～00:40，另有昨日組；C 今日一組。使用受控舊清單重現已過時的列入口，但表單查詢、提交、資料比較使用真隔離後端與 PostgreSQL。

| 案例 | 操作與預期 | 資料副作用／檢查 |
|---|---|---|
| R1-01 | 選已作廢 #11、還有 #22；顯示 #11 失效，維持待選 | 強制觸發 submit 仍無 POST，無新申請 |
| R1-02 | 指定組失效且無有效組；維持待選 | 不自動 new；未明選仍無 POST／申請 |
| R1-03 | R1-01 後明選 #22 | 原與申請時間更新；POST／資料庫目標為 #22＋版本，其他組與事件不變 |
| R1-04 | 原指定失效後明選 new | 清空時間；POST 目標皆 null，資料庫 target 為 null；不修改原組 |
| R1-05 | #11 仍有效、同日有其他組 | 預填／POST／資料庫維持 #11，未改時間保留微秒 |
| R1-06 | 取得快照後管理員更正／作廢目標 | POST 舊 ID＋版本遭 409，無新申請／成功歷程，其他組及原事件不變 |
| R1-07 | 通用入口一組更正、無組補登 | 正常 POST／申請可完成，原組在核准前不變 |
| R1-08 | 指定入口先切昨日，再切前日，延遲昨日 GET 晚到 | 只保留最新日期，待選且舊時間清空；明選 new 後送新日期、無舊目標 |

## Tasks／Implementation／Automated Tests

- [x] 版本、環境與相關文件／程式盤點。
- [x] 本輪需求、設計、逐案規格。
- [x] 寫 R1 腳本，以未修改基準重現失敗。
- [x] 修正局部目標狀態與日期切換。
- [x] 相同核心檢查修補後通過；build／lint、multiple-attendance／corrections／attendance。
- [x] 教材、證據、交接與作者 Review／Archive 完成；安全檢查及一般 commit／push／遠端核對見最終 Git 紀錄。

實際結果與版本見下方本輪證據。

## Manual Verification／Review／Archive

已讀取程式差異、檢視 r1-target-invalid.png 受控畫面與測試紀錄。受控清單／延遲回應不能當真人或真實網路故障；未規劃實機／正式環境／負載。沿用前批後端 322 與 migration 14 表證據，本輪不重跑後端全套。開發庫唯讀查證仍 V7；8080 PID 41304 未動、5173／8081／5174 未監聽，既有 db healthy。本輪只用既有隔離堆疊；不套 V8 至開發庫、不修改真實使用者或示範組織。


## 本輪實際證據

修正完成實作 SHA：`eb5d6840ca5793e06dfc4e2782fdba20bd4757c4`；其後僅補交接 SHA 的文件提交。R1 表單 blob：`203131fcf58224a6891297d5567fa9c61b548a60`。基準表單 blob `f95ce7c041f9380847a8453d9e90957d3d7d42a5` 與修正前工作區 hash 相同；先在原程式完整跑腳本才修改表單，沒有 reset／checkout 還原。腳本修正前後 SHA-256 均為 `bac87542b30483ceaf5c5f0c51a2e00570ef81b27d6b38c4bfa7b24c8f2b2ffa`。

| 執行版本／命令（工作目錄 frontend） | 實際結果 | log |
|---|---|---|
| 基準：`node e2e/attendance-target-review.mjs` | 21／29，exit 1；八項缺陷斷言失敗，腳本完整結束 | e2e/output/t03-3-r1-before.log |
| 修補：`npm run e2e:attendance-target-review` | 29／29，exit 0 | e2e/output/t03-3-r1-after.log |
| `npm run build` | 通過，exit 0 | e2e/output/t03-3-r1-build.log |
| `npm run lint` | 通過，exit 0 | e2e/output/t03-3-r1-lint.log |
| `npm run e2e:multiple-attendance` | 17／17，exit 0 | e2e/output/t03-3-r1-multiple-attendance.log |
| `npm run e2e:corrections` | 74／74，exit 0 | e2e/output/t03-3-r1-corrections.log |
| `npm run e2e:attendance` | 37／37，exit 0 | e2e/output/t03-3-r1-attendance.log |

本次四支 E2E 共 157 個檢查點，涵蓋八個 R1 案例與既有回歸；檢查點不是獨立需求數。測試前先核對現況：原 8080 PID 41304、開發庫 V7、既有 db healthy；沒有重啟或升級開發環境。隔離堆疊使用現有 e2e profile 的實際 catalog guard；新腳本只唯讀 renewflow_test，查詢結果再次檢查實際 catalog，資料比較限新建專用員工。

| 案例 | 基準實際 | 修補實際／證據 |
|---|---|---|
| R1-01 | 待選／提示／停用失敗，強制 submit 實際新增申請 | 三項全通過：待選、原 ID 失效提示、零 POST／完整資料無差異 |
| R1-02 | 自動變成補登；三項失敗且新增申請 | 三項全通過：無有效組仍待選、零 POST／完整資料無差異 |
| R1-03 | 明選另一組正常 | 明選更新時間；POST 與 DB target ID／version 都正確，其他組／事件不變 |
| R1-04 | 明選補登時誤稱當日無紀錄；其餘原功能正常 | 提示正確、清空時間；有／無其他組的補登皆 target null，原組／事件不變 |
| R1-05 | 指定有效組正常 | 維持原 ID／version、時間；未改上班值送六位小數秒，DB 目標正確 |
| R1-06 | 過期更正與作廢都被後端拒絕 | 沿用後端契約，真 POST 409、申請／歷程／出勤／事件完整比較不變 |
| R1-07 | 通用入口正常 | 正常更正與補登、POST／DB target 正確，核准前原資料不變 |
| R1-08 | 新日期自動 new，待選斷言失敗 | 指定入口新日期待選；舊回應丟棄、時間清空；明選補登送新日期與 null 目標 |

測試腳本前兩次試跑曾因錯讀錯誤 JSON 層級及 StrictMode 兩次列表載入而提前結束；已修正測試準備，之後才取得上列完整 21／29。試跑中斷不是 R1 缺陷證據。最終原版與修補版使用完全同一支腳本。

## 作者 Review 與 Archive 結果

已核對選擇狀態與提交快照同源、指定 ID 不 fallback、待選的按鈕及 handler 防線、明選補登提示、日期切換清空與 active 過期保護、微秒與既有版本拒絕。截圖為受控舊清單情境，畫面刻意保留過期列，但表單重新查真後端得失效提示；已閱讀，沒有稱為真人驗收。

本輪新證據為上述命令；前批後端 322、追加 30、V7→V8 14 表相同及其他模組 E2E 是沿用，沒有本輪重跑。未改後端、API／DTO、migration、共用元件／HTTP／認證或相依套件；package.json 只新增腳本 alias 與完整 E2E 串列。T03.3 未結案，T05.1 未開始。

尚未驗證：真人、實機、正式環境、真實網路設備故障與負載。沒有替 Max／STMAX 操作；測試後僅停止本次堆疊 PID，專用申請已撤回，測試資料與歷程保留供追溯，不清開發庫。log／截圖／build 產物被忽略，不進 Git。

修改檔案：CorrectionForm.tsx、attendance-target-review.mjs、frontend/package.json、README.md、review-round-1.md、specification.md、tasks.md、verification.md、review-and-archive.md、version-log.md、development-handoff.md、T03-3-target-selection-review-fix.md。

真人驗收前置：由使用者依 README 保留資料／備份並正常重啟新版後端套 V8，啟動新版前端；目前開發庫 V7、8080 舊程式，不宣稱新版網站已可驗收。以專用驗收資料建立兩組，保留舊員工清單，管理員作廢第一組後從舊列開表單；應標原 ID 失效、不能送出。明選第二組或新補登才可送；換日期後須重新選擇。尚未真正執行這段真人操作。

最後環境核對：renewflow-db healthy；開發 catalog renewflow，V1～V7 success=true；8080 PID 41304 未動，5173／8081／5174 沒有監聽。未操作其他容器。
