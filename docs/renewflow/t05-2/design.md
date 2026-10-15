# T05.2 第一批：需求、領域、架構與設計

日期2026-10-15，接手基準e6397659df9628362774f2438c2cfa60db293825，main／origin／實際遠端一致、工作區乾淨。已讀工程指南、最新交接、產品方向、組織／請假／T05.1規格與相關Service／Repository／UI。未發現repo AGENTS.md／CLAUDE.md，依使用者提供的AGENTS規範。

使用者帶回：T05.1 R1／R2針對性程式複審完成、未發現新增阻擋；不表示重跑作者349後端／252 E2E或真人驗收。保留所有demo_*／stmax_*及人工操作。

## Requirement → Domain Analysis

只做請假、一個審核關卡，來源為直屬主管或指定合格員工；ADMIN設定權與實際指派審核權分開。無多關卡、職稱、分支、代理、通知、其他表單或設計器。

名詞：草稿是可編輯設定；發布版本是不可變的設定內容；目前版本是只影響新提交的單一指標；提交快照是每次真正提交解析出的來源／版本／關卡／申請人與審核人必要顯示資訊，與現在指派不同。合法改派只改現在指派、保留原快照。

優先：存在個人指定→已發布流程→無發布時原直屬主管。高優先來源失效阻擋，不fallback、不改個人指定。草稿預覽套用同一解析器且保留個人指定優先。共用指定人可服務其他員工，但該人自己申請仍阻擋自審；不新增超級帳號。

## Architecture／Proposal

沿用模組化單體Controller→Service→Repository、DTO、建構子注入、既有Session／CSRF／ActiveAccountFilter。新增leave模組內LeaveWorkflowDtos／Repository／Service／Controller與提交快照保存；ApproverResolver擴充同一解析規則，保留原請假交易。沒有通用BPM層或新依賴。

新增設定寫入沿用OrganizationLock，固定順序：組織交易advisory鎖→帳號重驗→流程列／員工／請假列；不新增第二把跨模組鎖。發布、送出、新版本、改派、組織變動以鎖取得順序序列化；送出只看完整已提交版本。預覽也在短交易取得同鎖及重驗ADMIN，以一致讀取規則，零業務寫入；它仍不能保證之後提交。既有一般GET不加鎖。

## Data Model（已確認開發庫最高V9，新增V10）

leave_workflow_version：內容版號、名稱／說明、固定一關「請假審核」、MANAGER／DESIGNATED及指定ID、base發布ID、DRAFT／PUBLISHED、草稿rowVersion、建立／發布者與時間、發布原因。單一DRAFT部分唯一索引；PUBLISHED禁止UPDATE／DELETE。內容版本與草稿rowVersion不同。

leave_workflow_state：id=1的目前發布ID，初始null，沒有預先發布。外鍵／觸發器只允許指向PUBLISHED。版本列與指標、歷程在同一交易更新。

leave_workflow_history：操作UUID、actor、action、正規化本文指紋、前後完整版本內容、前後目前ID、原因與時間，只新增。操作UUID可查證／重送；相同鍵異內容／操作者409，先查原操作再驗現在草稿，避免發布回應遺失後重送變成新發布。歷程保存原操作結果，不以現在可變草稿代替。

leave_request追加nullable workflow_version_id FK及approval_snapshot。新提交在原INSERT同時保存；舊列保持null並明示舊制，不偽造歷史流程。新快照含來源（PERSONAL／WORKFLOW／DEFAULT_MANAGER）、發布版本ID／版號／名稱（若有）、關卡／規則、申請人與解析審核人id／員編／名稱、取得時間。DB保護快照與版本不可改，且引用發布版本；原submitted_approver欄位、狀態、內容與歷程不變。發布不UPDATE既有假單。

## API Design／Security Design

前綴`/api/admin/leave/workflow`全部ADMIN，Service再驗角色，寫入皆CSRF。EMPLOYEE／MANAGER403；未登入401；設定權不提供其他人的審核權。沒有刪除設定／版本／歷程API。

| API | 契約／結果 |
|---|---|
| GET 根 | current、draft、versions、history；僅管理端全部設定 |
| POST /drafts | requestId、expectedPublishedId、name、description、source、designatedApproverId；無既有草稿、基礎相符才建立 |
| PUT /drafts/{id} | 上述內容＋expectedVersion；只改指定DRAFT且rowVersion相符 |
| POST /drafts/{id}/publish | requestId、expectedVersion、reason；重驗來源資格、base仍是current；發布＋切指標＋歷程原子交易 |
| GET /operations/{requestId} | 查證持久化操作結果；未知404，不解釋成確定未送出 |
| GET /preview?employeeId=&draftId= | 正式或指定已儲存DRAFT預覽；顯示來源／版本／一關／解析人／問題與個人優先提示 |

所有寫入回OperationResult(requestId、action、version、replayed)，version為該操作凍結內容。前端當unknown驗證UUID、action、目標／版號與儲存本文；異常2xx、解析失敗、斷線／5xx均保留同意圖UUID及本文並提供GET查證／原鍵重試。403／409等後續拒絕不清原未知追蹤。查證404仍保持未知。明確丟棄須警告並重新載入，不自動生成另一鍵重送。401依既有登入失效處理，不跨登入自動重播。

錯誤：400 INVALID_WORKFLOW／REASON_REQUIRED、409 WORKFLOW_CHANGED／WORKFLOW_DRAFT_EXISTS／WORKFLOW_NOT_DRAFT／APPROVER_INVALID／WORKFLOW_REQUEST_CONFLICT；404 WORKFLOW_NOT_FOUND／WORKFLOW_OPERATION_NOT_FOUND／EMPLOYEE_NOT_FOUND。請假仍用NO_VALID_APPROVER、REQUEST_ID_CONFLICT等既有碼。拒絕零業務寫入。

R1保護：快照只附於已授權提供的request DTO，不再查全局現在設定，也不把流程history塞到請假詳情；所選／前序集合原樣限制新欄位。ADMIN設定頁可讀設定不代表可讀未指派假單。

## Frontend／取捨

新增ADMIN「簽核流程設定」，目前版、草稿表單、來源／合格人員、搜尋員工預覽、發布前後內容與原因、版本及操作歷程。沿用hash導航與UI，無套件。表單未儲存提示、離頁警告、儲存／發布鎖、過期回應序號及卸載保護；未知結果鎖原本文、可查證。重新載入／外部版本變更不偷偷覆蓋未存表單。手機可操作。假單詳情展示原提交快照與目前指派，舊列明示舊制。

只一個共同鎖較容易驗證、吞吐限制列為未測負載，不另加分散鎖。多API草稿編輯與發布有rowVersion與baseCurrent雙重檢查；每次儲存記完整內容方便查證，歷程量需未來按實際規模評估，不先加框架。

## 本機資料與階段

現有8080／5173及5432由本機執行，另有8081屬既有其他服務，不接管。隔離新建本批*_test庫，JUnit與E2E循序、actual catalog核對，只清理已記錄歸屬的資源。開發升級前停止已辨識本專案服務、custom備份／隔離還原及16表舊欄位摘要比對；升級只新增V10表及nullable欄位，不發布、不提交、不重跑demo。完成後載入功能並清楚交代服務由誰啟動。
