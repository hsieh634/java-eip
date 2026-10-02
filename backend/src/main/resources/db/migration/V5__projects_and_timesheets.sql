-- T04：最小專案管理與個人工時回報。V1～V4 已套用，不修改；本檔只新增資料表，不改動既有資料表，也不建立任何預設專案或工時。

-- ---------------------------------------------------------------
-- 1. project：專案（停用不刪除）
-- ---------------------------------------------------------------
CREATE TABLE project (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 已正規化（去除前後空白、大寫）；建立後不可修改
    code         varchar(32)   NOT NULL,
    name         varchar(100)  NOT NULL,
    description  varchar(1000),
    active       boolean       NOT NULL DEFAULT true,
    -- 每次修改 +1；管理員提交時比對，過期的提交被拒絕
    version      bigint        NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL,
    updated_at   timestamptz   NOT NULL,
    -- 代碼唯一：並行建立相同代碼時由資料庫裁決
    CONSTRAINT uq_project_code UNIQUE (code),
    CONSTRAINT ck_project_code CHECK (code ~ '^[A-Z0-9_-]{2,32}$'),
    CONSTRAINT ck_project_name CHECK (length(btrim(name)) > 0 AND name = btrim(name))
);

-- ---------------------------------------------------------------
-- 2. timesheet_entry：個人工時（沒有刪除或作廢）
-- ---------------------------------------------------------------
CREATE TABLE timesheet_entry (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id  bigint        NOT NULL REFERENCES employee (id) ON DELETE RESTRICT,
    -- 專案停用不會刪除工時；有工時的專案也不能被刪除
    project_id   bigint        NOT NULL REFERENCES project (id) ON DELETE RESTRICT,
    work_date    date          NOT NULL,
    description  varchar(1000) NOT NULL,
    -- 整數分鐘：15～1440，且為 15 的倍數（不用浮點數保存時數）
    minutes      integer       NOT NULL,
    -- 前端每次「邏輯上的新增」產生的識別碼；同一員工不可重複，避免重送建立兩筆
    request_id   uuid          NOT NULL,
    version      bigint        NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL,
    updated_at   timestamptz   NOT NULL,
    CONSTRAINT ck_timesheet_minutes CHECK (minutes BETWEEN 15 AND 1440 AND minutes % 15 = 0),
    CONSTRAINT ck_timesheet_description CHECK (length(btrim(description)) > 0),
    CONSTRAINT uq_timesheet_employee_request UNIQUE (employee_id, request_id)
);

-- 本人依日期範圍查詢（日期新到舊、同日期依 id），以及計算某人某日合計
CREATE INDEX ix_timesheet_employee_date ON timesheet_entry (employee_id, work_date, id);
-- 專案外鍵的索引（查詢某專案的工時、外鍵檢查）
CREATE INDEX ix_timesheet_project ON timesheet_entry (project_id);
