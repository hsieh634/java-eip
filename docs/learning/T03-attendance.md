# T03：上下班打卡與本人出勤紀錄——技術文件

產品：TeamPortal（企業員工入口平台）。開發方法：RenewFlow Engineering Method v2。

狀態標記：**已驗證**＝作者在本機實際執行並看到結果（證據見第 9 節）；**尚未經對話助理審查**。本文件的所有內容都是開發代理的自述，等你帶回 ChatGPT 審查後才算「已審查」。

| 功能 | 狀態 |
|---|---|
| 上班／下班打卡、今日狀態、本人紀錄（預設本月、可選範圍） | 已驗證 |
| 並行重複上班／下班只有一個成功 | 已驗證（後端多執行緒整合測試，PostgreSQL READ COMMITTED） |
| 所有受保護 API 共用的帳號有效性檢查 | 已驗證 |
| 台北時區日期邊界、昨日未完成、跨日限制 | 已驗證（可控制的 Clock） |
| 前端：首頁卡片、出勤頁、失敗與手機處理 | 已驗證（瀏覽器自動化；手機為模擬尺寸，非實機） |
| 產品更名 TeamPortal | 已驗證（登入頁、頁籤標題、favicon、README／文件） |
| 開發帳號「只更新密碼雜湊」維護流程 | 已驗證（只在測試庫；沒有對你的開發帳號執行） |

> **T03.1 之後的變化**：唯一限制改為部分唯一索引（只限「有效」紀錄）、`INSERT ... ON CONFLICT` 的目標加上 `where voided_at is null`、下班 SQL 會維護 `version`，並新增申請／核准／歷程。這些變化的解說在 [T03-1-attendance-corrections.md](T03-1-attendance-corrections.md)；本文件保留 T03 當時的程式與證據。

規格在 `docs/renewflow/t03/`；本文件把它們對應到實際程式。版本：repository `https://github.com/hsieh634/java-eip`、分支 `main`，審查基準是標題為 `feat: T03 ...` 的 commit（完整 SHA 在交付報告）。

---

## 1. 快速摘要

- **用途**：員工自己上班／下班打卡，看今日狀態與自己的出勤紀錄。
- **技術**：Spring MVC＋Spring Security Filter、JPA（唯讀 Entity）＋原子 SQL、PostgreSQL 唯一限制／CHECK／外鍵、Flyway V2、可注入的 `java.time.Clock`、React hook＋狀態重新查詢。
- **主要流程**：按鈕 → `POST /api/attendance/clock-in` → CSRF → 帳號有效性檢查 → Controller（員工 ID 取自 Session）→ Service（交易、伺服器時間）→ Repository 原子 SQL → PostgreSQL 約束 → 回應 → 前端**重新查詢**今日狀態。
- **最重要的規則**：
  1. 身份來自 Session、時間來自伺服器，前端無法指定。
  2. 並行正確性由**資料庫**保證（唯一限制＋原子 SQL），不是前端按鈕、也不是「先查再新增」。
  3. 只有預期的業務衝突才是 409；系統故障維持 5xx。
  4. 已停用／刪除的員工，下一個受保護請求就被拒絕。
  5. 前端不假設打卡結果，一律重新查詢後端。

學習假設（不代表任何公司制度）：固定白班、Asia/Taipei、每人每日一組、必須先上班才能下班、不支援跨日／輪班／補登／修改／刪除。完整清單見 `docs/renewflow/t03/02-assumptions.md`。

## 2. 操作入口

| 操作 | 入口 | 結果 |
|---|---|---|
| 查看今日狀態 | 登入後首頁的「今日打卡」卡片，或側邊選單「出勤打卡」頁 | 尚未上班／工作中／今日已完成，與上下班時間（台北時間） |
| 上班打卡 | 「上班打卡」按鈕 | 成功 → 狀態變「工作中」；今日已有紀錄 → 顯示「今日已經上班打卡…已更新為最新狀態」 |
| 下班打卡 | 「下班打卡」按鈕 | 成功 → 「今日已完成」；沒上班／已下班 → 對應的 409 訊息 |
| 查詢出勤紀錄 | 「出勤打卡」頁，起日／迄日＋「查詢」 | 預設本月；最多 31 天；無資料顯示空狀態；日期錯誤顯示提示 |
| 昨日未下班提醒 | 今日卡片上的黃色提醒 | 「你在 2026-09-30 的出勤紀錄尚未下班…不支援補登」 |

## 3. 實際程式碼定位

### 後端（`backend/src/main/java/com/renewflow/eip/`）

| 位置 | 內容 |
|---|---|
| `attendance/AttendanceController` | 四個端點；`@AuthenticationPrincipal` 取員工 ID |
| `attendance/AttendanceService` | `clockIn`、`clockOut`、`today`、`list`；交易邊界、衝突判斷、日期範圍驗證 |
| `attendance/AttendanceRepository` | `insertIfAbsent`（`ON CONFLICT DO NOTHING`）、`clockOutIfOpen`（條件 UPDATE）、查詢方法 |
| `attendance/Attendance` | 唯讀 Entity（`@Immutable`） |
| `attendance/AttendanceRecordResponse`、`TodayAttendanceResponse`、`AttendanceListResponse`、`AttendanceStatus` | DTO 與狀態 |
| `attendance/AttendanceRuleException`、`InvalidDateRangeException` | 業務衝突（409）／日期範圍錯誤（400） |
| `shared/time/TimeConfig`、`BusinessTime` | 可注入的 `Clock`、台北時區日期換算 |
| `shared/security/ActiveAccountFilter`、`identity/AccountStatusService` | 共用帳號有效性檢查 |
| `shared/web/GlobalExceptionHandler` | 409／400 的轉換（新增 3 個處理器） |
| `db/migration/V2__create_attendance.sql` | 資料表與約束 |
| `identity/DevSeedRunner`、`Employee.changePasswordHash` | 開發帳號密碼維護（`RENEWFLOW_DEV_SEED_RESET`） |

### 前端（`frontend/src/`）

| 位置 | 內容 |
|---|---|
| `attendance/useTodayAttendance.ts` | 今日狀態、打卡動作、**動作後一律重新查詢**、401／409／其他錯誤的分流 |
| `components/TodayAttendanceCard.tsx` | 今日打卡卡片（首頁與出勤頁共用） |
| `pages/AttendancePage.tsx` | 日期範圍查詢、載入／空／錯誤／重試 |
| `components/AppShell.tsx`、`navigation/useHashView.ts`、`App.tsx` | 共用框架與 hash 導覽（`#/attendance`） |
| `api/attendance.ts`、`lib/time.ts` | API 呼叫、台北時區顯示 |

### 測試（`backend/src/test/.../attendance/`、`frontend/e2e/`）

`AttendanceIntegrationTest`（24）、`AttendanceConcurrencyIntegrationTest`（4）、`ActiveAccountIntegrationTest`（6）、`AccountCheckFailureIntegrationTest`（1）、`AttendanceSchemaTest`（5）、`BusinessTimeTest`（3）、`identity/DevSeedRunnerTest`（4）；`frontend/e2e/attendance.mjs`（隔離堆疊）、`isolated-stack.mjs`。

## 4. 完整資料流

### 上班打卡

```
1 使用者按「上班打卡」                          TodayAttendanceCard → useTodayAttendance.perform('in')
2 前端 POST /api/attendance/clock-in            api/http.ts 從 Cookie 讀 XSRF-TOKEN 放進標頭；不帶任何身份／時間
3 Vite 代理（開發）→ Tomcat → Security Filter Chain
    CsrfFilter：缺／錯 → 403 CSRF_REJECTED
    SecurityContextHolderFilter：由 Session 還原 EmployeeUserDetails
    ActiveAccountFilter：查 employee（enabled、role）
        帳號失效／角色變更 → 作廢 Session、401（請求到此為止，不進 Controller）
        資料庫故障 → 500，不作廢 Session
    AuthorizationFilter：/api/** 需已驗證
4 AttendanceController.clockIn(principal)       員工 ID = principal.getEmployeeId()
5 AttendanceService.clockIn（@Transactional）
    now = Clock 的時間點（截到微秒）；today = BusinessTime.workDateOf(now)（台北日期）
6 AttendanceRepository.insertIfAbsent           insert ... on conflict (employee_id, work_date) do nothing
7 PostgreSQL                                    唯一限制裁決；回傳 1 或 0
    1 → 讀回該筆 → AttendanceRecordResponse（200）
    0 → 查原因 → AttendanceRuleException(ALREADY_CLOCKED_IN) → GlobalExceptionHandler → 409
8 前端收到回應（成功、409、錯誤皆然）→ reload()：GET /api/attendance/me/today
9 畫面只顯示「重新查詢後」的狀態；通知外層（出勤頁重新載入紀錄列表）
```

### 下班打卡

同上，第 6 步改為 `clockOutIfOpen`（條件 UPDATE），回傳 0 時再查：沒有今日紀錄 → `CLOCK_IN_REQUIRED`；已有下班時間 → `ALREADY_CLOCKED_OUT`；其餘（時鐘倒退）→ 5xx。

## 5. 核心程式解說

### 5.1 日期與時間型別、Clock、時區

- **`Instant`（時間點）vs `LocalDate`（日曆日）**：上下班時間是「某一瞬間」，用 `Instant`，資料庫是 `timestamptz`；業務日期是「台北的哪一天」，用 `LocalDate`，資料庫是 `date`。
- **同一時間點的 UTC 日期與台北日期可能不同**：`2026-10-01T16:30:00Z` 在 UTC 是 10/1，在台北是 10/2 00:30。`BusinessTime.workDateOf` 用 `instant.atZone(Asia/Taipei).toLocalDate()` 換算；如果誤用 UTC，台北 00:00～08:00 的打卡會被記到前一天（變異檢查證明測試能抓到）。
- **為什麼把 `work_date` 存起來而不是每次換算**：換算規則是業務規則，固化在資料裡，歷史紀錄的日期歸屬不會因為伺服器時區或查詢方式改變而漂移；代價是 `work_date` 與 `clock_in_at` 必須一致，所以只允許 `AttendanceService` 這一條路徑寫入。
- **注入 `Clock`**：Service 不呼叫 `Instant.now()`，而是 `clock.instant()`。正式環境 `TimeConfig` 提供 `Clock.systemUTC()`；測試以 `MutableClock` 取代，直接設定「現在是 2026-10-01T16:30:00Z」，不必修改作業系統時間。測試版時鐘還能「每次讀取前進 1 秒」，讓並行請求拿到不同時間，覆蓋行為才抓得出來。
- **截到微秒**：PostgreSQL `timestamptz` 精度是微秒；`BusinessTime.truncateToDatabasePrecision` 確保回給前端的值與資料庫存的值完全一致。
- **前端顯示**：後端回 ISO 8601（`2026-10-01T01:05:00Z`），`lib/time.ts` 用 `Intl.DateTimeFormat`（`timeZone: 'Asia/Taipei'`）顯示。

### 5.2 `@Transactional`、唯一限制與並行

```java
// AttendanceService.clockIn（節錄）
@Transactional
public AttendanceRecordResponse clockIn(Long employeeId) {
    Instant now = now();
    LocalDate today = BusinessTime.workDateOf(now);
    int inserted = attendanceRepository.insertIfAbsent(employeeId, today, now);   // 原子：不存在才新增
    if (inserted == 0) { ... throw new AttendanceRuleException(ALREADY_CLOCKED_IN, ...); }
    return recordOf(employeeId, today, today);
}
```

- **為什麼「先查沒有、再新增」不行**：兩個請求可能同時查到「沒有」，然後都新增。唯一限制會擋下其中一個，但那時已是例外。實測：把 `ON CONFLICT` 拿掉改成「先查再新增」，並行測試第 1 輪就失敗（出現 500）。
- **為什麼不是「捕捉唯一限制例外再轉 409」**：PostgreSQL 在約束錯誤後會把**整個交易標記為失敗**，之後任何 SQL 都會被拒絕（`current transaction is aborted`），要查「為什麼衝突」必須開新交易；而且這種寫法容易把外鍵等其他完整性錯誤也誤判成「重複打卡」。`INSERT ... ON CONFLICT (employee_id, work_date) DO NOTHING` 不產生錯誤，只回傳受影響筆數（1＝成功、0＝已存在），交易仍然健康，可以接著查原因，而且**只處理這一個預期的衝突**。
- **第二個並行 INSERT 發生什麼**：它在唯一索引上等待第一個交易提交；第一個提交後，它發現鍵已存在，於是「什麼都不做」回傳 0。
- **下班的條件更新**：

  ```sql
  update attendance set clock_out_at = :now, updated_at = :now
   where employee_id = :employeeId and work_date = :workDate
     and clock_out_at is null and clock_in_at <= :now
  ```

  兩個並行下班：第二個 UPDATE 在列鎖上等待；第一個提交後，PostgreSQL（READ COMMITTED）用最新資料**重新檢查 WHERE**，`clock_out_at is null` 已不成立，更新 0 筆，**不會覆蓋第一次的下班時間**。變異檢查：拿掉 `clock_out_at is null`，重複下班與並行下班測試都失敗。
- **`@Transactional` 做了什麼**：把 Service 方法包成一個資料庫交易（原子性：全做或全不做；例外時回滾）。預設只有未檢查例外（`RuntimeException`）會回滾。`@Modifying` 查詢需要交易。它靠 Spring 代理運作，同一個類別內部呼叫不會經過代理。
- **`@Modifying(clearAutomatically = true)`**：更新後清空持久化內容，避免同一交易內讀到更新前的快取 Entity。
- **為什麼 Entity 是唯讀（`@Immutable`）**：寫入路徑只有兩條原子 SQL，沒有 `save()`，就沒有「讀出、修改、存回」造成覆蓋的機會。
- **只有預期的衝突才是 409**：外鍵錯誤（員工不存在）、時鐘倒退（下班早於上班）、資料庫故障都維持 5xx；測試 A-29 驗證外鍵錯誤丟出的是資料完整性例外、不是 `AttendanceRuleException`。

### 5.3 為什麼按鈕停用不能取代後端保護

按鈕停用只是介面行為：使用者可能開兩個分頁（畫面過期）、雙擊、重送請求，或直接用 curl 呼叫 API，也可能在回應遺失後再按一次。`useTodayAttendance` 用 `useRef` 擋住第二次點擊、按鈕處理中停用，這只改善操作體驗；**真正的保證在資料庫**。E2E S2 刻意製造「過期分頁仍有可按的按鈕」，真的送出第二次上班，得到後端的 409（不是前端擋住）。

### 5.4 Session 身份與資料所有權

`AttendanceController` 的每個方法都只有 `@AuthenticationPrincipal EmployeeUserDetails principal`，沒有員工 ID、日期或時間的參數；請求本文與多餘的查詢參數會被忽略（測試 A-08、A-09：本文塞別人的 `employeeId`、查詢字串塞 `?employeeId=` 都沒有效果）。Repository 的每個方法都以 `employee_id` 限定範圍。三個角色在這階段行為相同，只操作自己的資料。

### 5.5 共用帳號有效性檢查（`ActiveAccountFilter`）

- **問題**：Session 是登入當時的快照；員工被停用後，舊 Session 在到期前仍可寫入。
- **做法**：在 Filter Chain 中、`AuthorizationFilter` 之前，每個已驗證的請求查一次 `select enabled, role from employee where id = ?`。失效（停用／刪除）或角色與快照不同 → 作廢 Session、回 401，**請求不會進入 Controller／Service**，所以不會讀寫任何資料。
- **為什麼放 Filter**：一個地方涵蓋所有受保護 API，T04 以後新增端點自動受保護，不必每個 Service 記得檢查。
- **成本**：每個已驗證請求多一次主鍵單列查詢（約毫秒級）。刻意不快取：快取會讓停用生效延遲。
- **資料庫故障 ≠ 帳號停用**：查詢發生例外時回 500 且**不**作廢 Session（測試以受控替身驗證：故障排除後同一個 Session 仍可使用）。
- **角色變更**：只做到「與 Session 快照不同就要求重新登入」；依角色而異的授權留到 T05。
- **登入請求不經過此檢查**，讓帶著過期 Session 的人可以重新登入；登出由更早的 `LogoutFilter` 處理。

### 5.6 前端：不假設結果

`useTodayAttendance.perform`：動作結束後（成功、409、網路中斷、5xx）**一律** `reload()` 重新查詢。例如回應遺失時，伺服器可能其實已寫入——E2E S2 讓請求真的送達並被處理、瀏覽器卻收不到回應，畫面顯示「無法確認打卡結果」，重新查詢後顯示實際已下班的時間。錯誤分流：401 → `AuthProvider.refresh()`（回登入頁）；409 → 顯示後端訊息；其他 → 顯示「無法確認」，**不會導回登入頁**。

## 6. 設計理由與取捨

| 決定 | 原因 | 替代方案與成本 |
|---|---|---|
| `ON CONFLICT DO NOTHING`＋條件 UPDATE | 單一原子語句、交易不失效、只處理預期衝突 | 捕捉例外：交易失效、易誤判；悲觀鎖 `SELECT ... FOR UPDATE`：多一次往返；樂觀鎖 `@Version`：衝突要重試，對「只寫一次」的欄位較繞 |
| 存 `work_date` | 業務規則固化、歷史不漂移、可建唯一限制 | 查詢時換算：無法用唯一限制保證每日一筆 |
| Filter 做帳號檢查 | 一處涵蓋全部、請求不進 Service | 每個 Service 檢查：易漏；快取：生效延遲 |
| 唯讀 Entity | 避免讀改寫覆蓋 | 一般 Entity＋`save()`：並行時需要額外鎖 |
| hash 導覽 | 兩頁不需要路由套件，重新整理與上一頁可用 | react-router：T04 起再評估 |
| E2E 隔離堆疊 | 不碰使用者資料也能重跑 | 在 dev 庫寫入再清理：違反隔離 |

屬於學習假設的簡化：固定白班、每日一組、不補登、不跨日、時鐘被視為正確（A-14）。

## 7. 資料模型與 SQL

表格與約束見 `docs/renewflow/t03/02-data-model.md`。實際觀察到的 SQL（測試時開啟 `org.hibernate.SQL`；原生 SQL 的日誌只顯示第一行，完整語句見 `AttendanceRepository`）：

```sql
insert into attendance (employee_id, work_date, clock_in_at, created_at, updated_at) ...      -- 原生，ON CONFLICT DO NOTHING
update attendance set clock_out_at = ?, updated_at = ? ...                                    -- 原生，條件 UPDATE
select ... from attendance a1_0 where a1_0.employee_id=? and a1_0.work_date=?                  -- 今日／讀回
select ... from attendance a1_0 where a1_0.employee_id=? and a1_0.work_date<? and a1_0.clock_out_at is null order by a1_0.work_date desc fetch first ? rows only
select ... from attendance a1_0 where a1_0.employee_id=? and a1_0.work_date between ? and ? order by a1_0.work_date desc
```

- 唯一限制 `(employee_id, work_date)` 同時是上列查詢的索引；沒有關聯載入，沒有 N+1。
- 帳號檢查：`select enabled, role from employee where id=?`（JPQL 投影，只取兩欄）。**此一句為依 JPQL 推估，未單獨截取日誌。**
- 關鍵約束：`UNIQUE(employee_id, work_date)`、`CHECK (clock_out_at IS NULL OR clock_out_at >= clock_in_at)`、外鍵 `ON DELETE RESTRICT`（有出勤的員工不能被刪，歷史不會被連帶清掉）。

## 8. 安全與錯誤

| 情境 | 結果 | 測試 |
|---|---|---|
| 未登入（讀／寫） | 401 | A-10 |
| 已登入、缺 CSRF 的 POST | 403 `CSRF_REJECTED`，無資料變動 | A-11 |
| 重複上班／已下班再上班 | 409 `ALREADY_CLOCKED_IN` | A-05、A-07 |
| 沒上班就下班 | 409 `CLOCK_IN_REQUIRED` | A-04、A-19、A-21 |
| 重複下班 | 409 `ALREADY_CLOCKED_OUT`，原時間不變 | A-06 |
| 日期格式錯誤 | 400 `VALIDATION_FAILED` | A-22 |
| 只給一個日期／起日大於迄日／超過 31 日 | 400 `INVALID_DATE_RANGE` | A-22 |
| 員工已停用／刪除／角色變更後的請求 | 401，Session 作廢，資料不變 | A-12、A-13、A-15 |
| 帳號檢查時資料庫故障 | 500 `INTERNAL_ERROR`，Session 不失效 | A-14 |
| 時鐘倒退、外鍵錯誤 | 5xx（不是 409） | A-29 |
| 並行重複上班／下班 | 恰一個成功，其餘 409 | A-24、A-25 |
| 重複請求（同一個請求重送） | 與重複打卡相同，由資料庫裁決 | A-05、A-06 |

## 9. 驗證證據（本機實際執行）

環境：Windows 11、Temurin 21.0.12、Maven 3.9.12、Node 24.14.0、PostgreSQL 17（Docker）、Edge。

| 項目 | 指令 | 結果 |
|---|---|---|
| 後端全部測試 | `cd backend; .\mvnw.cmd test` | **94 個，0 失敗、0 略過，BUILD SUCCESS**（T01／T02 的 47 個全保留；新增 47 個：出勤相關 43［`AttendanceIntegrationTest` 24、`AttendanceConcurrencyIntegrationTest` 4、`ActiveAccountIntegrationTest` 6、`AccountCheckFailureIntegrationTest` 1、`AttendanceSchemaTest` 5、`BusinessTimeTest` 3］＋`DevSeedRunnerTest` 4） |
| 前端建置／檢查 | `npm run build`、`npm run lint` | 通過，0 警告 |
| 瀏覽器 E2E（dev 環境，唯讀） | `npm run e2e:smoke`／`e2e:logout`／`e2e:password` | 18/18、23/23、10/10 |
| 瀏覽器 E2E（隔離堆疊） | `npm run e2e:attendance` | **37/37**：品牌、完整打卡流程、出勤頁同步、hash 導覽、日期範圍 UI、409、回應遺失、5xx、網路中斷、401、手機尺寸（390×844：無橫向捲動、按鈕 ≥ 44px、紀錄表卡片式） |
| 變異檢查 | 暫時破壞程式後跑對應測試，再還原 | 下班拿掉 `clock_out_at is null` → 3 個失敗；不註冊 `ActiveAccountFilter` → 4 個失敗；日期改用 UTC → 4 個失敗；`ON CONFLICT` 改成「先查再新增」→ 並行測試 2 個失敗（第 1 輪即出現 500） |
| V2 套用到「已有 V1 與資料」的資料庫 | 測試 `v2UpgradesExistingV1Database`（測試庫內獨立 schema）；以及重啟開發後端 | 通過；開發庫由 `flyway=1`（3 位員工）升級為 `1,2`，員工資料保留 |
| 乾淨庫建立 | 每次後端測試啟動時 clean＋migrate | 通過（測試 `cleanDatabaseHasV2`） |
| 隔離 | E2E 後查開發庫 | 員工 3、出勤 0、`e2e_%` 帳號 0（開發資料未被碰觸）；E2E 專用員工只在測試庫 |

**未執行／限制**
- 手機是 Chrome DevTools 的模擬尺寸與觸控事件，**不是實機**；只驗了 Edge。
- 瀏覽器端沒有「真的跨過午夜」的測試；日期邊界由後端的可控時鐘驗證。
- 並行測試證明在 PostgreSQL 預設隔離層級（READ COMMITTED）與本專案 SQL 下成立；沒有做壓力／效能測試，也沒量測帳號檢查增加的延遲。
- 沒有測試：HTTPS 下 `Secure` Cookie、Session 逾時、無障礙（螢幕閱讀器）審查。
- `RENEWFLOW_DEV_SEED_RESET` 流程只在測試庫驗證，**沒有對你的開發帳號執行**。
- E2E 的「登入失效」由另一個分頁登出來製造；「停用帳號」只在後端測試驗證，沒有在瀏覽器端重現。
- 測試輸出的 Mockito 自我附加警告來自測試框架，不影響結果。

## 10. 技術問答與練習

**Q1. 為什麼不相信前端傳來的員工 ID 與打卡時間？**
前端完全不可信：任何人都能用開發者工具或 curl 改請求。身份取自已驗證的 Session，時間取自伺服器，才能防止冒用他人打卡與偽造時間。追問：員工 ID 放在 URL 路徑（`/employees/5/clock-in`）有什麼問題？（要另外檢查「路徑 ID 是否等於登入者」，漏檢查就是越權。）

**Q2. 為什麼關掉按鈕不能保證不重複？**
按鈕只管這個畫面；使用者可開多個分頁、雙擊、重送、直接呼叫 API。保證必須在後端與資料庫。

**Q3. 為什麼「先查沒有，再新增」仍會在兩個同時請求時失敗？**
檢查與寫入是兩個步驟，中間可能被另一個請求插入（check-then-act 競態）。解法是把「檢查＋寫入」變成資料庫的單一原子操作，或用唯一限制作最終裁判。

**Q4. 交易與資料庫唯一限制各自處理什麼？**
交易處理「一組操作要全成功或全回滾」（原子性）與隔離；唯一限制處理「資料本身不可違反的規則」（任何程式路徑、並行都適用）。兩者互補：交易不會自動防止重複列，唯一限制不會自動讓多步操作原子化。

**Q5. 唯一限制衝突時怎麼回傳可理解的結果？**
用 `ON CONFLICT DO NOTHING` 取得受影響筆數，0 時查原因並丟出業務例外，由 `@RestControllerAdvice` 轉成 409 與機器可讀的 code。要避免把所有 `DataIntegrityViolationException` 都當成「重複」。追問：為什麼捕捉例外後不能在同一個交易繼續查詢？（PostgreSQL 交易已失效。）

**Q6. Clock 注入的好處？**
時間變成可替換的相依：測試可以設定「現在」、驗證時區邊界與並行，不必改系統時間，也不會讓測試依賴實際執行時刻。

**Q7. `timestamptz` 與 `date`、`Instant` 與 `LocalDate` 怎麼選？**
發生的瞬間用時間點（`Instant`／`timestamptz`）；歸屬的日曆日用 `LocalDate`／`date`，並明確指定時區規則。不要用不帶時區的本地時間表示瞬間。

**Q8. 401、403、409、5xx 的差異？**
401 未認證（含帳號失效）；403 已認證但被禁止（含 CSRF 失敗）；409 與資源目前狀態衝突（重複上班）；5xx 系統故障。把故障說成 409 或 401 會誤導使用者與監控。

**Q9. 停用員工的 Session 為什麼不能只靠 `/me` 檢查？**
`/me` 只覆蓋一個端點，寫入 API 仍會在 Session 到期前放行。需要所有受保護請求共用的檢查，放在 Filter 並使失效請求不進入 Service。

**Q10. Service 測試與 DB 整合測試差在哪？**
Service 單元測試快，但驗證不到 SQL、約束、鎖與並行；本功能的核心正確性在資料庫行為，所以用真實 PostgreSQL＋多執行緒整合測試，另用純單元測試驗證時區換算。

### 練習（只有這一題）

**背景**：同一位員工（今日已上班、尚未下班）同時送出兩個下班請求 A 與 B。`AttendanceRepository.clockOutIfOpen` 的 SQL 是 `update attendance set clock_out_at = :now ... where employee_id = ? and work_date = ? and clock_out_at is null and clock_in_at <= :now`，資料庫為 PostgreSQL 預設的 READ COMMITTED。A 先取得該列的鎖。

**問題**：
1. 請依時間順序寫出 B 的 UPDATE 在「A 提交之前」與「A 提交之後」各發生什麼事；B 的受影響筆數是多少；`AttendanceService.clockOut` 因此丟出哪個例外、最後 HTTP 回應是什麼。
2. 假如有人把實作改成「先 `select` 檢查 `clock_out_at is null`，再 `update ... where employee_id = ? and work_date = ?`（沒有 `clock_out_at is null` 條件）」，在同樣的並行情境下可能出什麼錯？為什麼？

請把你的回答帶回 ChatGPT 對話，由它批改並決定下一題。
