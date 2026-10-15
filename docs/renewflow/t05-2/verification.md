# T05.2 第一批：實際驗證、資料保留與限制

日期2026-10-15，比較基準e6397659df9628362774f2438c2cfa60db293825。開始時main／origin/main／實際遠端一致，工作區乾淨；沒有重跑T05.1修正或開發展示建立。使用者帶回T05.1 R1／R2針對性程式複審完成、未發現新增阻擋，與先前作者349後端／252 E2E、真人未執行分開記錄。

## 本輪真實命令及結果

| 項目 | 實際命令／結果 | 本機log（不提交） |
|---|---|---|
| 原歷史讀取保護 | mvnw -B -Dtest=LeaveReviewScopeIntegrationTest test，4／4 | backend/target/t05-2-first-integration.log |
| 新流程整合 | mvnw -B -Dtest=LeaveWorkflowIntegrationTest test，17／17 | backend/target/t05-2-workflow-final.log |
| 還原變異後完整後端 | mvnw -B test，**367／367**；failure／error／skip均0，exit0 | backend/target/t05-2-backend-final.log |
| 前端build／lint | npm run build、npm run lint，exit0，lint無警告 | frontend/e2e/output/t05-2-build-final.log、t05-2-lint-final.log |
| T05.1版本流程 | node e2e/leave-revisions.mjs，52／52 | frontend/e2e/output/t05-2-leave-revisions-final.log |
| 請假 | node e2e/leave.mjs，56／56 | frontend/e2e/output/t05-2-leave-final.log |
| 請假複審 | node e2e/leave-review.mjs，46／46 | frontend/e2e/output/t05-2-leave-review-final.log |
| 組織 | node e2e/organization.mjs，54／54 | frontend/e2e/output/t05-2-organization-final.log |
| R1／R2＋初始化隔離回歸 | node e2e/leave-revisions-review.mjs，44／44，只在它新建庫做fixture | frontend/e2e/output/t05-2-leave-revisions-review-final.log |
| 新流程真表單 | node e2e/leave-workflow.mjs，**33／33** | frontend/e2e/output/t05-2-leave-workflow-final.log |

六支最終E2E共285項。新腳本也可`npm run e2e:leave-workflow`；自建專屬*_test庫、自行清理，不碰開發／共用測試庫。最終整合與回歸循序執行；早期一次全套與新UI測試曾同時使用各自不同的新庫，沒有共用catalog，最終證據以循序結果為準。

後端專用DB_TEST_NAME=`renewflow_t052_20261015_29842854_test`；舊E2E專用`renewflow_t052_regress_20261015_29842854_test`，環境E2E_API_PORT=8082／E2E_VITE_PORT=5175。新UI／舊初始化回歸各另建自己*_test庫。均經actual catalog guard，不用名稱相似判斷就刪庫。

重跑完整後端／舊E2E時，先依README建立新的專用*_test庫並設定DB_TEST_NAME，不可把已清除的本批庫名稱當作共用常駐庫。JUnit及同庫E2E不並行。新腳本自己建立／刪除本次庫，不清空既有庫。

## 案例逐項結果與證據來源

前置／角色／操作／預期及副作用完整表在[specification](specification.md)。以下記錄實際來源，不以項數代替涵蓋內容。

| ID | 本輪實際結果／證據 |
|---|---|
| W01 | 真HTTP權限／Session／CSRF零寫入；新UI驗ADMIN入口、員工強制hash及MANAGER API403 |
| W02 | 真提交無發布維持主管、個人指定優先；快照source與流程ID正確 |
| W03 | 個人指定停用、共用仍有效：預覽問題／提交NO_VALID_APPROVER、完整表未改 |
| W04 | 草稿指定與正式預覽分開；草稿未發布的新提交仍採原主管 |
| W05 | 真發布前後新提交各取當時完整版本；既有待審／顯示快照保留；UI詳情與發布差異正確 |
| W06 | 真並行兩ADMIN編輯一成功一409；同UUID發布確認同結果，不同UUID一成功一409；UI過期儲存保留輸入 |
| W07 | 真發布／提交同時競爭，快照版本與人一致；顯式持有advisory鎖使發布等待，期間降級目標，釋鎖後APPROVER_INVALID |
| W08 | 不存在／EMPLOYEE／停用資格拒絕；指定人本人、無主管提交阻擋；沒有ADMIN fallback |
| W09 | 同解析器草稿／正式預覽；完整表零寫入；預覽後改資格，實際送出仍重驗；發布後回正式預覽 |
| W10 | 原提交已成功→新流程發布／舊審核人停用→原鍵重試，原ID／快照相同且零寫入；設定原操作凍結結果也可在後續版本後確認 |
| W11 | 真待審修改、退回重送、CHANGE／CANCEL_ALL／CANCEL_PART保存新快照；撤回不改有效；合法改派不改原快照，舊指派404 |
| W12 | 新快照的後續秘密名稱不出現在舊Bob詳情；原T05.1四案及44項回歸均通過，ADMIN審核仍受限 |
| W13 | 流程歷程插入故障→發布列／指標／歷程全部回滾，同鍵可再發布；原提交歷程回滾測試在本輪全套再次通過 |
| W14 | 真後端提交後受控JSON壞掉／缺欄位／UUID錯／本文讀取失敗／500；查證404、重試403仍保留原本文／UUID；真GET查證／原鍵重送無重複；同步鎖擋連按 |
| W15 | 草稿409保留輸入；重新查詢保留輸入；真舊GET回應被扣住，在新儲存成功後放回，不覆蓋新草稿 |
| W16 | 已發布UPDATE／DELETE、設定歷程DELETE及提交快照UPDATE皆DB拒絕；真UI前後內容／原因／操作者／時間與不可變版本可見 |
| W17 | 乾淨V10與獨立schema V9→V10，16表舊欄位逐列JSON相同、不回填；開發備份／還原／升級後亦相同 |
| W18 | 390px模擬表單／未知確認／發布可操作、無橫向溢出，無未處理例外；截圖已檢視；不是實機真人 |
| W19 | 四個暫時變異各一案assertion失敗，還原後完整367通過，見下表 |

## 關鍵變異（真實執行後還原）

| 變異 | 同核心方法 | 結果 |
|---|---|---|
| 新提交不保存workflow ID／snapshot | publishingOnlyChangesNewSubmissionsAndPreservesSnapshotDisplayNames | 1案、failure1、error0 |
| 發布偷偷UPDATE既有PENDING審核人 | 同上 | 1案、failure1、error0 |
| 跳過原requestId既有提交確認 | requestReplayKeepsOriginalEvenAfterPublishingAndDisablingOldReviewer | 1案、failure1、error0 |
| 發布不取OrganizationLock | publishingWaitsForOrganizationAndRechecksTargetAfterRoleChange | 1案、failure1、error0 |

使用本批隔離庫，暫改產品碼、測試不放寬，finally逐位元還原；原碼還原後執行完整367／367。log：backend/target/t05-2-mutation-*.log；非敏感摘要另存外部mutation-evidence.json。不將故意失敗視為產品測試通過。

如實記錄調整：第一輪UI26／28，兩個斷言誤看input.disabled，應看fieldset繼承後的`:disabled`，改測正確瀏覽器狀態後通過。接著舊GET受控故障未攔到明確method=GET而逾時，補正攔截條件。再經32／32，修正發布後預覽模式切回正式並補案例，最終33／33。初期lint有ref存取提示，修正後最終無警告。

## 開發備份／升級／原資料保留

證據目錄`C:/Users/10739/.codex/local-backups/java-eip/t052_20261015_29842854/`。

- `development-v9-before-t052.dump`：pg_export_snapshot一致MVCC快照＋pg_dump custom二進位；容器與本機SHA256相同：`af9cad042d37bf10516690ed48a4c3282698b7e4c79b818d0db7034048c9093e`。
- `t052-restore-evidence.json`：新庫完整還原、16表筆數及排序整列JSON摘要相同；還原庫已清除，非僅檢查檔案存在。
- `development-v10-verification.json`：V10套用後16表原欄位投影相同，唯讀API／UI後亦相同；workflow版本0、歷程0、current=null，舊假單新snapshot非null筆數0。
- `backend-v10-startup.log`：只套V10一筆migration，正常啟動；CLI停用seed/reset/STMAX兩種初始化，沒有改.env值。V1～V9原檔不改。

| 舊資料表 | 前／後筆數（相同） |
|---|---|
| employee／department／organization_history | 16／3／15 |
| attendance／attendance_request／attendance_history | 16／3／14 |
| geofence_setting／geofence_setting_history | 1／0 |
| attendance_clock_event／attendance_clock_submission | 5／1 |
| project／timesheet_entry | 3／10 |
| leave_request／leave_history／leave_document | 19／32／12 |
| leave_approver_setting | 0 |

比較完整舊欄位含密碼雜湊、角色、主管、時間／狀態／有效指標／歷程，僅保存摘要而不輸出秘密或個人列。新表只有初始id=1／current=null狀態列；沒有替任何人發布或提交，也沒重跑開發demo初始化。既有D仍2026-10-13。初始化CLI僅接受已驗證schema V9／V10的相容性調整，44項只在新隔離庫驗，不向開發執行。

## 本機服務／清理

起始8080 PID40840是使用者手動啟動的本專案dev後端，確認控制台無其他程序後正常Ctrl+C停止，避免新舊類別混用。V10驗證後端PID42780成功啟動、demo_max登入及ADMIN空設定／demo_leo預覽真畫面、舊假單舊制快照顯示確認；只auth＋GET，業務寫入0。桌面／手機截圖在frontend/e2e/output/t05-2-live-readonly.png及t05-2-workflow-mobile.png，已檢視。

依Max先前希望自行用PowerShell控制服務，**本批臨時後端已Ctrl+C關閉，8080釋放**。原5173前端PID36272保留，Docker renewflow-db健康；使用者啟動後端後才可登入操作。既有8081 PID35604未動；8082／5175隔離服務已停止。不要把「後端關閉」誤當功能未套用，開發庫已V10。

本批核心／回歸／每次UI／舊Demo回歸自建庫／備份還原庫均已確認歸屬清除。容器本批archive核對本機SHA後清除，本機dump與證據保留。原展示manifest及.env不改。清理結果cleanup-evidence.json，不清共用renewflow_test、不刪其他專案容器或資料卷。

## 證據界線與待辦

本輪367／285、升級／資料保留、受控網路與故障均為作者證據。T05.1程式複審完成是使用者本輪帶回；沒有冒稱本輪由ChatGPT跑完整測試。真人、實機、負載、正式環境未驗證。新T05.2尚待ChatGPT複審與Max依[真人步驟](manual-verification.md)執行。

沒有新工具安裝／相依套件／映像／雲端服務／費用。沒有多關卡／職稱／設計器、通知或後續模組。敏感／差異與連結檢查、commit／push SHA記錄見交接與最終交付；.env、密碼、dump、執行log、截圖、暫存狀態與產物不提交。
