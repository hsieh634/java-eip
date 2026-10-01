# Design：T03

## 後端結構（`backend/src/main/java/com/renewflow/eip/`）

| 位置 | 內容 |
|---|---|
| `attendance/Attendance` | Entity，**唯讀對映**（寫入只走 Repository 的原子 SQL） |
| `attendance/AttendanceRepository` | `insertIfAbsent`（ON CONFLICT DO NOTHING）、`clockOutIfOpen`（條件更新）、查詢方法 |
| `attendance/AttendanceService` | 使用案例與交易邊界（`@Transactional`）、衝突判斷、日期範圍驗證 |
| `attendance/AttendanceController` | 四個端點；員工 ID 來自 Session |
| `attendance/*Response`、`AttendanceStatus`、`AttendanceRuleException`、`InvalidDateRangeException` | DTO 與例外 |
| `shared/time/TimeConfig`、`BusinessTime` | 可注入的 `Clock`（UTC）、業務時區與日期換算 |
| `identity/AccountStatusService`、`shared/security/ActiveAccountFilter` | 共用帳號有效性檢查 |

## 並行與一致性（核心）

**上班**：`insert ... on conflict (employee_id, work_date) do nothing`，回傳 1 成功、0 衝突。兩個同時請求：第二個的 insert 會等第一個交易提交，之後因唯一鍵已存在而「什麼都不做」回傳 0，轉成 409。**不使用「先查有沒有再新增」**——兩個請求可能都查到「沒有」。

**為什麼不是捕捉唯一限制例外**：PostgreSQL 在發生約束錯誤後會把整個交易標記為失敗，後續任何 SQL 都會被拒絕（`current transaction is aborted`）；而 `ON CONFLICT DO NOTHING` 不產生錯誤，交易仍然健康，可以繼續查詢「為什麼衝突」以產生精準的訊息。它也只處理「預期的」唯一衝突，不會把外鍵或其他資料庫錯誤誤認為重複打卡。

**下班**：`update ... where employee_id=? and work_date=? and clock_out_at is null and clock_in_at <= :now`。更新 0 筆時再查一次：沒有紀錄 → `CLOCK_IN_REQUIRED`；已有下班時間 → `ALREADY_CLOCKED_OUT`；有紀錄、未下班、但仍更新 0 筆 → 時鐘倒退（A-14），丟 `IllegalStateException`（5xx）。兩個同時下班：第二個的 update 在列鎖上等待，第一個提交後（READ COMMITTED）重新檢查 `clock_out_at is null` 為假，更新 0 筆 → 409，第一次時間不被覆蓋。

**交易**：`AttendanceService` 的寫入方法為 `@Transactional`；`@Modifying` 查詢需要交易；寫入後的讀取使用 `clearAutomatically` 避免讀到持久化內容的舊快取。

## 時間

`Clock`（UTC）由 `TimeConfig` 提供，Service 以 `clock.instant()` 取得時間點並截到微秒（PostgreSQL 精度），再以 `Asia/Taipei` 換算業務日期。測試以可控制的 `MutableClock` 取代，不改作業系統時間。

## 日期範圍

`from`／`to` 同時提供或省略；省略＝本月（以 Clock 換算的台北日期）。`to - from + 1 <= 31`（含起訖）。以 `LocalDate` 解析，格式錯誤由 Spring 的型別轉換拋 `MethodArgumentTypeMismatchException`，統一轉為 400 `VALIDATION_FAILED`。

## 前端

- `AppShell`（頂欄、側邊選單、登出失敗橫幅）包住各頁；畫面以 `location.hash` 切換（`#/`、`#/attendance`），不引入路由套件。
- `attendance/useTodayAttendance`：今日狀態與打卡動作。**任何打卡動作結束後都重新查詢今日狀態**（成功、409、網路中斷、5xx 皆然），不自行假設結果；401 → 呼叫 `AuthProvider.refresh()` 更新登入狀態；其他錯誤顯示訊息但不導回登入頁。
- `TodayAttendanceCard`（首頁與出勤頁共用）、`AttendancePage`（日期範圍查詢、載入／空／錯誤／重試）。
- 時間以 `Intl.DateTimeFormat`（`timeZone: 'Asia/Taipei'`）顯示。按鈕在進行中停用只是體驗，後端自行防重複。
- 響應式：窄螢幕下紀錄表改為卡片式、按鈕最小高度 44px。

## 產品名稱
僅調整顯示：登入頁、首頁頂欄、favicon、頁籤標題、README 與文件中的「產品」名稱。Repository、資料夾、Java package、資料庫、容器、環境變數、`spring.application.name` 沿用既有。

## 開發帳號密碼維護
`DevSeedRunner` 新增開發限定的 `RENEWFLOW_DEV_SEED_RESET=true`：啟動時對三個開發帳號**只更新密碼雜湊**（員工 ID、出勤資料不變），不是公開 API；僅 `dev` profile。取代舊的「刪帳號重建」流程（外鍵存在後會失敗，且會失去 ID）。
