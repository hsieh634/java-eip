# T05.1 API 契約

既有本人／指派審核／ADMIN管理路由不變，所有寫入需要Session、CSRF，過期帳號401。DTO不接受呼叫者指定員工、審核人、狀態、分鐘。新增後續提交：

```http
POST /api/leave/me/101/revisions
Content-Type: application/json
```
```json
{
  "requestId": "cb5d2c08-9306-4b15-9eec-93f7da551b0c",
  "operation": "CHANGE",
  "expectedSourceVersion": 1,
  "expectedEffectiveRequestId": 101,
  "expectedEffectiveVersion": 1,
  "leaveType": "PERSONAL",
  "workDate": "2026-10-14",
  "startTime": "09:00",
  "endTime": "14:00",
  "reason": "提早返回"
}
```

101為合成例ID，勿拿例子向開發庫送出。操作為REVISE／CHANGE／CANCEL_ALL／CANCEL_PART；API route的sourceId也在指紋內。CANCEL_ALL本文保留原完整正分鐘區間；PART填銷假後保留的時段，單側真子集。首次201、相同提交確認200；不可只看HTTP就認定成功：

```json
{
  "replayed": false,
  "requestId": "cb5d2c08-9306-4b15-9eec-93f7da551b0c",
  "operation": "CHANGE",
  "sourceRequestId": 101,
  "sourceVersion": 1,
  "baseEffectiveRequestId": 101,
  "baseEffectiveVersion": 1,
  "request": { "id": 102, "requestId": "cb5d2c08-9306-4b15-9eec-93f7da551b0c", "status": "PENDING" }
}
```

以上request略去未變的原T05契約欄位，實際必須有完整內容、分鐘、applicant、approver、createdAt／updatedAt、version及revision metadata；前端明確核對送出內容、UUID、操作、source／base快照和documentId。replayed=true可回APPROVED／REJECTED／WITHDRAWN，不建立第二筆或重新指派。

詳情：`request`、同文件`history`、`document`（id、effectiveVersion、canceled、origin、originalApproved、effective、pending、versions）與`evidence`（dates、records、events、capturedAt、token）。歷程包含實際提交ID與新決定快照，舊決定無snapshot保持null，不推造證據。revision包括提交版本、來源、基礎有效版本、目前是否有效、送出時審核人ID／來源。歷史核准status保留APPROVED，但只有document有效指標占用時段。清單`effectiveTotalMinutes`是本頁有效列，不是全頁範圍或額度。

核准本文：`expectedVersion`＋`evidenceToken`；退回再加必填reason。新版一律送token；CHANGE／CANCEL強制要求，CREATE／REVISE舊呼叫者省略時只記決定當下快照且previewVerified=false。token不含capturedAt，內容沒變刷新不算衝突。

| HTTP／code | 意義／副作用 |
|---|---|
| 400 INVALID_REVISION／既有INVALID_LEAVE | 動作或區間不合法；無寫入 |
| 401 UNAUTHENTICATED | 無Session、帳號停用或鎖等待後失效；無寫入 |
| 403 FORBIDDEN／CSRF | 非合法角色或缺CSRF；無寫入 |
| 404 LEAVE_NOT_FOUND | 非本人或非指派審核範圍；不洩漏他人內容 |
| 409 LEAVE_CHANGED／LEAVE_NOT_PENDING | 提交狀態版號變動或已結案 |
| 409 SOURCE_INACTIVE | 明確來源已不是合法版本，不自動改另一單 |
| 409 EFFECTIVE_CHANGED | 有效指標／版本過期，整批回滾 |
| 409 REVISION_OPEN | 同業務單另有待審流程 |
| 409 LEAVE_OVERLAP | 與其他文件有效或待審預留衝突 |
| 409 NO_VALID_APPROVER | 指定失效不fallback，無合格主管，不失效原待審 |
| 409 REQUEST_ID_CONFLICT | 同員工UUID異操作／來源快照／內容，不改原提交 |
| 409 ATTENDANCE_EVIDENCE_CHANGED | 出勤內容已變或未提供必要確認，須刷新 |
| 500 | 實際資料／歷程失敗；同交易全部回滾，前端視結果不明 |

後續API、資料約束、鎖與權限見design.md，逐案證據見verification.md。
