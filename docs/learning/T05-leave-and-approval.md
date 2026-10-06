# T05：請假申請與主管審核——學習文件

> 規格：[`docs/renewflow/t05/`](../renewflow/t05/)。狀態：**已實作待驗證**（作者自測完成，等待 ChatGPT 複審）。
> 假別與時段規則是 **STMAX 示範規則／本批工程假設**，不是正式人事制度，也不代表符合法規。

## 1. 三分鐘摘要

- **用途**：員工送出請假（事假、病假、特休；一天內的一段時間、半小時為單位），由審核人核准或退回。員工可以撤回待審申請、查看結果與歷程。ADMIN 可以查詢、改派，並設定「指定請假審核人」。
- **審核人怎麼決定**：送出當下依序採用：
  1. ADMIN 設定的指定審核人；
  2. 否則採直屬主管；
  3. 都沒有就拒絕送出。

  決定後把審核人的員工 ID 存進申請（快照）。之後只有 ADMIN 改派會改變它。
- **最重要的規則**：
  - 同一員工的待審或已核准申請時段不能重疊；
  - 審核人不能是申請人；
  - 只有目前審核人能核准或退回，ADMIN 也一樣；
  - 已核准的申請不能修改或撤回。
- **三種保護各管一件事**：
  - `requestId` 防止同一次提交重複建立；
  - 版本號防止依過期畫面操作；
  - 交易鎖 `OrganizationLock` 保證跨多筆的規則：重疊、審核人仍有效、操作者仍有效。
- **新的共用改動**：`OrganizationLock.acquireFor(actor)` 在取得鎖之後重新確認操作者。等待鎖期間被停用或降級的人，會得到 401，什麼都不會寫入。

## 2. 操作入口

| 誰 | 畫面 | 輸入 → 結果 |
|---|---|---|
| 所有人 | 左側 **請假**（`#/leave`） | 假別、日期、開始、結束、原因 → 「待審」；清單、詳情、歷程、撤回 |
| MANAGER、ADMIN | 左側 **請假審核**（`#/leave/approvals`） | 指派給我的申請 → 核准／退回（原因必填） |
| ADMIN | 左側 **請假管理**（`#/admin/leave`） | 查詢、歷程、改派；指定請假審核人（原因、版本、歷程） |

逐步操作（含 STMAX 各帳號）見 README 第 14 節。

## 3. 技術、檔案與方法位置

| 位置 | 重點 |
|---|---|
| `backend/src/main/resources/db/migration/V7__leave_requests.sql` | `leave_request`（CHECK：刻度、順序、分鐘數、禁止自審、結案資訊）、`leave_approver_setting`、`leave_history`（只新增觸發器） |
| `organization/OrganizationLock.java` | `acquire()`、`acquireFor(actor)` |
| `leave/LeaveRequestService.java` | `submit`、`withdraw`、`approve`、`reject`、`listMine`、`listApprovals`、`findAssignedToMe`、`decide` |
| `leave/LeaveAdminService.java` | `search`、`reassign`、`updateSetting`、`getSetting`、`settingHistory` |
| `leave/ApproverResolver.java` | `resolve(applicant)`：指定 → 直屬主管 → 無；`canApprove(e)` |
| `leave/LeaveRules.java` | `normalize`（刻度、順序、日期 ±365、原因）、`Query.of`（狀態、日期範圍、分頁） |
| `leave/LeaveRequestRepository.java` | `findOverlapping`（半開區間）、`insertIfNew`（ON CONFLICT）、`decideIfPending`／`reassignIfPending`（版本＋狀態條件 UPDATE）、`search` |
| `leave/LeaveAuditLog.java` | 歷程的唯一寫入入口 |
| `frontend/src/pages/LeavePage.tsx` | `LeaveForm`（三階段＋未確認快照）、`WithdrawAction` |
| `frontend/src/pages/LeaveApprovalsPage.tsx` | `DecisionActions`（確認、衝突時重新載入） |
| `frontend/src/pages/LeaveAdminPage.tsx` | `ReassignAction`、`SettingEditor`／`SettingForm` |
| `frontend/src/leave/LeaveList.tsx`、`LeaveDetailDialog.tsx` | 共用清單（篩選、分頁）與詳情、歷程 |

## 4. 端到端資料流

### 4.1 送出

```
LeaveForm 送出（requestId＋內容快照）
 → POST /api/leave/me（CSRF）
 → ActiveAccountFilter：帳號仍有效、角色相同
 → LeaveController.submit（@Valid：必填、型別）
 → LeaveRequestService.submit（@Transactional）
     1. LeaveRules.normalize：刻度、順序、日期、原因（鎖外，純計算）
     2. lock.acquireFor(actor)：pg_advisory_xact_lock → 重新讀取帳號狀態
     3. 同員工＋同 requestId？
          內容相同 → 回傳原申請（replayed）
          內容不同 → 409
     4. ApproverResolver.resolve：指定 → 主管 → 無（409 NO_VALID_APPROVER）
     5. findOverlapping → 409 LEAVE_OVERLAP
     6. insertIfNew（ON CONFLICT DO NOTHING RETURNING id）
     7. LeaveAuditLog SUBMITTED（同交易）
 → 201 { replayed: false, request }，或 200 { replayed: true, request }
 → LeaveForm：onDone → 新表單（新的 requestId）、重新載入清單
```

### 4.2 核准

```
DecisionActions「確認核准」
 → POST /api/leave/approvals/{id}/approve { expectedVersion }
 → LeaveRequestService.approve
     1. 角色是 EMPLOYEE → 404
     2. acquireFor(actor)
     3. findByIdAndApproverId(id, 我) → 不是我的 → 404
     4. 不是 PENDING → 409；版本不符 → 409
     5. decideIfPending：where id, version, status = 'PENDING'
     6. 歷程 APPROVED
```

### 4.3 改派

```
ReassignAction「確認改派」
 → POST /api/admin/leave/requests/{id}/reassign { approverId, expectedVersion, reason }
 → LeaveAdminService.reassign
     1. ADMIN、原因必填
     2. acquireFor(actor)
     3. 讀申請
          申請人是自己 → 403
          不是 PENDING → 409
          版本不符 → 409
          新審核人是申請人 → 400
          與目前相同 → 400
          新審核人無效 → 409
     4. reassignIfPending：approver_id 換人、source = REASSIGNED、version + 1
     5. 歷程 REASSIGNED（原、新審核人）
```

改派後原審核人的 `findByIdAndApproverId` 查不到這筆，所以讀取與核准都是 404。

## 5. 核心程式解說

**5.1 半開區間的重疊條件**
```java
and r.startTime < :endTime and r.endTime > :startTime
```
- `[09:00, 12:00)` 與 `[12:00, 13:00)`：`09:00 < 13:00` 為真，但 `12:00 > 12:00` 為假，所以不重疊。
- 只查 `PENDING`、`APPROVED`：退回與撤回不占時段。

**5.2 requestId 重送：先比內容，再決定**
```java
LeaveRequest existing = requestRepository.findByEmployeeIdAndRequestId(me, body.requestId()).orElse(null);
if (existing != null) {
    if (!existing.sameContentAs(s)) throw … "REQUEST_ID_CONFLICT" …;   // 不覆寫原申請
    return new SubmitResult(true, views.request(existing));            // 不重新判斷審核人或重疊、不寫歷程
}
```
- 查詢一定帶 `employee_id`：requestId 不能用來探測別人的申請。
- 已撤回的原申請也原樣回傳，不會因為「重送」而復活。

**5.3 取鎖後重新確認操作者**
```java
public void acquireFor(EmployeeUserDetails actor) {
    acquire();                                                         // 可能在這裡等待
    if (accountStatusService.check(actor) != AccountState.ACTIVE) {    // 等待期間被停用或改角色？
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", …);
    }
}
```
- `ActiveAccountFilter` 在請求一開始就檢查過。但請求可能接著在鎖前排隊，排隊期間管理員把他降級了，所以取到鎖後要再看一次。
- 資料庫故障時 `check` 會丟出例外，回 500，不會被當成未登入。

**5.4 條件 UPDATE 是第二道防線**
```sql
update leave_request set status = :toStatus, … version = version + 1
 where id = :id and version = :expectedVersion and status = 'PENDING'
```
即使 Service 的檢查被誤刪，終態也不會被改回來，過期版本也不會覆寫。變異驗證「忽略版本」時，必須兩層都移除，測試才會失敗。

## 6. 狀態機、角色與資料範圍

```
PENDING ──核准（目前審核人）──► APPROVED
PENDING ──退回（目前審核人，原因必填）──► REJECTED
PENDING ──撤回（申請人）──► WITHDRAWN
PENDING ──改派（ADMIN；狀態不變、版本 +1）──► PENDING
```

| 問題 | 誰回答 | 本批 |
|---|---|---|
| 能做哪一類操作（角色） | `Role` | EMPLOYEE 沒有審核清單；ADMIN 才有管理 API |
| 能操作誰的資料（資料範圍） | SQL 條件 | 本人：`employee_id = 我`；審核：`approver_id = 我`；管理：ADMIN 全部可讀 |

MANAGER 不會因為是主管而看到部屬的出勤或工時。ADMIN 能讀所有申請，但不能核准未指派給自己的申請。

## 7. 主管關係 vs 指定請假審核人

| | 直屬主管（T05 前置） | 指定請假審核人（T05） |
|---|---|---|
| 存在哪裡 | `employee.manager_id` | `leave_approver_setting.approver_id` |
| 規則 | 不可循環、必須有效 | 只要求有效、不是本人 |
| 用途 | 組織關係；請假的預設審核人 | 只決定「誰審核這個人的請假」 |
| Max → Iris | 不能：Iris 的主管是 Max，會形成循環 | 可以：不影響組織 |

## 8. 交易、鎖與歷程

- **鎖範圍**：組織寫入與請假寫入共用一把鎖，代價是彼此排隊，例如很多人同時送假會一個接一個處理。每筆交易只做少量主鍵與索引查詢，不呼叫外部服務。
- **不取這把鎖的操作**：登入、打卡、工時與查詢。但它們仍可能因為其他列鎖而等待。
- **歷程**：與業務修改在同一個交易。歷程寫入失敗會丟出例外，整筆回滾（LA-01～LA-03）。觸發器擋 UPDATE 和 DELETE。
- **沒有用排除限制保證重疊**：PostgreSQL 的 `EXCLUDE USING gist` 是資料庫層最強的保證，但需要 `btree_gist` 擴充（D-73）。本批改以鎖內查詢保證，並以並行測試與變異驗證證明。

## 9. 測試證據

### 9.1 本次執行

| 項目 | 指令 | 結果 |
|---|---|---|
| 後端全部 | `backend> .\mvnw.cmd -B test` | **310／310 通過**（282 → 310：請假 27、O-09b 1） |
| 請假後端 | `-Dtest=com.renewflow.eip.leave.*Test` | `LeaveIntegrationTest` 17、`LeaveConcurrencyIntegrationTest` 5（各 8 輪）、`LeaveAuditRollbackIntegrationTest` 3、`LeaveSchemaTest` 2 |
| 變異驗證 8 項 | 改壞 → 只跑對應測試 → 還原 | 全部使測試失敗（見下表）；還原後重跑全套 |
| 前端 | `npm run lint`、`npm run build` | 通過（oxlint 結束碼 0、沒有警告輸出） |
| E2E `leave.mjs`（隔離堆疊） | `npm run e2e:leave` | **56／56** |
| E2E 全部 12 支 | 逐支執行 | 見 9.3 |
| 開發庫 V7 | 前後 md5 比對 | 既有 11 張表相同；新表 0 筆 |
| 開發環境唯讀檢查 | STMAX 6 帳號登入，只讀預計審核人與入口 | 13／13；沒有任何寫入請求 |

| 改壞的保護 | 失敗的測試（實際訊息摘要） |
|---|---|
| 移除重疊檢查 | `overlapRules`（11:30-12:30 預期 409 實際 201）、`concurrentOverlappingSubmits` |
| 送出不取組織鎖 | `concurrentOverlappingSubmits`、`concurrentDuplicateSubmits`（重複送出變成 500） |
| 審核人範圍改成任何人 | `dataScope`、`reassignment`（原審核人預期 404 實際 200） |
| 允許 ADMIN 改派自己的申請 | `noSelfApproval`（預期 403 實際 200） |
| 忽略版本（Service＋SQL） | `staleVersion` |
| requestId 不比對內容 | `requestIdSemantics`（預期 409 實際 200） |
| 吞掉歷程寫入失敗 | `transitionsRollBack`（預期 500 實際 200） |
| 取鎖後不重新確認操作者 | `actorRecheckedAfterLock`、`lastAdminIsProtected`（預期 401 實際 409，代表第二道防線 LAST_ADMIN 仍擋下） |

### 9.2 沿用的證據

- `ActiveAccountFilter`（停用或改角色後舊 Session 401）是 T03 的機制，本批沒有修改，L-11 再驗證一次。
- T05 前置的組織測試全部包含在 310 項中重跑，不是沿用。

### 9.3 E2E 全部重跑

全部通過（開發後端已套用 V7 後執行）：smoke 18、logout 23、password 10、attendance 37、corrections 74、geofence 59、races 22、timesheets 49、timesheets-review 24、timesheets-retry 25、organization 54、leave 56。
- smoke、logout、password 對開發環境（5173／8080）執行：只做登入／登出與讀取畫面，不送單、不打卡。smoke 與 attendance 的導覽檢查隨功能更新：「請假」已啟用，只剩「必要設定」未啟用；首頁沒有「尚未啟用」卡片。
- 其他 9 支在隔離堆疊（測試庫＋本次專用帳號）執行；`leave.mjs` 的所有假單都只存在測試庫。
- 上一輪記錄的偶發登入逾時，本輪沒有出現，原因仍未確定。

### 9.4 沒有驗證的項目

- 大量申請時的鎖排隊延遲沒有量測。
- 手機只用模擬尺寸，沒有實機。
- 並行測試證明「每輪只有一個成功」，但不能窮舉所有交錯順序。LC-05 兩種先後都可能出現；本次沒有記錄每輪實際走了哪一邊。
- 跨重新整理的草稿保存：本批不做。

## 10. 取捨、限制與面試重點

**限制**：
- 沒有額度、年資、法規計算、排班、午休扣除；
- 不修改出勤或工時；
- 單一關審核，沒有代理人、通知、附件；
- 已核准的申請不能取消；
- 指定審核人失效時拒絕送出（不自動退回採用主管，A-83）。

**面試重點**：
1. **requestId、版本號、鎖各解決什麼？** 分別是重複提交、過期畫面、跨多筆規則（重疊、審核人有效）。
2. **為什麼不存「審核人規則」，而是存審核人 ID 快照？** 送出後組織可能改變，審核責任不能在背後被偷偷換掉。換人要經過 ADMIN 改派，並留下歷程。
3. **為什麼查不到與沒權限都回 404？** 回 403 會洩漏「這筆存在」。
4. **Filter 檢查過了，為什麼 Service 取鎖後還要再查一次帳號？** 檢查與使用之間有時間窗（TOCTOU）。等鎖讓這個時間窗變長。
5. **半開區間的好處？** 相鄰時段不算重疊，邊界規則簡單一致。
6. **前端「結果不明」時為什麼不能自動重送或清空？** 伺服器可能已經建立。只能用同一個 requestId 送同一份內容確認，或讓使用者看過警告後明確放棄。

## 練習（不附答案）

如果之後要支援「已核准申請的取消申請」（員工提出取消，原審核人核准後才生效），你會怎麼設計狀態、資料表與權限？「核准取消」與「同時有人改派」會怎麼競爭？要用哪一種保護？
