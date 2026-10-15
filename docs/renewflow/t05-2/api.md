# T05.2 實際 API 與權限契約

根路徑`/api/admin/leave/workflow`。所有端點僅ADMIN，URL與Service雙重驗證；Session／CSRF／ActiveAccountFilter沿用。沒有DELETE／歷程修改／直接變更假單審核結果端點。MANAGER不能讀設定，ADMIN仍只能在審核入口處理實際指派給自己的提交。

| 操作 | 未登入 | EMPLOYEE／MANAGER | ADMIN |
|---|---|---|---|
| 設定／版本／操作歷程／管理預覽 | 401 | 403 | 可讀 |
| 草稿／發布 | 401 | 403 | CSRF、帳號重驗及版本檢查後可寫 |
| 本人假單／快照 | 401 | 僅本人 | 僅本人入口；管理入口另依原授權 |
| 審核假單／快照 | 401 | 員工不可審；主管僅實際指派 | 僅實際指派，不借設定權擴大 |

## 版本、草稿及發布

GET根回`{current,draft,versions,history}`；未發布current=null；未有草稿draft=null。version內容：id、number（流程內容版號）、status、rowVersion（草稿並行版）、basePublishedId、values、createdBy／At、publishedBy／At、publishReason。發布列不可再修改，後續POST另一個草稿。

POST `/drafts`建立，PUT `/drafts/{id}`編輯，實際本文使用巢狀values：

```json
{
  "requestId": "18653c04-4bc4-4bbf-bce3-544f53fd14f1",
  "expectedPublishedId": null,
  "expectedVersion": null,
  "values": {
    "name": "請假簽核流程",
    "description": "一關主管審核",
    "source": "MANAGER",
    "designatedApproverId": null
  }
}
```

範例UUID僅示意，不向開發庫直接送出。name去空白後1～100字；description必須為字串、最多1000字，可空。MANAGER指定ID必須null；DESIGNATED必須有合格人員ID。介面建立時送expectedVersion=null，建立並行由目前發布ID與唯一草稿檢查；編輯必須為所見rowVersion。expectedPublishedId必須與目前指標相符；base仍須相符。DTO不含關卡陣列，固定一關「請假審核」。

POST `/drafts/{id}/publish`：`{requestId,expectedVersion,reason}`，原因去空白後1～500字。重驗目標啟用／資格，與當前基礎比對後，發布＋指標＋完整歷程同交易。MANAGER規則可發布但特定員工可能沒有主管；DESIGNATED本人申請仍被自審規則阻擋，預覽明示。

寫入成功HTTP200，回`{requestId,action,version,replayed}`。action為DRAFT_CREATED／DRAFT_SAVED／PUBLISHED。首次replayed=false；同操作者同鍵同正規化本文回凍結原結果、replayed=true，先於現在資格／狀態驗證；異內容／操作／目標或操作者409。POST兩位管理員同時建立只有一草稿；同rowVersion編輯只有一成功。相同鍵發布可確認同次結果，不同鍵再次發布409。

GET `/operations/{requestId}`：查持久化原操作結果（同200契約）。404表示尚未查到，不能推論操作不會稍後提交。未知結果需保留同UUID／本文、查證或原鍵重試；前端不將異常2xx當成功，不因後續403／409解鎖原未知意圖。

## 預覽與提交

GET `/preview?employeeId=123`預覽正式規則；加`draftId=456`只預覽指定已儲存DRAFT。兩ID為示例。回employee、source、workflowVersionId／Number／Name、stageName、rule、approver、personalOverride、problem。source為PERSONAL／WORKFLOW／DEFAULT_MANAGER。規則未能解析以problem說明，不建立假單／歷程。個人指定即使失效也優先且不fallback；申請人停用同樣顯示阻擋。預覽與提交共用ApproverResolver，真正送出仍在鎖內重新解析。

請假原端點不變，request新增nullable `approvalSnapshot`。新提交包含來源、流程識別／版號／名稱（適用時）、一關內容／規則、申請人與解析人id／員編／名稱及capturedAt。舊資料null顯示「舊制提交」，不查現在設定偽造歷史。原`approverSource`增加WORKFLOW，PERSONAL對應原DESIGNATED、無流程主管對應MANAGER。改派後現在來源REASSIGNED，approvalSnapshot與submitted_approver仍不變。

審核歷史範圍沿用T05.1：只對已提供的request組裝其自身快照；不包含現在全局設定、未授權後續快照或設定history。新流程版本歷程只存在ADMIN設定API。

## 錯誤與副作用

| HTTP／code | 意義 |
|---|---|
| 400 VALIDATION_FAILED／INVALID_WORKFLOW／REASON_REQUIRED／REASON_TOO_LONG | 格式／名稱／來源／原因不合法 |
| 401 UNAUTHENTICATED | 未登入、停用／角色變更，含等待鎖後重驗 |
| 403 FORBIDDEN／既有CSRF | 無ADMIN或寫入缺驗證 |
| 404 WORKFLOW_NOT_FOUND／EMPLOYEE_NOT_FOUND | 版本／員工不存在 |
| 404 WORKFLOW_OPERATION_NOT_FOUND | 尚未查到原操作，可能仍處理中 |
| 409 WORKFLOW_CHANGED | 草稿rowVersion或目前／基礎發布版本過期 |
| 409 WORKFLOW_DRAFT_EXISTS／WORKFLOW_NOT_DRAFT | 已有草稿／目標已發布 |
| 409 APPROVER_INVALID | 指定人無效，不替換 |
| 409 WORKFLOW_REQUEST_CONFLICT | 原操作鍵已用於其他內容或操作者 |
| 409 NO_VALID_APPROVER／原請假衝突碼 | 真正提交無合法審核人／來源或有效內容過期等 |
| 500 | 交易／歷程失敗，回滾；前端視結果待查證 |

所有拒絕不改業務資料。固定順序：OrganizationLock→actor重驗→流程／組織／請假資料；發布不修改已提交列。
