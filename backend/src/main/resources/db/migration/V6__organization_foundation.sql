-- T05 前置：最小組織與員工管理。V1～V5 已套用，不修改。
-- 既有員工的部門與主管預設為 NULL（未指派），ID、帳號、角色、啟用狀態、密碼雜湊全部保留。
-- 不在這裡建立任何部門、示範公司或員工資料。

-- ---------------------------------------------------------------
-- 1. department：平面部門（不刪除，只停用）
-- ---------------------------------------------------------------
CREATE TABLE department (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 已正規化（去除前後空白、大寫）；建立後不可修改
    code        varchar(32)  NOT NULL,
    name        varchar(100) NOT NULL,
    active      boolean      NOT NULL DEFAULT true,
    version     bigint       NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT uq_department_code UNIQUE (code),
    CONSTRAINT ck_department_code CHECK (code ~ '^[A-Z0-9_-]{2,32}$'),
    CONSTRAINT ck_department_name CHECK (length(btrim(name)) > 0 AND name = btrim(name))
);

-- ---------------------------------------------------------------
-- 2. employee：部門、直屬主管（自關聯）、版本
-- ---------------------------------------------------------------
ALTER TABLE employee
    ADD COLUMN department_id bigint REFERENCES department (id) ON DELETE RESTRICT,
    -- 直屬主管：指向另一位員工（自關聯外鍵）；NULL＝沒有主管
    ADD COLUMN manager_id    bigint REFERENCES employee (id) ON DELETE RESTRICT,
    -- 組織管理修改時 +1；管理員提交時比對，過期的表單被拒絕
    ADD COLUMN version       bigint NOT NULL DEFAULT 0,
    -- 直接自指由資料庫擋下；間接循環（A → B → A）由後端在組織鎖內檢查
    ADD CONSTRAINT ck_employee_not_own_manager CHECK (manager_id IS NULL OR manager_id <> id);

CREATE INDEX ix_employee_department ON employee (department_id);
CREATE INDEX ix_employee_manager ON employee (manager_id);

-- ---------------------------------------------------------------
-- 3. organization_history：組織管理操作歷程（只新增；不含密碼）
-- ---------------------------------------------------------------
CREATE TABLE organization_history (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target_type    varchar(20)  NOT NULL,
    employee_id    bigint       REFERENCES employee (id) ON DELETE RESTRICT,
    department_id  bigint       REFERENCES department (id) ON DELETE RESTRICT,
    action         varchar(40)  NOT NULL,
    reason         varchar(500),
    -- 前後值（JSON 陣列文字：[{field, before, after}]），只用於顯示
    changes        text         NOT NULL,
    -- ADMIN 操作時為操作者；系統初始化（dev 示範）時為 NULL，以 source 標示來源
    actor_id       bigint       REFERENCES employee (id) ON DELETE RESTRICT,
    source         varchar(30)  NOT NULL,
    occurred_at    timestamptz  NOT NULL,
    CONSTRAINT ck_org_history_target CHECK (
        (target_type = 'EMPLOYEE' AND employee_id IS NOT NULL AND department_id IS NULL)
        OR (target_type = 'DEPARTMENT' AND department_id IS NOT NULL AND employee_id IS NULL)),
    CONSTRAINT ck_org_history_action CHECK (action IN ('DEPARTMENT_CREATED', 'DEPARTMENT_UPDATED', 'EMPLOYEE_CREATED', 'EMPLOYEE_UPDATED')),
    CONSTRAINT ck_org_history_source CHECK (source IN ('ADMIN', 'SYSTEM_DEMO_SEED')),
    CONSTRAINT ck_org_history_actor CHECK ((source = 'ADMIN') = (actor_id IS NOT NULL))
);

CREATE INDEX ix_org_history_employee ON organization_history (employee_id, id);
CREATE INDEX ix_org_history_department ON organization_history (department_id, id);

-- 只新增：沿用 V4 的 reject_append_only_change()（TRUNCATE 僅供測試庫清理）
CREATE TRIGGER trg_organization_history_immutable
    BEFORE UPDATE OR DELETE ON organization_history
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_change();
