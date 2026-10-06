-- T05：請假申請與主管審核。V1～V6 已套用，不修改。
-- 不建立任何預設申請或指定審核設定，也不修改既有資料。

-- ---------------------------------------------------------------
-- 1. leave_approver_setting：指定請假審核人（優先於直屬主管；不影響 employee.manager_id）
-- ---------------------------------------------------------------
CREATE TABLE leave_approver_setting (
    employee_id  bigint      PRIMARY KEY REFERENCES employee (id) ON DELETE RESTRICT,
    -- NULL＝已移除指定，採直屬主管
    approver_id  bigint      REFERENCES employee (id) ON DELETE RESTRICT,
    -- 第一次設定為 1；沒有列等同版本 0
    version      bigint      NOT NULL,
    updated_by   bigint      NOT NULL REFERENCES employee (id) ON DELETE RESTRICT,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT ck_leave_setting_not_self CHECK (approver_id IS NULL OR approver_id <> employee_id),
    CONSTRAINT ck_leave_setting_version CHECK (version >= 1)
);

-- ---------------------------------------------------------------
-- 2. leave_request：一個台北業務日期內的一段時間 [start_time, end_time)
-- ---------------------------------------------------------------
CREATE TABLE leave_request (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id      bigint       NOT NULL REFERENCES employee (id) ON DELETE RESTRICT,
    -- 前端的提交識別碼：同一位員工不可重複（結果不明時重送同一個）
    request_id       uuid         NOT NULL,
    leave_type       varchar(20)  NOT NULL,
    work_date        date         NOT NULL,
    start_time       time(0)      NOT NULL,
    end_time         time(0)      NOT NULL,
    -- 由後端計算，資料庫再確認一次
    minutes          integer      NOT NULL,
    reason           varchar(500) NOT NULL,
    status           varchar(20)  NOT NULL,
    -- 審核人快照：送出時決定，只有 ADMIN 改派會改變
    approver_id      bigint       NOT NULL REFERENCES employee (id) ON DELETE RESTRICT,
    approver_source  varchar(20)  NOT NULL,
    decided_by       bigint       REFERENCES employee (id) ON DELETE RESTRICT,
    decided_at       timestamptz,
    decision_reason  varchar(500),
    version          bigint       NOT NULL DEFAULT 0,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    CONSTRAINT uq_leave_request_employee_request UNIQUE (employee_id, request_id),
    CONSTRAINT ck_leave_request_type CHECK (leave_type IN ('PERSONAL', 'SICK', 'ANNUAL')),
    CONSTRAINT ck_leave_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_leave_request_source CHECK (approver_source IN ('DESIGNATED', 'MANAGER', 'REASSIGNED')),
    -- 半小時刻度、結束晚於開始（不跨午夜）
    CONSTRAINT ck_leave_request_grid CHECK (
        extract(second FROM start_time) = 0 AND extract(minute FROM start_time) IN (0, 30)
        AND extract(second FROM end_time) = 0 AND extract(minute FROM end_time) IN (0, 30)),
    CONSTRAINT ck_leave_request_order CHECK (end_time > start_time),
    CONSTRAINT ck_leave_request_minutes CHECK (minutes > 0 AND minutes = extract(epoch FROM (end_time - start_time)) / 60),
    CONSTRAINT ck_leave_request_reason CHECK (length(btrim(reason)) > 0 AND reason = btrim(reason)),
    -- 禁止自審（最後一道防線）
    CONSTRAINT ck_leave_request_not_self_approver CHECK (approver_id <> employee_id),
    -- 待審時沒有結案資訊；結案時必須有決定者與時間；退回必須有原因
    CONSTRAINT ck_leave_request_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL AND decision_reason IS NULL)
        OR (status <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT ck_leave_request_reject_reason CHECK (status <> 'REJECTED' OR length(btrim(decision_reason)) > 0)
);

CREATE INDEX ix_leave_request_employee_date ON leave_request (employee_id, work_date);
CREATE INDEX ix_leave_request_approver_status ON leave_request (approver_id, status);

-- ---------------------------------------------------------------
-- 3. leave_history：請假歷程（只新增）
-- ---------------------------------------------------------------
CREATE TABLE leave_history (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    leave_request_id     bigint       REFERENCES leave_request (id) ON DELETE RESTRICT,
    subject_employee_id  bigint       REFERENCES employee (id) ON DELETE RESTRICT,
    action               varchar(40)  NOT NULL,
    from_status          varchar(20),
    to_status            varchar(20),
    before_approver_id   bigint       REFERENCES employee (id) ON DELETE RESTRICT,
    after_approver_id    bigint       REFERENCES employee (id) ON DELETE RESTRICT,
    reason               varchar(500),
    actor_id             bigint       NOT NULL REFERENCES employee (id) ON DELETE RESTRICT,
    occurred_at          timestamptz  NOT NULL,
    CONSTRAINT ck_leave_history_action CHECK (action IN
        ('SUBMITTED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'REASSIGNED', 'APPROVER_SETTING_CHANGED')),
    CONSTRAINT ck_leave_history_target CHECK (
        (action = 'APPROVER_SETTING_CHANGED' AND subject_employee_id IS NOT NULL AND leave_request_id IS NULL)
        OR (action <> 'APPROVER_SETTING_CHANGED' AND leave_request_id IS NOT NULL AND subject_employee_id IS NULL))
);

CREATE INDEX ix_leave_history_request ON leave_history (leave_request_id, id);
CREATE INDEX ix_leave_history_subject ON leave_history (subject_employee_id, id);

-- 只新增：沿用 V4 的 reject_append_only_change()（TRUNCATE 僅供測試庫清理）
CREATE TRIGGER trg_leave_history_immutable
    BEFORE UPDATE OR DELETE ON leave_history
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_change();
