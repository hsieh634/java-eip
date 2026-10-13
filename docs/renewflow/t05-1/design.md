# T05.1 需求、領域、架構、資料、API 與安全設計

> 第1輪修正：下方原設計將「URL提交授權」套用完整同單上下文，會洩漏後續未指派版本。現在Service依入口選FULL／REVIEW_HISTORY，審核只提供所選及必要前序，版本、history／decision_snapshot及出勤使用一致範圍；介面明示歷史基礎不是全局現在狀態。R2只修撤回文字，不改交易；展示用獨立demo_*與預設關閉本機CLI，無migration。實作前需求／設計見[review-round-1](review-round-1.md)，結果見[verification](review-round-1-verification.md)。

2026-10-13，基準 72ba3265037a7cd09c49f3f018b0ee408170f87e；使用者新附件授權本機備份／升級、T05.1、測試、文件及一般 commit／push。T03.3 R1 已通過使用者帶回的 ChatGPT 程式複審及實際表單邏輯 20 項受控檢查（基準11／20→修正20／20）；BUG-TP-001 前輪通過。完整整合／E2E 是作者證據，真人尚未完成。

## Requirement → Domain Analysis

沿用單日、半小時刻度、假別、日期±365天、分鐘計算與角色／資料範圍。增加待審修改、退回重送、核准後變更、整段及單側部分銷假；每次提交獨立 ID／UUID／審核人快照。待審後續流程不改原有效內容；核准才切換，撤回／退回不影響原有效內容。一筆業務單最多一個待審版本。

原申請、申請狀態與有效請假是不同概念。歷史 APPROVED 仍代表當時核准，不能用所有 APPROVED 列相加或判斷占用。銷假全文仍保存原完整時段，核准後以業務單無有效內容且 canceled=true 表示，不寫零分鐘。部分銷假提交「銷假後保留的時段」，必須是原有效時段的非空真子集且只縮短開始或結束；兩側同時縮短、中間拆兩段、跨日／批次不做。

## Architecture／Proposal

沿用 Controller → Service → Repository、DTO、建構子注入及模組化單體。LeaveRequestService 保留既有 CRUD 與審核入口，新增 LeaveRevisionService 處理後續提交與審核前檢查／有效內容切換，LeaveDocumentRepository 保存業務單狀態，避免把版本邏輯塞入 Controller。出勤新增只讀 EvidenceService／DTO，請假服務只取得資料、不改出勤。無新套件／共用簽核引擎或設計器。

## Data Model（V9；V1～V8 不改）

- leave_request 每次已提交內容不改；新增 document_id、revision_no、operation、source_request_id／version、base_effective_request_id／version、fingerprint、submitted_approver_id／source。
- operation：CREATE、REVISE、CHANGE、CANCEL_ALL、CANCEL_PART。來源 PENDING／REJECTED 可重送同業務動作（CREATE→REVISE）；歷史 APPROVED 只有當它仍是有效內容才可發起後續。舊拒絕紀錄維持 REJECTED。待審修改把舊待審轉 WITHDRAWN，原因「已由新版本取代」，與新列／歷程同交易。
- leave_document.id 為首筆 request.id；employee_id 固定；effective_request_id 是目前有效版本，effective_version 每次成功核准+1；canceled 表示整段已銷假。資料庫使用同員工／同文件的複合外鍵、來源外鍵、文件＋版本唯一與 PENDING 部分唯一索引；不是文字連結。
- CREATE 透過資料庫 trigger 建立業務單，確保既有新增 SQL 與合成資料仍有完整關聯；雙向 FK 延至交易結束檢查。舊每筆請假轉為獨立文件；APPROVED 指向本身且有效版本1，其他狀態無有效內容、版本0。不改舊狀態／審核決定／歷程，不新增決定。
- 已提交內容及來源／submitted 審核人欄位由 trigger 保護，狀態、目前審核人、version 仍走既有條件更新。舊曾改派的 submitted 審核人以最早 SUBMITTED 歷程取得；無法推回的審核來源標 LEGACY_UNKNOWN，不捏造來源。舊主欄位完整保留。
- leave_history 追加 nullable 的 decision_snapshot（有效版本前後與出勤摘要）；既有只增不改 trigger 保留。新審核保存當時出勤摘要、ID、版本、事件及資料取得時間；舊歷程不假裝有未保存的證據。

## API Design

既有新增／列表／詳情／審核／撤回／改派 URL 保留。新 POST /api/leave/me/{sourceId}/revisions：requestId、operation、expectedSourceVersion、expectedEffectiveRequestId（可null）、expectedEffectiveVersion、假別／日期／起訖／原因。完整銷假本文保留原有效時段；部分銷假本文為剩餘時段。回應明確帶 UUID、動作、來源 ID／版本、原有效版本及新申請。

同員工同UUID先比持久化指紋（包含動作、來源快照及正規化內容）再判斷目前業務狀態／審核人。相同提交已核准／退回／撤回仍確認原列，不建新流程；異內容409 REQUEST_ID_CONFLICT。新提交重新解析審核人，已送版本不暗改派。

詳情增加文件 origin／originalApproved／effective／effectiveVersion／canceled／pending／versions 與出勤 evidence（records、原 events、capturedAt、token）。列表標明目前有效或歷史版本，合計只計目前有效列一次（本頁）。核准／退回可帶 evidenceToken；CHANGE／銷假必須有，證據變動409 ATTENDANCE_EVIDENCE_CHANGED。原 CREATE／REVISE 的舊客戶端可省略 token，歷程明記為決定當下擷取，不能說成使用者已看過。前端新版一律帶 token。

錯誤：既有 LEAVE_CHANGED／LEAVE_NOT_PENDING／LEAVE_OVERLAP／NO_VALID_APPROVER／404 沿用；新增 SOURCE_INACTIVE、REVISION_OPEN、EFFECTIVE_CHANGED、INVALID_REVISION、ATTENDANCE_EVIDENCE_CHANGED。所有拒絕零業務寫入。

## Security Design／Locks／Transactions

所有寫入先 OrganizationLock.acquireFor，等待後重驗操作者。沿用組織→業務列順序，不向出勤寫入取逆序鎖；出勤讀取以單一 SELECT 的一致快照並保留審核時摘要。員工只能自己版本；合法指派 MANAGER／ADMIN 才可審核，不自審；ADMIN 管理查詢可讀，沒有跨過審核直接改有效內容的捷徑。

出勤 EvidenceService 只接受請假服務已驗證身份的同文件申請人與相關日期（原核准／目前有效／申請）；不新增全公司出勤入口、不含 GPS。原始事件與更正後資料分別顯示，沒有出勤也可審核，絕不自動核准。

重疊來源：目前有效內容＋所有 PENDING 非 CANCEL_ALL 的預計新時段。原有效內容在 CHANGE／CANCEL 待審仍占用，CANCEL_ALL 不新增預留但不提前釋放。排除本文件自身，再查其他文件；送出與核准均檢查。退回／撤回僅釋放該待審預留。

待審修改的舊失效／新列／去重指紋／歷程、核准的新狀態／文件有效內容／版本／審核證據必須一起成功或回滾。唯一 pending 索引及有效版本條件 SQL 為最後防線。

## Frontend Design

後續表單每次從明確 sourceId 載入最新 detail，不 fallback 到別單或新增。合法選項由狀態與文件決定；無有效內容或舊版本明示失效。成功回應以 unknown 處理，執行期比對 UUID／動作／來源快照／內容。結果不明鎖原本文，重試任何拒絕仍不清快照；經警告才放棄。

頁面有同步 ref 與畫面鎖，未知一般新增／修改時不能切換其他修改表單丟追蹤。送出中不能換目標／日期，401 保留原登入失效處理，卸載不更新新頁；不做跨瀏覽器草稿。審核畫面分開原核准、目前有效、本次請求、差異、出勤原事件與目前紀錄，明示取得時間並可刷新；歷程顯示當時凍結摘要。

## 本機安全與備份

本輪實查 Git main／遠端為預期 SHA、乾淨；開發 catalog renewflow 已 V8，8080／5173已在執行，另有8081。使用 pg_export_snapshot＋pg_dump -Fc -f，不以 PowerShell二進位重導向；還原新臨時庫後15表筆數／排序JSON摘要相同。備份位於 C:/Users/10739/.codex/local-backups/java-eip/20261013_51799e4d/development-v8.dump，不進Git；還原庫已清除。

僅正常 Ctrl+C 已辨識之本專案控制台（核對所有 console PID），停止8080與5173避免版本混用；既有8081保留。測試使用新建 renewflow_t051_20261013_51799e4d_test（actual catalog allowlist），埠8082／5175，不清共用 renewflow_test。V9 開發升級前再備份／驗還原與舊欄位投影逐表比較；啟動時停用 seed/reset，既有密碼／角色／組織不改。真人案例均待 Max 執行。

詳細JSON、HTTP與錯誤碼契約見[api](api.md)；歷程追加leaveRequestId，把同單歷程明確連回實際提交。
