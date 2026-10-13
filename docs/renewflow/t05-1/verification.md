# T05.1 驗證、證據與限制

> 本輪R1／R2及STMAX展示見[review-round-1-verification](review-round-1-verification.md)：349後端、252 E2E、R2受控0／5→5／5與16表保存證據。下方保留前批歷史。T05.1仍待ChatGPT複審，真人與客戶Demo待Max。

日期2026-10-13；基準HEAD／origin/main／遠端main均72ba3265037a7cd09c49f3f018b0ee408170f87e，最初工作區乾淨。前輪R1實作eb5d6840ca5793e06dfc4e2782fdba20bd4757c4不重做。本批執行版本為此基準上的T05.1工作區，最後實作與完整交付SHA見development-handoff／最終Git回報。下列是作者自測，不是ChatGPT或Max執行。

## 隔離與命令

只使用本輪新建renewflow_t051_20261013_51799e4d_test，actual catalog精確核對；8082／5175為隔離服務，既有其他專案8081保留。JUnit及各E2E循序，不同時清同一庫。測試帳號均合成，未操作開發庫Max／STMAX業務資料。

```powershell
$env:DB_TEST_NAME='renewflow_t051_20261013_51799e4d_test'
$env:E2E_API_PORT='8082'
$env:E2E_VITE_PORT='5175'
# backend
.\mvnw.cmd -B test
.\mvnw.cmd -B '-Dtest=LeaveRevisionIntegrationTest' test
# frontend
npm run build
npm run lint
npm run e2e:leave-revisions
```

變異每次只改LeaveRevisionService一處，選定`-Dtest=LeaveRevisionIntegrationTest#方法`。不改V9；原始服務bytes在外部備份保存、finally還原、比SHA-256，再重跑同案。對照與結果列於下方最終證據。

## 每案結果與可重跑證據對照

每案需求、前置、角色／資料、操作步驟、預期畫面／HTTP及資料副作用見[specification](specification.md)同ID；此表補實際測試方法與證據，執行版本同本文件開頭。共同前置：Alice EMPLOYEE→Bob MANAGER、Erin非指派主管、Carol／Dave ADMIN；控制Clock為台北2026-10-01，日期2026-10-05。E2E為當日合成A→B與Admin、隔離真人HTTP／PostgreSQL，受控故障另外標明。

| 案例 | 實際驗證方法／步驟與副作用檢查 | 證據與限制 |
|---|---|---|
| L51-01 | pendingRevisionPreservesContentAndResolvesNewApprover：舊內容與送出審核人保留、新ID、單一PENDING；歷程指向實際提交ID | 新整合＋新E2E，未人工 |
| L51-02 | staleEffectiveVersionAndUnauthorizedReviewHaveNoSideEffects／modificationAndApprovalRaceNeverApproveOldContentOntoNewRevision；新E2E舊版核准409 | 完整表快照＋並行真HTTP |
| L51-03 | rejectedResubmissionKeepsReasonAndRejectsOtherSource、pendingRevisionPreservesContentAndResolvesNewApprover；新E2E退回重送 | 原REJECTED／reason不改，新指派重新解析 |
| L51-04 | invalidOrNoApproverModificationKeepsOldPending、submissionAuditFailureRollsBackReplacementAndDedup | 400／409／500及完整請假／文件／歷程／出勤快照一致 |
| L51-05 | approvedChangeAppliesOnlyAfterApprovalAndDoesNotDoubleCount；新E2E變更09:30～14:00 | 待審舊有效不變、核准才切指標、合計一次 |
| L51-06 | withdrawOrRejectChangePreservesEffectiveAndReleasesOnlyReservation | 原有效不變，新預留釋放，歷史保留 |
| L51-07／11 | fullCancellationKeepsOriginalAndOnlyReleasesAfterApproval；新E2E全文退回重送核准 | 待審仍占用；核准effective=null，canceled=true，正分鐘舊內容保留 |
| L51-08／09 | partialCancellationRequiresOneEdgeTrueSubsetAndSupportsPastDates；新E2E單側保留 | 開始／結束縮短、非法同範圍／增加／兩側／換日拒絕、零副作用 |
| L51-10 | changeOverlapExcludesSelfButChecksOtherPendingAndEffectiveDocuments | 自身排除、其他有效／待審衝突、相鄰成功；核准再檢查 |
| L51-12／13 | sameKeyConcurrentAndChangeVersusCancellationCreateOneWorkflow、modificationAndApprovalRaceNeverApproveOldContentOntoNewRevision、duplicateApprovalsUpdateEffectiveOnlyOnce | 真並行恰一合法序列／一個結案歷程；非負載驗證 |
| L51-14 | dedupAfterTerminalAndSettingsChangesKeepsOriginalWorkflow、terminalApprovedAndWithdrawnReplaysDoNotResolveApproverAgain | 退回／核准／撤回後同鍵同ID確認；異內容／動作／來源409 |
| L51-15 | staleEffectiveVersionAndUnauthorizedReviewHaveNoSideEffects＋來源舊版本案例 | 完整快照不變、條件有效指標更新防線 |
| L51-16 | revisionEndpointRequiresSessionAndCsrfWithoutAnyWrites、revisionWaitingForOrganizationLockRechecksDisabledActor | 401／403及advisory真等待後停用401，無業務寫入；其他本人／非指派／ADMIN越權404 |
| L51-17 | invalidDesignationNeverFallsBackAndOldPendingSurvives、changeApprovalVersusReassignPreservesSubmittedApprover | 指定失效不fallback、原PENDING保存；新流程審核vs改派僅一成功；送出審核人ID不改 |
| L51-18／20 | evidenceTokenAndHistoryAreBoundedAndImmutable；新E2E真兩組四事件、更正／作廢／補登、昨日排除 | 限本人相關日、無GPS／全公司MANAGER權；未下班／無紀錄由整合前置驗證 |
| L51-19 | evidenceTokenAndHistoryAreBoundedAndImmutable；新E2E看詳情→實際更正→舊確認409→刷新核准 | 捕獲當時摘要與有效前後版本，之後更正不改歷程 |
| L51-21 | submissionAuditFailureRollsBackReplacementAndDedup、effectiveSwitchFailureRollsBackApprovalAndHistory、approvalAuditFailureRollsBackEffectiveAndFrozenEvidence | 真SQL／歷程寫入後注入失敗；全部回滾；恢復後可成功 |
| L51-22／23 | leave-revisions.mjs：真201破壞JSON／read／missing／UUID／operation／source／document／content；unknown原本文重送、403／503仍鎖；警告／明確放棄、401、卸載晚到 | 成功Response及拒絕受控；真POST／查詢／資料庫確認；不稱設備網路故障實測 |
| L51-24 | 新E2E錯誤來源GET零POST、手機390；leave-review.mjs同條件重查／晚到回應／空與錯誤重試 | 相同LeaveList／useLoad沿用且本輪回歸；非實機 |
| L51-25 | LeaveRevisionSchemaTest V8→V9代表15表舊欄位投影；乾淨專用庫V1～V9初始化 | 舊APPROVED可繼續變更，FK、immutable、單一pending；實際開發升級另見下表 |
| L51-26 | 五個變異逐一移除關鍵保護，測試必須斷言失敗，再原bytes恢復通過 | 結果見mutation-evidence，未變動migration |

## 新證據與沿用證據

前輪作者322後端、484E2E、R1作者29、ChatGPT20項與BUG審查屬歷史／使用者提供證據。ChatGPT20項基準11／20→修正20／20，不與作者29項合併。本輪完整後端與各腳本實跑結果由最終表列出，不能稱ChatGPT重跑全部整合。

第一次新E2E卡在相同hash網址未重讀清單；修正go reload與狀態等待後21／21。擴充503案例最初等待自訂錯誤文字，但UI對5xx統一顯示結果不明；改以受控503已發生＋unknown狀態等待。初次變異以`.code()`讀201導致測試Error，補HTTP409斷言後是明確Failure。這些未完成嘗試不記通過，也沒有以改產品行為迎合腳本。

## 尚未驗證

Max真人教材M33／M51全部未執行；未跑實機、負載、正式環境、跨日／多日／中段拆單，未完成舊138案人工驗收。只提供本批操作準備。沒有新增套件／工具／映像／外部服務／費用，沒有改共用HTTP／認證／useLoad，沒有自動修改主管或指定設定。

最終命令結果、變異摘要、備份／升級保留與服務PID在完成後更新於以下段落。

## 最終實跑結果

| 命令（對應工作目錄） | 結果 | 本機證據 |
|---|---|---|
| backend：`.\mvnw.cmd -B test` | 345／345，失敗0、錯誤0、略過0，exit0 | backend/target/t05-1-backend-final.log |
| backend：`-Dtest=LeaveRevisionIntegrationTest` | 新整合22／22，後續納入全套345；另V8→9測試1項 | backend/target/t05-1-expanded-final-tests.log／最終全套 |
| frontend：`npm run build`／`npm run lint` | 均exit0 | frontend/e2e/output/t05-1-build-final.log／lint-final.log |
| frontend：`node e2e/leave-revisions.mjs` | 52／52，exit0 | frontend/e2e/output/t05-1-e2e-final.log |
| frontend：`node e2e/leave.mjs` | 56／56，exit0 | frontend/e2e/output/t05-1-leave.log |
| frontend：`node e2e/leave-review.mjs` | 46／46，exit0 | frontend/e2e/output/t05-1-leave-review.log |
| frontend：`node e2e/attendance.mjs` | 37／37，exit0 | frontend/e2e/output/t05-1-attendance.log |
| frontend：`node e2e/multiple-attendance.mjs` | 17／17，exit0 | frontend/e2e/output/t05-1-multiple-attendance.log |
| frontend：`node e2e/attendance-corrections.mjs` | 74／74，exit0 | frontend/e2e/output/t05-1-attendance-corrections.log |
| frontend：`node e2e/organization.mjs` | 54／54，exit0 | frontend/e2e/output/t05-1-organization.log |
| frontend：`node e2e/timesheets-success-body.mjs` | 21／21，exit0 | frontend/e2e/output/t05-1-timesheets-success-body.log |
| frontend：`node e2e/timesheets-review.mjs` | 24／24，exit0 | frontend/e2e/output/t05-1-timesheets-review.log |
| frontend：`node e2e/timesheets-retry-boundaries.mjs` | 25／25，exit0 | frontend/e2e/output/t05-1-timesheets-retry-boundaries.log |
| frontend：`node e2e/attendance-target-review.mjs` | 29／29，exit0 | frontend/e2e/output/t05-1-attendance-target-review.log |

11支E2E共435個檢查點（新52＋回歸383）。新版手機案例先設定390×844／mobile，再重新開啟明確ID詳情，避免Puppeteer模擬切換重載造成只測到基礎頁；補強後52／52。mobile PNG已檢視，模態顯示、無橫向溢出；不稱實機驗證。五項變異均一個斷言Failure／0Error、原bytes還原後一項通過，最後345全套證明恢復，見[mutation-evidence](mutation-evidence.md)。

沒有重跑完整`npm run e2e`的smoke／logout／password／定位／競爭／一般工時腳本；本輪未改共用認證、HTTP、定位或工時程式，後端全套及相關回歸已實跑。本機另外只執行三個帳號的登入與GET新版契約。手機補強只改測試等待順序；最後另刪除revisionOutcome檔尾多餘空行，產品邏輯未變，build／lint再通過。作者測試與使用者帶回ChatGPT20項受控檢查分開。

## 最終備份、升級與資料保留

最後備份：`C:/Users/10739/.codex/local-backups/java-eip/20261013_final_9521edcd/development-v8.dump`；同資料夾restore-evidence.json／activation-evidence.json含舊欄位清單與彙總摘要，無完整個人明文資料。pg_export_snapshot一致快照＋pg_dump -Fc -f，再docker cp原位元檔；本機與還原archive SHA256一致。還原目標tp_restore_20261013_final_9521edcd是本輪新庫，確認catalog不同於開發／共用測試庫後建立，只在還原確認後清理。

前兩份備份位於20261013_51799e4d、20261013_9d602043，三份本機custom備份保留、不進Git。最新備份在必要自測全部通過後建立。啟動dev時CLI明確空白一般／developer seed密碼、reset=false、STMAX／org seed=false；沒有改.env，沒有重設帳號、密碼、角色或主管。正常啟動Flyway只套一個V9（V8已存在，沒有重套／退版），CRC32與目前原檔一致，無clean／repair。

| 舊表 | 升級前／後筆數 | 舊欄位投影排序逐列摘要 |
|---|---:|---|
| employee | 10／10 | 相同 |
| department | 3／3 | 相同 |
| organization_history | 9／9 | 相同 |
| attendance | 7／7 | 相同 |
| attendance_request | 3／3 | 相同 |
| attendance_history | 6／6 | 相同 |
| geofence_setting | 1／1 | 相同 |
| geofence_setting_history | 0／0 | 相同 |
| attendance_clock_event | 4／4 | 相同 |
| project | 0／0 | 相同 |
| timesheet_entry | 0／0 | 相同 |
| leave_request | 2／2 | 相同 |
| leave_history | 4／4 | 相同 |
| leave_approver_setting | 0／0 | 相同 |
| attendance_clock_submission | 0／0 | 相同 |

員工包含密碼雜湊／角色／主管等舊欄位都以彙總比較，不輸出原值。新增leave_document2筆，舊每筆根版本、APPROVED有效指標／版本1、其他0的轉換錯誤0；舊history不新增決定。五張原空表仍空，非空代表資料保留由隔離V8→V9十五表probe補證，見backend/target/t05-1-migration-evidence.txt。登入可用性檢查後再比較15表仍完全相同。

## 實際服務與本機可用性

2026-10-13最後確認：renewflow-db既有容器；catalog renewflow V1～V9 success=true。8080 Java PID7260（本專案EipApplication），5173 Vite PID516；隱藏啟動helper分別49140／48256。網站http://localhost:5173，後端認證未登入401、前端200、代理認證401。stmax_leo／iris／max分別EMPLOYEE／MANAGER／ADMIN登入、新版文案與GET契約通過，**業務POST／PUT為0**，不是代替真人送單／審核。唯讀預計審核人Leo→Iris、Iris→Max；Max NONE，無自動指定。

既有其他專案8081 PID35604未動。隔離8082／5175無殘留，測試庫renewflow_t051_20261013_51799e4d_test已在catalog與無連線確認後清除，只刪本輪三個container /tmp archive，本機備份保留；沒有清開發／共用renewflow_test或其他容器。本次測試庫已清理，重跑需新建專用`*_test`庫再指定DB_TEST_NAME，勿把不存在的本輪名字直接當前置完成。

真人逐步教材[manual-verification](manual-verification.md)M33／M51全部未執行。密碼從本機.env的RENEWFLOW_DEMO_STMAX_PASSWORD取，不列值；沒有設定Max審核人或改STMAX組織。

最終狀態：**T05.1 作者自測完成，等待 ChatGPT 複審；真人驗收依實際結果記錄。可設定簽核流程與拖拉設計器尚未實作。**
