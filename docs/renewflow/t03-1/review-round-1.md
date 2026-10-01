# T03.1 審查修正＋打卡申請操作調整（第 1 輪複審）

審查基準：`f16b5f0e5c6d9a6dc2d3ac9aa5a844d032bc4763`。需求已與使用者確認。**不開始 T04。**
狀態：已實作、作者自測完成；**等待 ChatGPT 複審**。證據見 `docs/learning/T03-1-review-fixes.md`。

## 1. 需求

### R1 直接更正必須比對「紀錄 ID＋版本」
- 問題：舊的 `DirectCorrectionRequest` 只帶 `employeeId／workDate／expectedVersion`，後端以「當日的有效紀錄」補上 ID。紀錄 A（版本 0）被作廢、員工重新打卡產生 B（版本也是 0）後，舊表單會誤改 B。
- 修正：
  - 更正既有紀錄必須同時帶 `attendanceId`＋`expectedVersion`；補登新紀錄兩者都不帶。只帶其一 → 400 `INVALID_REQUEST`。
  - 後端確認紀錄存在、屬於該員工、日期相同、仍有效、版本相同，否則 409 `RECORD_CHANGED`。
  - 最終仍以 `UPDATE ... WHERE id = ? AND version = ? AND voided_at IS NULL` 原子更新。
  - 被拒絕時，出勤、版本、歷程都不變。

### R2 相同條件再按「查詢」也要重新取得
- 每次有效的「查詢」都重新讀取出勤與歷程；保留 loading、錯誤與重試；切換條件時仍丟棄過期回應。

### R3 只清理本次成功建立的臨時資料庫
- 每次執行產生獨立、合法的名稱（允許組符合 `*_test`）。
- 不先刪既有資料庫；只清理「本次成功建立」的庫；名稱碰撞、建立失敗或部分成功都不刪別的庫。
- 開發庫與測試庫名稱取自實際設定；不得與它們同名。
- 先關閉本次應用程式與連線，再清理。

### 新：員工的三種申請操作
| 操作 | 底層 | 適用 | 核准後 |
|---|---|---|---|
| A. 申請撤銷下班打卡 | `CORRECT_TIME`（希望上班＝目前上班、希望下班＝空） | 今日有效紀錄**已有下班時間** | 同一筆紀錄只清空下班時間、版本 +1、恢復 WORKING；不作廢、不新增紀錄 |
| B. 申請整組作廢並重新打卡 | `RESET_TODAY` | 今日有有效紀錄（有上班沒下班也可以） | 舊紀錄作廢（保留原時間），今日恢復 NOT_STARTED，可重打一組 |
| C. 申請更正／補登時間 | `CORRECT_TIME` | 今日或過去日期；沒有紀錄可補登 | 依申請值更正或建立 |

- 不提供「只取消上班、保留下班」：系統不允許沒有上班卻有下班的有效紀錄。
- 系統依申請內容辨識類別並回傳 `kind`：`RESET_TODAY`／`REVOKE_CLOCK_OUT`／`CORRECT_TIME`，清單、確認畫面與歷程都顯示對應名稱。

### 新：送出申請時核對快照
- 員工端申請帶上表單讀到的 `expectedAttendanceId`＋`expectedVersion`（兩者同時帶或同時不帶；只帶其一 → 400）。
  - 兩者都帶：當日有效紀錄必須正是該 ID 與版本，否則 409 `RECORD_CHANGED`（請重新確認）。
  - 兩者都不帶：表示「看到的是沒有紀錄」；若當日其實已有有效紀錄 → 409 `RECORD_CHANGED`。
  - RESET_TODAY 一定要帶（必須先有紀錄）。
- 今日查詢（`/api/attendance/me/today`）多回傳 `recordId`、`version`，供前端提出申請時使用。
- 核准時仍比對申請保存的目標 ID／版本（既有機制）。

## 2. 假設與決策

| 編號 | 內容 |
|---|---|
| A-23 | 撤銷下班沿用 `CORRECT_TIME`，不另建審核流程；以內容辨識類別（`kind`） |
| A-24 | 撤銷下班的上班時間以**完整精度**送出（含小數秒），核准後上班時間與原值完全相同 |
| D-27 | 直接更正以「ID＋版本」雙重比對；ID 由前端帶入且不會被替換 |
| D-28 | 申請的快照核對在「提出時」與「核准時」各做一次：前者擋住過期表單，後者擋住申請後的變動 |
| D-29 | 前端每次查詢遞增一個序號並納入載入 key，使相同條件也會重新請求；過期回應仍依 key 丟棄 |
| D-30 | 臨時資料庫由 `TemporaryDatabases` 管理：產生唯一名稱、記錄成功建立者、只清理已記錄者；以替身驗證碰撞與部分失敗 |

不需要新的 migration（V3 已有版本、作廢與申請欄位）。

## 3. API 變更

- `POST /api/admin/attendance/records/correct`：新增 `attendanceId`。更正＝`attendanceId`＋`expectedVersion`；補登＝兩者都省略。
- `POST /api/attendance/requests`：新增 `expectedAttendanceId`、`expectedVersion`；新錯誤 409 `RECORD_CHANGED`、400 `INVALID_REQUEST`（快照欄位不完整）。
- `AttendanceRequestResponse` 新增 `kind`；`HistoryEntryResponse` 新增 `requestKind`；`TodayAttendanceResponse` 新增 `recordId`、`version`。

## 4. 驗收情境

| 編號 | 情境 | 預期 | 驗證 |
|---|---|---|---|
| F-01 | R1：A 被作廢、員工重打產生 B（版本同為 0），以 A 的舊表單直接更正 | 409 `RECORD_CHANGED`；B 的時間、版本不變；沒有新歷程 | 自動（HTTP＋PostgreSQL） |
| F-02 | R1：只帶 attendanceId 或只帶 expectedVersion | 400 `INVALID_REQUEST` | 自動 |
| F-03 | R1：ID 屬於別的員工／別的日期 | 409 `RECORD_CHANGED`（不洩漏、不修改） | 自動 |
| F-04 | R1：同一筆紀錄版本衝突、並行直接更正 | 409；恰一個成功（保留既有測試） | 自動 |
| F-05 | R2：相同條件再按查詢，其間另一個 Session 改動資料 | 出勤與歷程都更新 | E2E |
| F-06 | R3：名稱碰撞、部分建立失敗、正常清理 | 只刪本次成功建立的庫 | 自動（替身） |
| F-07 | R3：真實啟動（臨時庫）拒絕與允許 | 拒絕：無資料表；允許：有資料表與種子 | 自動 |
| F-08 | 撤銷下班核准 | 同一 ID、上班時間完全相同、下班為 null、版本 +1、WORKING；之後可再下班 | 自動／E2E |
| F-09 | 沒有下班時間時申請撤銷下班 | 400（沒有變更）；畫面不提供此操作 | 自動／E2E |
| F-10 | 撤銷下班申請待審、撤回、退回 | 正式出勤不變 | 自動 |
| F-11 | 撤銷下班申請後紀錄被更正或重新打卡 | 核准 409 `REQUEST_STALE`；較新的紀錄保留 | 自動 |
| F-12 | 表單讀取後紀錄被替換，送出舊快照 | 409 `RECORD_CHANGED`，沒有建立申請 | 自動／E2E |
| F-13 | 整組作廢 vs 撤銷下班 | 前者作廢舊紀錄（2 筆，1 筆有效）；後者同一筆、無作廢；`kind` 與歷程可辨識 | 自動／E2E |
| F-14 | 只有上班沒下班時申請整組作廢 | 可提出、核准後 NOT_STARTED | 自動 |

## 5. Tasks

| # | 工作 | 狀態 |
|---|---|---|
| RR-1 | 規格與 tasks（本文件、API 文件、需求／假設／specification 增補） | ☑ 已實作 |
| RR-2 | R3 `TemporaryDatabases`＋替身測試；改寫真實啟動測試 | ☑ 作者自測完成 |
| RR-3 | R1 直接更正 ID＋版本（後端、DTO、前端、測試） | ☑ 作者自測完成 |
| RR-4 | 申請快照核對、`kind`、今日回應 `recordId／version` | ☑ 作者自測完成 |
| RR-5 | 前端三種操作、R2 重新查詢 | ☑ 作者自測完成 |
| RR-6 | 後端回歸測試、E2E 回歸（隔離堆疊） | ☑ 作者自測完成 |
| RR-7 | 學習文件、README、版本紀錄 | ☑ 已實作 |
| — | ChatGPT 複審 | ☐ 等待中 |
