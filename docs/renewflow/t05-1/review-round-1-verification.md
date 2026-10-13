# T05.1 第1輪 R1／R2與客戶展示：作者驗證

2026-10-13。接手基準 main／origin／遠端均為 `47f7fdfec33513bde50396e2864575f4c3f6e054`，初始乾淨，前批實作 `1ea6c625894d4645e07e5cafd70ee454d6956eb3`。本批遵循[規格與階段](review-round-1.md)，沒有覆蓋別人的未提交修改。

使用者帶回的 ChatGPT 34項受控前端函式檢查已通過，並指出R1巢狀越權與R2文案矛盾；34不是本輪作者重跑結果。前批345後端、E2E、備份仍屬前批作者證據。**本輪作者自測完成，等待ChatGPT複審；T05.1未結案，真人與客戶Demo待Max。**

## 修正前後及逐案資料副作用

`LeaveReviewScopeIntegrationTest`使用同核心測試與真HTTP／PostgreSQL，先以舊產品程式執行再修正，沒有放寬斷言或重設程式歷史。

| 案例 | 舊版結果 | 修正版結果／完整JSON及資料 |
|---|---|---|
| Bob待審v1→Erin v2 | assertion失敗 | 舊ID不含新pending、版本、秘密原因／歷程；新ID404；Erin合法，本人／管理完整 |
| Bob已核准v1→Erin新日期CHANGE並核准 | assertion失敗 | 舊入口沒有新日出勤、事件、token或新決定快照；歷史基礎v1，Erin必要前序正確 |
| 同一PENDING改派 | 原本通過 | 舊審核人GET與處理POST404；維持既有正確權限 |
| ADMIN曾審核舊版、後續改指派 | assertion失敗 | 審核入口仍限制；ADMIN管理入口FULL |

舊4案：失敗3、錯誤0；修正版4／4。讀取前後比對全部業務表，沒有改歷程／資料，也沒有新增全公司出勤權限。實際組裝限制在LeaveViews而非僅前端隱藏。

R2以 `withdraw-confirm-controlled.mjs`擷取**基準git實際WithdrawAction與目前函式**，五種動作CREATE／REVISE／CHANGE／CANCEL_ALL／CANCEL_PART：基準0／5→修正5／5。此為作者受控函式檢查，不冒稱ChatGPT34或實際交易；另以新E2E真瀏覽器確認ID、操作、日期／時段、待審結案與保留原有效提示，真撤回後讀取確認有效ID／版／時段完全不變。後端撤回交易未修改。

## 最終實際執行

| 驗證 | 結果 | 本機log（不提交） |
|---|---|---|
| R1舊／修正版核心 | 舊3失敗／4，後4／4 | backend/target/t05-1-r1-before.log、t05-1-r1-after.log |
| 完整後端 | **349／349**，failure／error／skip均0，exit0 | backend/target/t05-1-r1-backend-final.log |
| 前端build | exit0 | frontend/e2e/output/t05-1-r1-build-final.log |
| 前端lint | exit0、無警告 | frontend/e2e/output/t05-1-r1-lint-final.log |
| leave-revisions | **52／52** | frontend/e2e/output/t05-1-r1-leave-revisions-final.log |
| leave | **56／56** | frontend/e2e/output/t05-1-r1-leave-final.log |
| leave-review | **46／46** | frontend/e2e/output/t05-1-r1-leave-review-final.log |
| organization | **54／54** | frontend/e2e/output/t05-1-r1-organization-final.log |
| 新leave-revisions-review | **44／44** | frontend/e2e/output/t05-1-r1-leave-revisions-review-final.log |
| R2受控函式 | 舊0／5→後5／5 | frontend/e2e/output/t05-1-r2-controlled.log |

五支E2E共252項。後端以本批新建核心庫 `renewflow_t051r1_20261013_673957f8_test`、actual catalog guard執行；測試後停止隔離服務、查無連線才清除該庫。新E2E每次建立自己的`renewflow_t051r1_demo_<run>_test`，隨機密碼只在記憶體；finally只清除自己成功建立的庫。沒有清空共用renewflow_test／開發庫。

可重跑新E2E（自行建立隔離庫）：

```powershell
cd frontend
$env:E2E_API_PORT='8082'
$env:E2E_VITE_PORT='5175'
npm run e2e:leave-revisions-review
node e2e/withdraw-confirm-controlled.mjs
```

349後端及其他回歸須依既有README建立新的專用*_test庫、設定DB_TEST_NAME；JUnit及E2E不要同時共用資料庫。保留已有8081，不使用它。測試時沒有向開發庫送業務寫入。

修正期間如實記錄：舊leave第一輪55／56因新提示與「不能再恢復」預期不同，修正文字後；第二輪39／40是改派後GET仍載入、關閉按鈕競速，補等待實際新審核人再點擊，最終56／56。展示唯讀smoke第一次因同hash路由未等工時查詢載入失敗，補明確範圍與等待後六帳號通過。最後清理測試腳本lint警告後，首次重跑忘設定埠，被既有8081防護拒絕（沒有接管／停它）；改8082／5175後44／44。未把失敗紀錄當成功證據。

## 展示初始化驗證

| 情境 | 實際驗證／副作用 |
|---|---|
| 預設關閉、非dev、無密碼 | 不建立，不輸出秘密；本機程式／profile／catalog核對後才准寫 |
| 帳號／員編／專案碰撞 | 以本批新庫預先放衝突fixture，初始化停止；整批業務資料不變，不接管 |
| 中途201後失去確認 | 員工API真寫入後故障；manifest保持PLANNED；續跑查稽核標記確認同ID，其他步驟補齊、無重複員工 |
| 首次完整資料 | 六帳號正確角色／員編／主管；三專案；10工時1560分；8MANUAL、展示CLOCK／GPS0；10單17提交、6pending、5effective與完整歷程 |
| 同批重跑 | 所有16表筆數／摘要相同，D固定，無新審核或內容重設 |
| 人工調整角色／主管／假單／專案說明 | 保留狀態與名稱，零寫入；完成案例摘要變動只回報略過 |
| 秘密 | manifest／結果不含展示或bootstrap密碼；工具不印原HTTP／程序stderr秘密 |

開發載入前唯讀盤點帳號、部門、專案及舊16表，確認無碰撞後再啟用。首次native CLI實際54項API建立／決定；第二次與第三次新增0、略過37（六帳號、三專案、八補登、十工時、十完整案例），不是37筆新資料。D＝2026-10-13；實際IDs、來源版本與歷史見[客戶指南](../demo-stmax-customer.md)。

## 備份、資料保留及執行環境

外部證據資料夾：`C:/Users/10739/.codex/local-backups/java-eip/t051r1_20261013_673957f8/`。

- `development-v9-before-demo.dump`：pg_export_snapshot一致快照＋pg_dump custom二進位檔；容器與本機SHA256相同：`4f2d6c7fa86ef339bb07fcacf9b4f41856ba33071c709225faecb8092a92392b`。
- `restore-evidence.json`：新庫還原、16表筆數與排序整列JSON摘要／PK集合完全相同；還原庫已清除。不是只驗檔案存在。
- `demo-data-evidence.json`：首次後原16表既有PK列摘要相同（包含密碼雜湊、角色、主管、請假、出勤）；第三次重跑全部16表相同；六帳號登入／GET與UI操作後全部16表亦相同。雜湊只比較、不輸出個人列或密碼。
- `demo-first-report.json`／`demo-repeat-report.json`與`customer-inventory.json`：建立／略過與demo假單實際資料；不存秘密。manifest另在`.codex/local-state/java-eip/stmax-customer-v1.json`，不進Git。

| 表 | 載入前 | 載入後 | 原列 |
|---|---:|---:|---|
| employee／department／organization_history | 10／3／9 | 16／3／15 | 相同 |
| attendance／attendance_request／attendance_history | 8／3／6 | 16／3／14 | 相同 |
| geofence_setting／geofence_setting_history | 1／0 | 1／0 | 相同 |
| attendance_clock_event／attendance_clock_submission | 5／1 | 5／1 | 相同，展示新增0 |
| project／timesheet_entry | 0／0 | 3／10 | 相同 |
| leave_request／leave_history／leave_document | 2／4／2 | 19／32／12 | 相同 |
| leave_approver_setting | 0 | 0 | 相同 |

正常Ctrl+C停已識別的舊8080／5173控制台，另有8081 PID35604保留。新8080 PID51644、5173 PID51748；前者dev、兩者均本專案。8080從目前target/classes啟動、5173即時目前原始碼。啟動停用舊dev seed／密碼reset及STMAX seed；Flyway已V9，沒有本輪migration。只追加獨立TEAMPORTAL_DEMO_PASSWORD，其他.env原設定相同。

六帳號API登入確認角色，真瀏覽器工時查10-11～12展示Leo8小時30分、Iris打開#7受限審核基礎；只auth＋GET、業務寫入0。圖片在frontend/e2e/output/customer-demo-timesheets.png與customer-demo-review.png，已檢視。此作者smoke不等於Max真人Demo。

本批核心／每次新Demo／備份還原測試庫已清除；容器內本批備份暫存檔在核對本機SHA後清除，本機備份與manifest保留。8080／5173留給Max操作，未部署、未清開發庫、未刪歷程。

## 作者Review／限制／Archive

已對照需求→領域→架構／資料／API／安全→Proposal／Design→Specification／Tasks→Implementation→Verification→Review／Archive。新增僅本機原生CLI及測試，無相依套件／映像／服務，V1～V9不改，無共用HTTP／認證／useLoad改動。清單與DTO範圍也修正，並維持原writer共同鎖／版本／交易。

待ChatGPT：審查R1可見前序、快照過濾及R2文案；待Max：所有既有真人案例與客戶Demo、真正打卡定位。大資料量／負載、實機、正式環境未驗證；可設定簽核流程／設計器及後續階段未實作。本輪不自行結案或接續新階段。

敏感檢查與一般commit／push結果於交接／交付SHA記錄；執行log、.env、密碼、dump、manifest、圖片與產物均不提交。
