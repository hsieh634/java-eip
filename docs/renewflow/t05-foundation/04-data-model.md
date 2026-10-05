# T05 前置：資料模型（V6）

> **T05 前置子任務**。V1～V5 不修改；新增 `V6__organization_foundation.sql`。migration 不建立任何部門、公司或員工資料。

## 1. department（新表）

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | bigint identity PK | |
| code | varchar(32) NOT NULL | 已正規化；`uq_department_code` 唯一；`ck_department_code`：`^[A-Z0-9_-]{2,32}$`；建立後不可修改（API 沒有這個欄位） |
| name | varchar(100) NOT NULL | `ck_department_name`：去除空白後非空、且已去除前後空白 |
| active | boolean NOT NULL DEFAULT true | 不刪除，只停用 |
| version | bigint NOT NULL DEFAULT 0 | 每次修改 +1 |
| created_at／updated_at | timestamptz NOT NULL | |

## 2. employee（新增三個欄位）

| 欄位 | 型別 | 說明 |
|---|---|---|
| department_id | bigint NULL → department(id) ON DELETE RESTRICT | NULL＝未指派；有員工的部門不能被刪 |
| manager_id | bigint NULL → employee(id) ON DELETE RESTRICT | **自關聯外鍵**：指向另一位員工；NULL＝沒有主管 |
| version | bigint NOT NULL DEFAULT 0 | 組織管理修改時 +1 |

- `ck_employee_not_own_manager`：`manager_id IS NULL OR manager_id <> id`（直接自指由資料庫擋；間接循環由後端在組織鎖內檢查）。
- 索引：`ix_employee_department`、`ix_employee_manager`（查部屬數、部門人數）。
- 既有員工：三個新欄位為 NULL／NULL／0；ID、員工編號、帳號、名稱、密碼雜湊、角色、啟用、時間**全部不變**。

## 3. organization_history（新表，只新增）

| 欄位 | 說明 |
|---|---|
| target_type | `EMPLOYEE`／`DEPARTMENT`；`ck_org_history_target` 保證只填對應的那個 ID |
| employee_id／department_id | 對象（外鍵） |
| action | `DEPARTMENT_CREATED`、`DEPARTMENT_UPDATED`、`EMPLOYEE_CREATED`、`EMPLOYEE_UPDATED` |
| reason | varchar(500)，狀態類變更必填（由後端檢查） |
| changes | text：JSON 陣列 `[{field, before, after}]`；部門與主管為 `{id, label}`。**不含密碼或雜湊** |
| actor_id | 操作者；`SYSTEM_DEMO_SEED` 時為 NULL |
| source | `ADMIN`／`SYSTEM_DEMO_SEED`；`ck_org_history_actor`：`(source = 'ADMIN') = (actor_id IS NOT NULL)` |
| occurred_at | timestamptz |

觸發器 `trg_organization_history_immutable` 沿用 V4 的 `reject_append_only_change()`：UPDATE／DELETE 一律拒絕。

## 4. 升級驗證

`OrganizationSchemaTest.v6UpgradesExistingV5Database`：在獨立 schema 套用到 V5、寫入員工（含停用者）、出勤、申請、歷程、定位設定、打卡事件、專案、工時後升級到 V6，比對：

- employee 的 **V5 舊欄位投影**（`id, employee_no, username, display_name, password_hash, role, enabled, created_at, updated_at`）逐列相同；
- 其他 8 張表逐列相同；
- 新欄位全部未指派、版本 0；沒有部門、沒有歷程。

開發庫實際升級的前後比對見學習文件第 8 節。
