# T03 資料模型

Migration：`backend/src/main/resources/db/migration/V2__create_attendance.sql`（V1 未修改）。

## attendance

| 欄位 | 型別 | 限制 | 說明 |
|---|---|---|---|
| id | bigint | PK，`GENERATED ALWAYS AS IDENTITY` | |
| employee_id | bigint | NOT NULL，FK → `employee(id)` `ON DELETE RESTRICT` | 出勤的主人；員工有出勤資料時不可被刪除，歷史不會被連帶清掉 |
| work_date | date | NOT NULL | 業務日期（Asia/Taipei） |
| clock_in_at | timestamptz | NOT NULL | 實際上班時間點 |
| clock_out_at | timestamptz | 可為 NULL | 實際下班時間點 |
| created_at / updated_at | timestamptz | NOT NULL，預設 `now()` | |

約束：
- `uq_attendance_employee_work_date UNIQUE (employee_id, work_date)`：一人一天一筆；同時是查詢「某人某日／某區間」的索引。
- `ck_attendance_clock_out_after_in CHECK (clock_out_at IS NULL OR clock_out_at >= clock_in_at)`。

## 為什麼用 `timestamptz` ＋ `date`

- 打卡是「某一瞬間」→ `timestamptz`（PostgreSQL 內部存 UTC 時間點，與連線時區無關）。
- 業務日期是「台北的哪一天」→ 在寫入時由時間點換算並**存成 `date`**，而不是每次查詢時再換算。原因：換算規則（時區）是業務規則，固化在資料中，之後即使伺服器時區或查詢方式改變，歷史資料的日期歸屬也不會漂移。代價是 `work_date` 與 `clock_in_at` 必須一致，因此只允許後端單一程式路徑（`AttendanceService`）寫入。

## 關鍵 SQL（實際使用，見 `AttendanceRepository`）

上班（原子，避免「先查再新增」的競態）：

```sql
insert into attendance (employee_id, work_date, clock_in_at, created_at, updated_at)
values (:employeeId, :workDate, :now, :now, :now)
on conflict (employee_id, work_date) do nothing
```

回傳 1＝新增成功；0＝今日已有紀錄（衝突）。

下班（條件更新，只填一次）：

```sql
update attendance set clock_out_at = :now, updated_at = :now
 where employee_id = :employeeId and work_date = :workDate
   and clock_out_at is null and clock_in_at <= :now
```

回傳 1＝成功；0＝要再查一次判斷原因（沒有上班／已下班）。
