# T03.1 資料模型

Migration：`backend/src/main/resources/db/migration/V3__attendance_corrections.sql`（V1、V2 未修改）。

## 1. attendance 的變更

| 變更 | 說明 |
|---|---|
| 新增 `version bigint NOT NULL DEFAULT 0` | 每次修改 +1。上下班（原生 SQL）、核准、直接更正、作廢都同步維護 |
| 新增 `voided_at timestamptz`、`voided_by bigint → employee(id)` | 作廢標記；CHECK：兩者同為 NULL 或同為非 NULL |
| 新增 `source varchar(10) NOT NULL DEFAULT 'CLOCK'` | `CLOCK`（員工打卡）或 `MANUAL`（經核准／直接更正補登建立） |
| 新增 `corrected_at timestamptz` | 時間被更正過的時間點；畫面據此標示「已更正」 |
| **移除** `uq_attendance_employee_work_date` | 無條件唯一限制無法保留多筆作廢歷史 |
| **新增部分唯一索引** `uq_attendance_active_employee_work_date ON (employee_id, work_date) WHERE voided_at IS NULL` | 每人每日最多一筆**有效**紀錄 |
| 新增一般索引 `ix_attendance_employee_work_date (employee_id, work_date)` | 歷史（含作廢）查詢 |

既有 V2 資料升級後：`version=0`、`source='CLOCK'`、`voided_*`／`corrected_at` 為 NULL——使用者原有的紀錄原封不動。

**重要**：`INSERT ... ON CONFLICT` 的衝突目標必須與部分索引完全相符：

```sql
on conflict (employee_id, work_date) where voided_at is null do nothing
```

只改 migration 而不改這句，PostgreSQL 會回報「找不到符合 ON CONFLICT 的唯一或排除限制」。

## 2. attendance_request（申請）

| 欄位 | 說明 |
|---|---|
| id、employee_id（FK RESTRICT） | 申請人 |
| request_type | `RESET_TODAY`／`CORRECT_TIME` |
| status | `PENDING`／`APPROVED`／`REJECTED`／`CANCELLED` |
| work_date | 業務日期 |
| target_attendance_id（FK）、target_version | 申請當下的有效紀錄與版本；沒有紀錄（補登）則為 NULL |
| original_clock_in_at／out_at | 申請當下的原時間（快照） |
| requested_clock_in_at／out_at | 希望值（RESET_TODAY 為 NULL） |
| reason | 必填，非空白，≤ 500 字 |
| reviewer_id、reviewed_at、review_comment | 審核資訊（退回必填原因） |
| created_at、updated_at | |

約束：
- 部分唯一索引 `uq_attendance_request_pending (employee_id, work_date) WHERE status = 'PENDING'`：同員工同日最多一筆待審。
- CHECK：原因非空白；CORRECT_TIME 必有希望上班時間；希望下班不早於上班；APPROVED／REJECTED 必有審核人與時間，其他狀態不可有；REJECTED 必有審核意見。

## 3. attendance_history（歷程，只新增）

| 欄位 | 說明 |
|---|---|
| id、employee_id（FK）、work_date | 被影響的員工與日期 |
| operation | `REQUEST_SUBMITTED`／`REQUEST_CANCELLED`／`REQUEST_APPROVED`／`REQUEST_REJECTED`／`DIRECT_CORRECTION`／`DIRECT_VOID` |
| request_id、request_type | 相關申請（直接更正為 NULL） |
| attendance_id、target_version | 目標紀錄與版本 |
| reason | 原因（申請原因、審核意見或直接更正原因） |
| original_*、requested_*（上下班） | 原值、申請值（或直接更正的設定值） |
| after_clock_in_at／out_at、after_voided | **實際修改後**的值（修改前即 original_*） |
| result | 審核結果（`APPROVED`／`REJECTED`） |
| actor_id（FK） | 操作者（由後端依 Session 填入） |
| occurred_at | 伺服器時間（由 `Clock` 產生） |

- 觸發器 `trg_attendance_history_immutable`：任何 UPDATE／DELETE 都丟例外。
- 所有外鍵皆 `ON DELETE RESTRICT`：員工、紀錄、申請有歷程就不能被刪。

## 4. 為什麼歷程不用「從出勤紀錄推算」

出勤紀錄只保存「現在」；歷程保存「誰在何時基於什麼原因把什麼改成什麼」。兩者缺一不可，且歷程必須與修改同一交易寫入，才不會有「改了卻沒紀錄」。
