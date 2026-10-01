# T03.1：出勤更正、重新打卡申請與管理權限——技術文件

產品：TeamPortal（企業員工入口平台）。開發方法：RenewFlow Engineering Method v2。

狀態標記：**已驗證**＝作者在本機實際執行並看到結果（證據見第 9 節）；**尚未經對話助理審查**。

| 功能 | 狀態 |
|---|---|
| 員工申請重新打卡（RESET_TODAY）、申請更正／補登（CORRECT_TIME）、撤回 | 已驗證 |
| 管理員核准／退回、直接更正、作廢今日、查詢、歷程 | 已驗證 |
| 管理權限（ADMIN）由後端驗證；職責分離 | 已驗證 |
| 作廢＋新增的重新打卡、部分唯一索引、版本比對、只處理一次 | 已驗證（含真實多執行緒並行測試） |
| 修改＋狀態＋歷程同一交易；歷程只新增 | 已驗證（含注入失敗的回滾測試、資料庫觸發器） |
| `developer` 開發帳號（僅 dev、獨立密碼、無預設） | 已驗證（測試庫）；開發庫已實際建立 |
| E2E 資料庫保護提前到 migration 之前 | 已驗證（替身＋臨時資料庫的真實啟動） |
| 前端：申請、我的申請、歷程、出勤管理、自動重新取得、行動版 | 已驗證（Edge；行動版為模擬尺寸） |

版本：repository `https://github.com/hsieh634/java-eip`、分支 `main`，審查基準是標題為 `feat: T03.1 ...` 的 commit（完整 SHA 在交付報告）。規格：`docs/renewflow/t03-1/`。

---

## 1. 快速摘要

- **用途**：T03 的規則「每人每日一組、不能修改」在誤按或漏打時會卡住員工。T03.1 提供**受控、可追溯**的出路：員工提出申請 → 管理員審核 → 系統以**作廢＋新增**或**條件更正**套用，並寫下歷程。
- **最重要的規則**：
  1. 管理權限由後端驗證（URL 規則＋Service 檢查＋最新角色），不是隱藏按鈕。
  2. 原始紀錄不硬刪：作廢保留原時間；每人每日最多一筆**有效**紀錄（部分唯一索引）。
  3. 申請只處理一次（`WHERE status = 'PENDING'` 的條件更新）。
  4. 核准不覆蓋較新的資料（`version` 比對）。
  5. 修改、狀態、歷程同一交易；歷程只能新增。
  6. 只有預期的衝突是 409；系統故障維持 5xx。

## 2. 操作入口

| 誰 | 在哪裡 | 做什麼 |
|---|---|---|
| 員工 | 首頁或「出勤打卡」頁的今日卡片 | 申請重新打卡（填原因）、撤回、重新整理 |
| 員工 | 「出勤打卡」頁 | 申請更正／補登（選日期、上下班時間、原因）、我的申請、更正歷程、作廢紀錄標示 |
| ADMIN | 左側「出勤管理」（只對 ADMIN 顯示） | 申請審核（核准／退回，有確認視窗）、員工出勤查詢與更正、作廢今日、操作歷程 |

完整的逐步操作在 README 第 9 節。

## 3. 實際程式碼定位

| 位置 | 內容 |
|---|---|
| `backend/.../db/migration/V3__attendance_corrections.sql` | 版本、作廢欄位；部分唯一索引；申請表；歷程表＋觸發器 |
| `attendance/AttendanceRepository` | `insertIfAbsent`（衝突目標加 `where voided_at is null`）、`clockOutIfOpen`（維護 `version`）、`voidIfCurrent`、`correctIfCurrent`、`insertManualIfAbsent` |
| `attendance/AttendanceRequestRepository` | `insertPending`（`ON CONFLICT ... WHERE status = 'PENDING' DO NOTHING RETURNING id`）、`review`、`cancel` |
| `attendance/AttendanceRequestService`／`Controller` | 員工端：提出、撤回、列出、歷程 |
| `attendance/AttendanceAdminService`／`Controller` | 管理端：`approve`、`reject`、`correctDirectly`、`voidToday`、查詢 |
| `attendance/AttendanceAuditLog`、`AttendanceHistory*`、`AuditEntry` | 歷程的單一寫入入口 |
| `attendance/AttendanceViews` | DTO 轉換與「過時」判斷 |
| `attendance/AttendanceTimes` | 台北當地時間 → 時間點，與時間驗證 |
| `shared/security/SecurityConfig` | `/api/admin/**` → `hasRole('ADMIN')` |
| `identity/DevSeedRunner` | `developer`（ADMIN，`RENEWFLOW_DEV_DEVELOPER_PASSWORD`） |
| `backend/src/test/.../support/E2eFlywayConfig` | E2E 的 migration 前資料庫檢查 |
| `frontend/src/components/TodayAttendanceCard.tsx`、`CorrectionForm.tsx`、`RequestsPanel.tsx`、`HistoryList.tsx`、`ConfirmDialog.tsx` | 員工端畫面與確認視窗 |
| `frontend/src/pages/AdminAttendancePage.tsx`、`AttendancePage.tsx` | 管理頁、出勤頁 |
| `frontend/src/attendance/useAutoRefresh.ts`、`useMyRequests.ts`、`useTodayAttendance.ts` | 回到視窗、切頁、跨台北日期、待審輪詢時重新查詢 |

## 4. 完整呼叫流程：核准重新打卡

```
管理員按「核准」→ 確認視窗（員工、日期、原值、預計結果）→「確認核准」
 → POST /api/admin/attendance/requests/{id}/approve（帶 X-XSRF-TOKEN）
 → Security Filter：CSRF → ActiveAccountFilter（帳號仍有效、角色仍是 ADMIN）→ hasRole('ADMIN')
 → AttendanceAdminController.approve → AttendanceAdminService.approve（@Transactional，一個交易）
    1. requireAdmin（縱深防禦）；讀申請；申請人＝自己 → 403 SELF_REVIEW_FORBIDDEN
    2. UPDATE attendance_request SET status='APPROVED' ... WHERE id=? AND status='PENDING'
         0 筆 → 409 REQUEST_NOT_PENDING（已被處理）     ← 這一步也鎖住申請列
    3. 申請日 ≠ 台北今日 → 409 RESET_DATE_PASSED（整個交易回滾，申請仍是 PENDING）
    4. UPDATE attendance SET voided_at, voided_by, version=version+1 WHERE id=? AND version=? AND voided_at IS NULL
         0 筆 → 409 REQUEST_STALE（資料已被改過；回滾）
    5. AttendanceAuditLog.record(...)（原值、申請值、修改後、操作者、伺服器時間）；失敗 → 例外 → 全部回滾
 → 200 申請（APPROVED）
員工回到視窗（focus／visibilitychange）或每 30 秒的待審輪詢 → 重新查詢 → 今日 NOT_STARTED
 → 員工按「上班打卡」→ INSERT ... ON CONFLICT (employee_id, work_date) WHERE voided_at IS NULL DO NOTHING → 1（作廢的舊紀錄不在索引內）
```

## 5. 核心程式解說

### 5.1 權限：三層把關

```java
// SecurityConfig
.requestMatchers("/api/admin/**").hasRole("ADMIN")
.requestMatchers("/api/**").authenticated()
```

- **第一層**：URL 規則。順序重要——具體規則在前，`/api/**` 在後。
- **第二層**：`ActiveAccountFilter`（T03）在授權之前回資料庫確認角色；admin 被降級後，舊 Session 的下一個請求 401（測試 C-19）。
- **第三層**：`AttendanceAdminService.requireAdmin` 再檢查一次。變異檢查：只拿掉 URL 規則時，Service 仍擋得住；兩層都拿掉，授權測試才失敗——這正是縱深防禦的意義。
- **職責分離**：`loadForReview` 拒絕審核自己的申請；`requireNotSelf` 拒絕更正自己的出勤（403，code 區分 `SELF_REVIEW_FORBIDDEN`／`SELF_CORRECTION_FORBIDDEN`，與一般 `FORBIDDEN` 不同）。
- MANAGER 不自動是管理員（沒有角色階層）；前端只在 ADMIN 時顯示「出勤管理」，但這只是體驗——測試以員工、主管、匿名**直接呼叫**每個管理端點。

### 5.2 狀態轉移：申請只能處理一次

```
PENDING ──核准──▶ APPROVED
   │ ──退回──▶ REJECTED（原因必填）
   └ ──撤回──▶ CANCELLED（僅本人）
```

不是「先讀狀態、判斷、再寫入」，而是**把判斷放進 UPDATE 的 WHERE**：

```sql
update attendance_request set status = :status, reviewer_id = :reviewerId, reviewed_at = :now, ...
 where id = :id and status = 'PENDING'
```

8 個並行核准：第一個鎖住該列並提交，其餘在列鎖上等待，取得鎖時重新檢查 `status = 'PENDING'` 已不成立 → 更新 0 筆 → 409 `REQUEST_NOT_PENDING`。測試 C-15 驗證恰 1 個 200、7 個 409、只作廢一次、歷程只有一筆核准。

### 5.3 交易：修改、狀態、歷程一起成敗

`approve` 是一個 `@Transactional` 方法：申請狀態 UPDATE → 出勤 UPDATE／INSERT → 歷程 INSERT。任何一步丟出 `RuntimeException`（包括 409 的業務例外與歷程寫入失敗），Spring 讓整個交易回滾。

- 為什麼先改申請狀態、再改出勤：先鎖住申請列，避免兩個核准同時改出勤；失敗時一起回滾，申請仍是 PENDING。
- 測試 C-23 用 `@MockitoSpyBean` 讓 `AttendanceAuditLog.record` 丟例外：核准回 500、申請仍 PENDING、紀錄沒作廢、版本沒變、沒有半套歷程；修好後同一申請可正常核准。提出申請、直接更正、直接作廢也各有一個回滾測試。
- 「歷程寫入失敗」不是業務衝突，所以是 500，不是 409。

### 5.4 版本與鎖：不覆蓋較新的資料

每筆出勤有 `version`，**所有**寫入都 `+1`——包括 T03 的原生下班 SQL（若漏改，依舊版本提出的申請就偵測不到「員工後來又下班了」；變異檢查證實：拿掉下班的 `version + 1`，過時與並行測試就失敗）。

```sql
update attendance set clock_in_at = :clockIn, clock_out_at = :clockOut, corrected_at = :now,
       updated_at = :now, version = version + 1
 where id = :id and version = :version and voided_at is null
```

- 申請記住 `target_version`；核准時版本不同 → 0 筆 → 409 `REQUEST_STALE`，申請維持 PENDING，畫面標示「已過時」，員工撤回後依新資料重新提出。
- 這是**樂觀鎖**的概念（以版本偵測衝突），但用一條條件 UPDATE 實作，不需要長時間持有鎖，也不用 JPA `@Version`（因為寫入走原生 SQL）。
- 並行「核准 vs 員工下班」（C-15，各 6 輪）：結果只會是「核准成功＋下班 409」或「核准過時＋下班成功」，絕不會兩者都成功；更正版本則驗證「上班時間被改 ⇔ 核准成功」且下班時間不會被覆蓋。
- 管理員直接更正也帶 `expectedVersion`；6 個並行直接更正只有 1 個成功（C-20）。

### 5.5 部分唯一索引：一筆有效、多筆作廢

```sql
alter table attendance drop constraint uq_attendance_employee_work_date;
create unique index uq_attendance_active_employee_work_date
    on attendance (employee_id, work_date) where voided_at is null;
```

- 作廢的紀錄不在索引內，所以同一天可以有多筆作廢歷史，但只能有一筆有效紀錄（C-27）。
- **陷阱**：`INSERT ... ON CONFLICT` 的衝突目標必須與索引**完全相符**：`on conflict (employee_id, work_date) where voided_at is null`。只改 migration 不改 SQL，PostgreSQL 會說找不到可用的唯一限制（變異檢查：拿掉 `where voided_at is null`，T03 的打卡測試大量失敗）。
- 申請表同樣用部分唯一索引 `WHERE status = 'PENDING'` 保證「同員工同日最多一筆待審」，並以 `ON CONFLICT ... DO NOTHING RETURNING id` 建立——並行 8 個提出只會有 1 筆（C-03）。
- 所有「今日／下班／未完成提醒」查詢都加上 `voided_at is null`：作廢的紀錄不會被當成「工作中」，也不會觸發昨日未完成提醒（C-09）。

### 5.6 操作歷程：只新增

- 欄位：申請人、日期、操作、原因、目標紀錄與版本、原值、申請值、**實際修改後**的值、操作者、審核結果、伺服器時間（來自 `Clock`）。
- 操作者與時間由後端產生；請求本文中的同名欄位被忽略（測試以偽造的 `employeeId`、`status`、`reviewerId`、`createdAt` 驗證）。
- 沒有修改／刪除歷程的 API；資料庫觸發器 `trg_attendance_history_immutable` 擋 UPDATE／DELETE（C-24）。測試庫的清理因此改用 `TRUNCATE`（觸發器不會在 TRUNCATE 時觸發）。
- 外鍵全部 `ON DELETE RESTRICT`：有歷程的員工（含操作者）不能被刪除。

### 5.7 E2E 保護提前到 migration 之前

- 之前：`E2eSeedRunner`（`ApplicationRunner`）在應用程式**啟動完成後**才檢查資料庫，而 Flyway 早在啟動過程中套用了 migration——設定錯誤時會「先寫、再拒絕」。
- 現在：`E2eFlywayConfig` 提供 `FlywayMigrationStrategy`，在 `migrate()` **之前**用 Flyway 自己的 datasource 呼叫 `getCatalog()`，套用 `TestDatabaseGuard`；不通過就丟例外，`migrate()` 不會被呼叫，種子寫入也不會發生。
- 驗證（`E2eStackGuardTest`）：替身確認拒絕時 `migrate()`／種子寫入都沒被呼叫；再建立**臨時的獨立資料庫**，用 e2e 設定真的啟動應用程式——指向不被允許的庫時啟動失敗、且該庫**沒有任何資料表**；對照組（允許時）會建表並寫入 4 位種子員工。兩個臨時庫在測試結束時刪除。完全沒有碰開發庫。
- 小插曲（真實踩到的坑）：`SpringApplicationBuilder.properties(...)` 是**預設值**（優先權最低），會被 `application-e2e.yml` 蓋掉；覆寫必須用命令列參數 `run("--key=value")`。

### 5.8 前端：不假設、會重新查詢

- 申請送出後只顯示「待審核」，按鈕不開放；核准結果靠**重新查詢**呈現：視窗 focus／分頁可見（`useAutoRefresh`，兩次至少隔 3 秒）、有待審申請時每 30 秒輪詢（`useMyRequests`）、每 10 秒檢查「台北的今天」是否換日（`useTodayAttendance`）。不用 WebSocket。
- 更正表單每次選日期都向後端查該日**目前**資料來預填（E2E 曾抓到：用畫面上可能過期的列表預填會漏掉剛打的下班時間），並清楚區分原時間與申請時間。
- 同一日期可能有多筆紀錄，列表 key 用紀錄 `id`。
- 錯誤：401 回登入頁；5xx／網路中斷顯示「結果未確認，請重新整理確認」，不導回登入頁；4xx 顯示後端訊息。
- 管理操作都有確認視窗，列出員工、日期、原值、預計結果；退回與直接更正必填原因。

## 6. 設計理由與取捨

| 決定 | 原因 | 替代方案與成本 |
|---|---|---|
| 作廢＋新增，而非刪除或覆寫 | 保留證據；新紀錄仍走正常打卡流程與限制 | 硬刪：失去原始資料；覆寫：看不出原值 |
| 部分唯一索引 | 資料庫保證「一筆有效」，允許多筆作廢 | 應用程式檢查：有競態；觸發器：較複雜 |
| 條件 UPDATE＋version | 單一 SQL 內完成檢查與寫入，不持鎖等待使用者 | 悲觀鎖：較重；JPA `@Version`：寫入是原生 SQL |
| 先轉申請狀態再改出勤 | 鎖住申請列，失敗一起回滾 | 先改出勤：並行核准可能重複套用 |
| 歷程同交易＋觸發器 | 沒有「改了沒紀錄」，也無法事後竄改 | 非同步寫歷程：可能遺失 |
| 三層權限檢查 | 任一層出錯仍有防線 | 只靠前端：等於沒有權限 |
| RESET_TODAY 只限今日 | 「重新打卡」只對當下有意義；過去的日期用時間更正 | 允許任意日期：語意不清 |
| 不用 WebSocket | 重新查詢的觸發已足夠，不需新套件 | 即時推播：本輪過度設計 |

## 7. 資料模型與 SQL

表格與約束見 `docs/renewflow/t03-1/03-data-model.md`。關鍵 SQL 皆為 `AttendanceRepository`／`AttendanceRequestRepository` 中的原生語句（本文第 5 節已列出，為實際程式內容）；Hibernate 產生的查詢（例如 `findByEmployeeIdAndWorkDateAndVoidedAtIsNull`）形如 `select ... from attendance where employee_id=? and work_date=? and voided_at is null`——**此句依方法名稱推估，本輪未另外截取 SQL 日誌**。

## 8. 安全與錯誤

| 情境 | 結果 | 測試 |
|---|---|---|
| 匿名呼叫任何管理端點 | 401 | C-18（後端）、E2 |
| EMPLOYEE／MANAGER 呼叫任何管理端點 | 403 `FORBIDDEN` | C-18、E2 |
| 管理員被降級後的舊 Session | 401 | C-19 |
| 審核自己的申請／更正自己的出勤 | 403 `SELF_REVIEW_FORBIDDEN`／`SELF_CORRECTION_FORBIDDEN` | C-17、C-20、C-21 |
| 原因空白、格式錯誤 | 400 `VALIDATION_FAILED`／`BAD_REQUEST` | C-01、C-16 |
| 時間不合理（未來、下班早於上班、無變更） | 400 `INVALID_REQUEST` | C-11、C-20 |
| 別人的申請 | 404 `REQUEST_NOT_FOUND` | C-05 |
| 重複待審、已處理、過時、跨日、版本不符、非今日作廢 | 409（各自的 code） | C-03、C-12、C-13、C-14、C-20、C-21 |
| 缺 CSRF | 403 `CSRF_REJECTED` | 員工端測試 |
| 歷程寫入失敗 | 500，全部回滾 | C-23 |

## 9. 驗證證據（本機實際執行）

環境：Windows 11、Temurin 21.0.12、Maven 3.9.12、Node 24.14.0、PostgreSQL 17（Docker）、Edge。

| 項目 | 指令 | 結果 |
|---|---|---|
| 後端全部測試 | `cd backend; .\mvnw.cmd test` | **151 個，0 失敗、0 略過**（T03 的 94 個全保留；新增 57 個：`AttendanceAdminIntegrationTest` 18、`AttendanceRequestIntegrationTest` 12、`AttendanceCorrectionsSchemaTest` 8、`AttendanceCorrectionConcurrencyIntegrationTest` 5、`AttendanceAuditRollbackIntegrationTest` 4、`E2eStackGuardTest` 5、`DevSeedRunnerTest` +5） |
| 前端 | `npm run build`、`npm run lint` | 通過，0 警告 |
| E2E（dev，唯讀） | `e2e:smoke`／`e2e:logout`／`e2e:password` | 18/18、23/23、10/10 |
| E2E（隔離堆疊） | `e2e:attendance`／`e2e:corrections` | 37/37、**52/52**（申請 → 核准 → 重新打卡完整流程、補登、退回、撤回、撤銷下班、直接作廢、非管理員 403／匿名 401、行動版、回到視窗與跨台北日期的重新查詢） |
| 變異檢查 | 暫時破壞後跑對應測試，再還原 | 更正 SQL 拿掉版本條件 → 3 個失敗；`ON CONFLICT` 拿掉索引條件 → 大量失敗；下班不加版本 → 4 個失敗（並行測試抓到「核准與下班都成功」）；拿掉兩層管理權限 → 2 個失敗；拿掉自審檢查 → 1 個失敗；拿掉 E2E migration 前檢查 → 3 個失敗 |
| V3 升級 | 測試 `v3UpgradesExistingV2Database`；重啟開發後端 | 通過；開發庫由 `1,2` 升級為 `1,2,3`。你今天 12:26:23／12:26:25 的紀錄仍是有效紀錄（`version=0`、未作廢、`source=CLOCK`） |
| 開發資料未被碰觸 | 測試與 E2E 後查開發庫 | 出勤 1 筆（你的）、申請 0、歷程 0 |

**未執行／限制**
- 行動版為 Edge 的模擬尺寸與觸控事件，不是實機；只驗了 Edge。
- 「跨台北日期重新查詢」在瀏覽器端以位移前端時間驗證觸發；伺服器端的跨日規則（RESET_DATE_PASSED、昨日提醒）由後端可控時鐘驗證。
- 並行測試在 PostgreSQL 預設隔離層級（READ COMMITTED）下成立；沒有壓力測試。
- `developer` 的密碼重設流程只在測試庫驗證；開發庫的 `developer` 已由啟動流程建立（密碼在 `.env`），沒有執行重設。
- 沒有多層審核、通知、登入失敗鎖定；歷程不涵蓋一般打卡。
- 隔離測試庫中會留下各次 E2E 的專用員工與申請（測試庫用途，整合測試啟動時會清空重建）。

## 10. 面試重點與練習

**Q1. 角色檢查只放在前端（隱藏按鈕）可以嗎？** 不行。任何人都能直接呼叫 API。後端必須驗證，最好多層：URL 規則、Service 檢查、以及確認角色是最新的（避免降級後的舊 Session）。追問：為什麼 MANAGER 不自動包含 ADMIN？（需求明確分開；角色階層會擴大權限。）

**Q2. 如何保證同一筆申請只被核准一次？** 把狀態判斷放進 UPDATE 的 WHERE（`status = 'PENDING'`），以受影響筆數判斷；資料庫列鎖讓並行請求依序重新檢查。追問：為什麼不能先 SELECT 再 UPDATE？（兩個請求都可能讀到 PENDING。）

**Q3. 樂觀鎖與悲觀鎖差在哪？本專案用哪一種？** 悲觀鎖先鎖再改（`SELECT ... FOR UPDATE`），適合衝突頻繁；樂觀鎖用版本偵測衝突，衝突時拒絕。本專案用「版本＋條件 UPDATE」的樂觀做法，因為審核可能隔很久，不能一直持鎖。追問：使用 `version` 時，哪些寫入路徑必須同步加一？（所有的，包括原生 SQL。）

**Q4. 部分唯一索引適合什麼情境？** 「在某個條件下才需唯一」，例如只有有效紀錄唯一、只有待審申請唯一。追問：`ON CONFLICT` 要注意什麼？（衝突目標的欄位與 WHERE 條件必須與索引相符。）

**Q5. 為什麼歷程要和修改在同一個交易？** 避免「改了資料卻沒紀錄」或「有紀錄卻沒改」。追問：歷程要不要防竄改？（應用層不提供修改 API，資料庫層用觸發器或權限擋 UPDATE／DELETE。）

**Q6. 401、403、404、409 在本功能分別代表什麼？** 未登入或身份失效；已登入但不允許（含審核自己）；不存在或不是你的（不洩漏存在與否）；狀態衝突（已處理、過時、重複待審）。

**Q7. 「刪除資料重來」與「作廢再新增」差在哪？** 後者保留證據、可追溯、可稽核；前者無法回答「原本是幾點」。

**Q8. 測試如何證明並行安全？** 用閘門讓多個執行緒同時送出，對真實資料庫競爭，斷言「不變條件」（恰一個成功、資料不被覆蓋），並做變異檢查證明測試會在錯誤實作下失敗。

### 練習（只有這一題）

**背景**：`AttendanceAdminService.approve` 的順序是：(1) 把申請從 PENDING 改為 APPROVED；(2) 依版本作廢或更正出勤；(3) 寫歷程；三者在同一個 `@Transactional` 內。另外，`AttendanceRepository.clockOutIfOpen` 會 `version = version + 1`。

**問題**：假設有人把 `approve` 的 (1) 與 (2) 對調——先依版本改出勤、再把申請改為 APPROVED，並且把 `@Transactional` 拿掉（每個 Repository 呼叫各自提交）。請描述兩個管理員**同時**核准同一筆 RESET_TODAY 申請時，可能出現的最壞結果是什麼（出勤資料、申請狀態、歷程各會變成怎樣）？再說明：只把 `@Transactional` 加回來、但不把順序換回來，能不能完全避免問題？為什麼？

請把你的回答帶回 ChatGPT 對話，由它批改並決定下一題。
