# T04 資料模型

Migration：`backend/src/main/resources/db/migration/V5__projects_and_timesheets.sql`。V1～V4 不修改；V5 只新增兩張表，**不建立任何預設專案或工時**。

## project

| 欄位 | 型別 | 限制 |
|---|---|---|
| `id` | bigint identity | PK |
| `code` | varchar(32) | NOT NULL；`uq_project_code` 唯一；`ck_project_code`：`^[A-Z0-9_-]{2,32}$`（已正規化） |
| `name` | varchar(100) | NOT NULL；`ck_project_name`：去除空白後非空且已去除前後空白 |
| `description` | varchar(1000) | 可 NULL |
| `active` | boolean | 預設 true |
| `version` | bigint | 預設 0；每次修改 +1 |
| `created_at`／`updated_at` | timestamptz | NOT NULL（值由後端 Clock 決定） |

## timesheet_entry

| 欄位 | 型別 | 限制 |
|---|---|---|
| `id` | bigint identity | PK |
| `employee_id` | bigint | FK → employee（RESTRICT） |
| `project_id` | bigint | FK → project（RESTRICT：有工時的專案不能刪） |
| `work_date` | date | NOT NULL |
| `description` | varchar(1000) | NOT NULL；`ck_timesheet_description`：去除空白後非空 |
| `minutes` | integer | `ck_timesheet_minutes`：15～1440 且 `minutes % 15 = 0` |
| `request_id` | uuid | NOT NULL；`uq_timesheet_employee_request (employee_id, request_id)` |
| `version` | bigint | 預設 0 |
| `created_at`／`updated_at` | timestamptz | NOT NULL |

索引：`ix_timesheet_employee_date (employee_id, work_date, id)`（本人依日期範圍查詢、計算某日合計）、`ix_timesheet_project (project_id)`（外鍵）。

**每日 1440 上限沒有資料庫限制**：它跨多列，CHECK 只能看單列。由後端交易內的員工列鎖序列化後檢查（見 design）。

## 關鍵 SQL（程式中的原生 SQL）

```sql
select id from employee where id = ? for no key update;                       -- 員工列鎖
select * from project where id = ? for share;                                 -- 專案列鎖
select coalesce(sum(minutes), 0) from timesheet_entry
 where employee_id = ? and work_date = ? and id <> ?;                         -- 當日其他紀錄合計
insert into timesheet_entry (...) values (...)
 on conflict (employee_id, request_id) do nothing returning id;               -- 新增（重複 → 0 列，交易仍健康）
update timesheet_entry set ..., version = version + 1, updated_at = ?
 where id = ? and employee_id = ? and version = ?;                            -- 修改（所有權＋版本）
insert into project (...) values (...) on conflict (code) do nothing returning id;
update project set name = ?, description = ?, active = ?, version = version + 1, updated_at = ?
 where id = ? and version = ?;
```

## 升級

`TimesheetSchemaTest#v5UpgradesExistingV4Database`：在已有 V4 資料（員工、有效／作廢出勤、申請、歷程、已修改的定位設定與歷程、打卡事件）的資料庫套用 V5 → 七張既有表逐列雜湊相同、定位設定不變、沒有預設專案。開發庫實際套用結果見 version-log。
