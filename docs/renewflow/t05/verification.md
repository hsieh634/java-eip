# T05：Implementation、自動測試、人工驗證、Review、Archive

## 1. Implementation（實際檔案）

| 層 | 檔案 |
|---|---|
| Migration | `backend/src/main/resources/db/migration/V7__leave_requests.sql` |
| 共用鎖 | `organization/OrganizationLock.java`：`acquireFor(actor)` |
| 請假 | `leave/LeaveController`、`LeaveAdminController`、`LeaveRequestService`、`LeaveAdminService`、`ApproverResolver`、`LeaveRules`、`LeaveViews`、`LeaveAuditLog`、三個 Repository 與 Entity、`LeaveDtos`、`LeaveEnums` |
| 前端 | `api/leave.ts`、`leave/labels.ts`、`leave/LeaveList.tsx`、`leave/LeaveDetailDialog.tsx`、`pages/LeavePage.tsx`、`pages/LeaveApprovalsPage.tsx`、`pages/LeaveAdminPage.tsx`；`AppShell`、`useHashView`、`App`、`HomePage` |
| 測試 | `leave/` 下 5 個測試類別；`organization/OrganizationLastAdminDefenseTest`；E2E `frontend/e2e/leave.mjs` |

## 2. Automated Tests

指令與結果見學習文件 `docs/learning/T05-leave-and-approval.md` 第 9 節。

## 3. Manual Verification（開發環境）

**V7 套用**：重啟前已先說明原因與影響。前後以 md5 逐列比對：

| 表 | 結果 |
|---|---|
| employee、department、organization_history、attendance、attendance_request、attendance_history、geofence_setting、geofence_setting_history、attendance_clock_event、project、timesheet_entry | 全部相同 |
| 筆數（員工／部門／組織歷程／出勤／專案／工時） | 10／3／9／5／0／0（不變） |
| Flyway | 6 → 7 |
| leave_request／leave_approver_setting／leave_history | 0／0／0（沒有建立任何假單或設定） |

**唯讀檢查**：以 STMAX 帳號登入，只讀取資料，全程沒有任何寫入請求，13／13 通過。

| 帳號 | 預計審核人 | 入口 |
|---|---|---|
| Leo、Mia、Ryan | Iris（直屬主管） | 請假 |
| Iris | Max（直屬主管）；待審清單為 0 | 請假、請假審核 |
| Zoe | Max | 請假 |
| Max | **沒有**（沒有主管，也還沒有指定）；指定設定為 0 | 請假、請假審核、請假管理 |

Max → Iris 的指定設定**沒有**代為建立。由 Max 依 README 第 14 節自行在「請假管理」設定。

## 4. Review

- **第 1 輪**：ChatGPT 提出 R1～R3，以實際前端函式與 API 函式做 12 項受控檢查。檢查使用替身，沒有重跑瀏覽器 E2E 或 Spring／PostgreSQL 整合測試。修正見 [`review-round-1.md`](review-round-1.md) 與本文件第 6 節。
- 修正後等待 ChatGPT 複審。

## 5. Archive

複審通過後再整理。

## 6. 第 1 輪複審修正的驗證

**修改範圍**：只有前端（`submitOutcome.ts`、`api/leave.ts`、`LeavePage.tsx`、`LeaveList.tsx`、`LeaveAdminPage.tsx`），另新增 E2E `leave-review.mjs`。後端、API、V1～V7、共用元件都沒有修改。

| 項目 | 結果 |
|---|---|
| `leave-review.mjs`，基準程式（暫時 stash 修改，用最終版測試執行） | 24／43；R1、R2、R3 的行為檢查失敗。R1-0（新模組不存在）與 R1-6（前置情境無法成立）不算舊缺陷證據 |
| `leave-review.mjs`，修正後 | 46／46 |
| `npm run lint`、`npm run build` | 通過 |
| 回歸：見學習文件 `T05-review-fixes.md` 與 version-log 第 13 輪 | — |
| 後端 310 項 | **沿用**上一輪證據；後端沒有修改，本輪沒有重跑 |
| 開發環境 | 純前端修改，沒有重啟開發後端，也沒有操作開發庫 |

