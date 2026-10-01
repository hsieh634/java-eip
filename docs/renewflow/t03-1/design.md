# Design：T03.1

## 後端結構（`backend/src/main/java/com/renewflow/eip/attendance/`）

| 類別 | 責任 |
|---|---|
| `Attendance`（擴充）、`AttendanceRepository`（擴充） | 版本、作廢、來源欄位；所有查詢改為只看**有效**紀錄；新增作廢／更正／補登的原生條件 SQL |
| `AttendanceRequest`、`AttendanceRequestRepository` | 申請（唯讀 Entity＋原生條件更新／`INSERT ... ON CONFLICT ... RETURNING`） |
| `AttendanceHistory`、`AttendanceHistoryRepository`、`AttendanceAuditLog` | 只新增的歷程；由 `AttendanceAuditLog.record` 單一入口寫入（測試可注入失敗） |
| `AttendanceTimes` | 台北時間輸入換算與驗證（不得未來、下班不早於上班、同一業務日） |
| `AttendanceRequestService` | 員工端：提出、撤回、列出、查歷程 |
| `AttendanceAdminService` | 管理端：審核、直接更正／作廢、查詢；每個方法先確認 ADMIN |
| `AttendanceRequestController`、`AttendanceAdminController` | HTTP 邊界 |
| 例外 | `AttendanceConflictException(code)`→409、`OperationForbiddenException(code)`→403、`ResourceNotFoundException(code)`→404、`InvalidRequestException`→400 |

## 關鍵設計

1. **重新打卡＝作廢＋新增**。作廢是更新 `voided_at/voided_by/version`，原時間不動；之後員工以正常的上班 SQL 建立新紀錄，因為部分唯一索引只看有效紀錄。`insertIfAbsent` 的 `ON CONFLICT` 目標加上 `WHERE voided_at IS NULL`。
2. **版本檢查**。`attendance.version` 每次寫入 +1（含 T03 的 `clockOutIfOpen`）。申請記住 `target_version`；核准時以 `UPDATE ... WHERE id = ? AND version = ? AND voided_at IS NULL` 套用，0 筆＝過時＝409。沒有目標（補登）的申請以「`ON CONFLICT DO NOTHING` 插入，0 筆＝該日已有紀錄」判定過時。
3. **申請只處理一次**。`UPDATE attendance_request SET status = ... WHERE id = ? AND status = 'PENDING'` 先執行（鎖住申請列）；0 筆＝已被處理＝409。其後套用出勤修改；任何一步丟例外，整個 `@Transactional` 回滾，包括申請狀態。
4. **歷程同交易**。`AttendanceAuditLog.record` 在 Service 的同一交易內執行；失敗丟例外 → 全部回滾。資料庫觸發器使歷程只能新增。
5. **只有預期的衝突才 409**。例外類別明確區分；資料庫故障、外鍵錯誤、時鐘倒退維持 5xx。
6. **管理權限**。`/api/admin/**` URL 規則＋Service 內再檢查；`ActiveAccountFilter`（T03）確保角色最新；職責分離（不可審核自己的申請、不可更正自己的出勤）。
7. **time 輸入**。前端傳台北當地時間（`HH:mm:ss`）與日期，後端以 `Asia/Taipei` 換算成時間點；所有「現在」來自 `Clock`。

## 前端

- `api/attendanceRequests.ts`、`api/adminAttendance.ts`、`attendance/useMyRequests.ts`、`attendance/useAutoRefresh.ts`。
- 員工：`TodayAttendanceCard`（申請重新打卡）、`AttendancePage`（申請更正／補登、我的申請、更正歷程、作廢紀錄標示）。
- 管理員：`AdminAttendancePage`（`#/admin/attendance`；僅 ADMIN 顯示入口，後端另行驗證）；確認視窗顯示員工、日期、原值、預計結果。
- 自動重新取得：視窗 focus／分頁可見、每分鐘檢查台北日期是否變更、有待審申請時每 30 秒輪詢；不用 WebSocket。
- 紀錄列表 key 使用紀錄 ID（同日期可有多筆）。

## 開發帳號

`DevSeedRunner` 新增 `developer`（ADMIN）。密碼取自 `RENEWFLOW_DEV_DEVELOPER_PASSWORD`；空白則不建立並記錄明確提示。`RENEWFLOW_DEV_SEED_RESET=true` 對 developer 同樣只更新密碼雜湊。

## E2E 保護補強

`E2eFlywayConfig`（`@Profile("e2e")`，測試原始碼）提供 `FlywayMigrationStrategy`：在 `migrate` 之前以 Flyway 實際 datasource 的 `getCatalog()` 套用 `TestDatabaseGuard`；失敗則不呼叫 migrate。
