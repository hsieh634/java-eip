# T05：架構

模組化單體不變，新增 `com.renewflow.eip.leave` 模組。請假只**讀取**組織資料（員工的角色、啟用、直屬主管），不修改員工、部門或主管。

```
Controller
  ├─ LeaveController              /api/leave/me/**、/api/leave/approvals/**
  └─ LeaveAdminController         /api/admin/leave/**
Service
  ├─ LeaveRequestService          送出、撤回、核准、退回、本人與審核人查詢
  ├─ LeaveAdminService            管理查詢、改派、指定審核人設定
  ├─ ApproverResolver             決定（或預覽）審核人：指定 → 直屬主管 → 無
  ├─ LeaveAuditLog                請假歷程的唯一寫入入口（同交易）
  └─ LeaveViews                   Entity → DTO（補人名、審核人目前是否有效）
共用（organization 套件）
  └─ OrganizationLock.acquireFor(actor)   取鎖 → 重新確認操作者（D-70、D-71）
Repository
  ├─ LeaveRequestRepository       ON CONFLICT DO NOTHING RETURNING id、版本＋狀態條件 UPDATE、重疊查詢
  ├─ LeaveApproverSettingRepository
  └─ LeaveHistoryRepository
```

## 1. 寫入流程（以核准為例）

```
POST /api/leave/approvals/{id}/approve
 → ActiveAccountFilter（帳號仍有效、角色相同）
 → Controller：@Valid
 → LeaveRequestService.approve（@Transactional）
     1. 角色是 MANAGER／ADMIN（否則 404）
     2. OrganizationLock.acquireFor(actor)：取鎖；重新讀取帳號狀態 → 失效即 401，不寫入
     3. 讀申請（最新）→ approver_id = 我？（否則 404）
     4. 狀態 PENDING？（409）→ 版本相符？（409）
     5. 條件 UPDATE：where id, version, status = 'PENDING'（version + 1）
     6. LeaveAuditLog（同交易）
 → 提交，鎖釋放
```

## 2. 鎖的範圍（更新）

`OrganizationLock` 的範圍從「組織管理」擴大為「組織管理＋請假寫入＋指定審核人設定」：
- 這些寫入彼此排隊。代價是公司內所有人的請假送出也會一個接一個處理；每筆交易只有少量主鍵與索引查詢，不呼叫外部服務。
- 登入、打卡、工時、所有查詢都**不**取這把鎖。但它們仍可能因為其他 SQL 列鎖而等待（例如 T04 的員工列鎖），這把鎖不保證「完全不會等待」。

## 3. 前端

| 路徑 | 頁面 | 入口 |
|---|---|---|
| `#/leave` | `LeavePage`：送出、預計審核人、本人清單、詳情、撤回 | 所有人 |
| `#/leave/approvals` | `LeaveApprovalsPage`：指派給我的申請、核准、退回 | MANAGER、ADMIN |
| `#/admin/leave` | `LeaveAdminPage`：申請查詢、歷程、改派；指定審核人設定 | ADMIN |

共用：`api/leave.ts`、`leave/labels.ts`、`leave/LeaveDetail.tsx`（詳情＋歷程）。不新增套件。
