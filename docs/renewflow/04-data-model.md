# 資料模型（T01；T03 的 `attendance` 表見 [`t03/02-data-model.md`](t03/02-data-model.md)；T03.1 見 [`t03-1/03-data-model.md`](t03-1/03-data-model.md)；T03.2 的定位設定、設定歷程、打卡事件見 [`t03-2/04-data-model.md`](t03-2/04-data-model.md)）

Migration：`backend/src/main/resources/db/migration/V1__create_employee.sql`

## employee

| 欄位 | 型別 | 限制 | 說明 |
|---|---|---|---|
| id | bigint | PK，`GENERATED ALWAYS AS IDENTITY` | 內部識別碼；程式內所有關聯都用它 |
| employee_no | varchar(20) | NOT NULL，UNIQUE | 員工編號 |
| username | varchar(50) | NOT NULL，UNIQUE，CHECK `username = lower(username)` | 登入帳號；以 CHECK 保證大小寫一致，避免 `Max` 與 `max` 並存 |
| display_name | varchar(100) | NOT NULL | 顯示名稱 |
| password_hash | varchar(255) | NOT NULL | `DelegatingPasswordEncoder` 輸出（如 `{bcrypt}$2a$...`），不存明文 |
| role | varchar(20) | NOT NULL，CHECK IN (`EMPLOYEE`,`MANAGER`,`ADMIN`) | 單一角色（假設 A-01） |
| enabled | boolean | NOT NULL，預設 true | 停用後不得登入 |
| created_at | timestamptz | NOT NULL，預設 `now()` | |
| updated_at | timestamptz | NOT NULL，預設 `now()` | |

## 設計說明

- 角色用 `varchar + CHECK` 而非 PostgreSQL enum 型別：日後增減角色只需一個 migration 修改 CHECK，不必處理 enum 型別變更。JPA 端以 `@Enumerated(EnumType.STRING)` 對映，**不用 ORDINAL**，避免調整列舉順序造成資料錯位。
- 部門、審核人關係不在本輪建立；T05 才以新 migration 加入（`V2__...`），已套用的 V1 不再修改。
- 本輪沒有額外索引：UNIQUE 約束已為 `username`、`employee_no` 建立索引，登入查詢 `WHERE username = ?` 會使用它。

## 預期 SQL（推估，實測結果見學習文件）

登入時 `findByUsername`：

```sql
select ... from employee e where e.username = ?
```
