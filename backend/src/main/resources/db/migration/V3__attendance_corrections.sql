-- T03.1：出勤更正、重新打卡申請與管理權限。V1、V2 已套用，不修改；本檔是新的版本。

-- ---------------------------------------------------------------
-- 1. attendance：版本、作廢、來源。既有資料以預設值升級（原封不動）。
-- ---------------------------------------------------------------
ALTER TABLE attendance
    -- 每次修改 +1；申請記住建立當下的版本，核准時必須相同，否則是「過時」
    ADD COLUMN version      bigint      NOT NULL DEFAULT 0,
    -- 作廢（重新打卡）：保留原時間，不硬刪
    ADD COLUMN voided_at    timestamptz,
    ADD COLUMN voided_by    bigint      REFERENCES employee (id) ON DELETE RESTRICT,
    -- CLOCK＝員工打卡；MANUAL＝經核准／直接更正補登建立
    ADD COLUMN source       varchar(10) NOT NULL DEFAULT 'CLOCK',
    ADD COLUMN corrected_at timestamptz,
    ADD CONSTRAINT ck_attendance_source CHECK (source IN ('CLOCK', 'MANUAL')),
    ADD CONSTRAINT ck_attendance_void_pair CHECK ((voided_at IS NULL) = (voided_by IS NULL));

-- 無條件的唯一限制無法保留多筆作廢歷史 → 改為「每人每日最多一筆『有效』紀錄」
ALTER TABLE attendance DROP CONSTRAINT uq_attendance_employee_work_date;
CREATE UNIQUE INDEX uq_attendance_active_employee_work_date
    ON attendance (employee_id, work_date) WHERE voided_at IS NULL;
-- 查歷史（含作廢）用
CREATE INDEX ix_attendance_employee_work_date ON attendance (employee_id, work_date);

-- ---------------------------------------------------------------
-- 2. attendance_request：員工的申請（希望值；核准前不改正式出勤）
-- ---------------------------------------------------------------
CREATE TABLE attendance_request (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id           bigint       NOT NULL REFERENCES employee (id) ON DELETE RESTRICT,
    request_type          varchar(20)  NOT NULL,
    status                varchar(20)  NOT NULL,
    work_date             date         NOT NULL,
    -- 申請當下的有效紀錄與版本；沒有紀錄（補登）則為 NULL
    target_attendance_id  bigint       REFERENCES attendance (id) ON DELETE RESTRICT,
    target_version        bigint,
    original_clock_in_at  timestamptz,
    original_clock_out_at timestamptz,
    requested_clock_in_at  timestamptz,
    requested_clock_out_at timestamptz,
    reason                varchar(500) NOT NULL,
    reviewer_id           bigint       REFERENCES employee (id) ON DELETE RESTRICT,
    reviewed_at           timestamptz,
    review_comment        varchar(500),
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_attendance_request_type CHECK (request_type IN ('RESET_TODAY', 'CORRECT_TIME')),
    CONSTRAINT ck_attendance_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_attendance_request_reason CHECK (length(btrim(reason)) > 0),
    CONSTRAINT ck_attendance_request_correct_in CHECK (request_type <> 'CORRECT_TIME' OR requested_clock_in_at IS NOT NULL),
    CONSTRAINT ck_attendance_request_out_after_in CHECK (
        requested_clock_out_at IS NULL OR requested_clock_in_at IS NULL OR requested_clock_out_at >= requested_clock_in_at),
    -- 只有被核准／退回才有審核人與時間
    CONSTRAINT ck_attendance_request_review CHECK (
        (status IN ('APPROVED', 'REJECTED')) = (reviewer_id IS NOT NULL AND reviewed_at IS NOT NULL)),
    CONSTRAINT ck_attendance_request_reject_comment CHECK (status <> 'REJECTED' OR length(btrim(review_comment)) > 0)
);

-- 同一員工同一日期最多一筆「待審」申請：並行重複提出的最終裁判
CREATE UNIQUE INDEX uq_attendance_request_pending
    ON attendance_request (employee_id, work_date) WHERE status = 'PENDING';
CREATE INDEX ix_attendance_request_status ON attendance_request (status, id);
CREATE INDEX ix_attendance_request_employee ON attendance_request (employee_id, id);

-- ---------------------------------------------------------------
-- 3. attendance_history：操作歷程（只新增）
-- ---------------------------------------------------------------
CREATE TABLE attendance_history (
    id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id            bigint      NOT NULL REFERENCES employee (id) ON DELETE RESTRICT,
    work_date              date        NOT NULL,
    operation              varchar(30) NOT NULL,
    request_id             bigint      REFERENCES attendance_request (id) ON DELETE RESTRICT,
    request_type           varchar(20),
    attendance_id          bigint      REFERENCES attendance (id) ON DELETE RESTRICT,
    target_version         bigint,
    reason                 varchar(500),
    original_clock_in_at   timestamptz,
    original_clock_out_at  timestamptz,
    requested_clock_in_at  timestamptz,
    requested_clock_out_at timestamptz,
    -- 實際修改後的值；沒有修改出勤的操作（提出、撤回、退回）為 NULL
    after_clock_in_at      timestamptz,
    after_clock_out_at     timestamptz,
    after_voided           boolean,
    result                 varchar(20),
    actor_id               bigint      NOT NULL REFERENCES employee (id) ON DELETE RESTRICT,
    occurred_at            timestamptz NOT NULL,
    CONSTRAINT ck_attendance_history_operation CHECK (operation IN (
        'REQUEST_SUBMITTED', 'REQUEST_CANCELLED', 'REQUEST_APPROVED', 'REQUEST_REJECTED',
        'DIRECT_CORRECTION', 'DIRECT_VOID'))
);

CREATE INDEX ix_attendance_history_employee ON attendance_history (employee_id, id);

-- 歷程只能新增：連直接下 SQL 的 UPDATE／DELETE 也擋下（TRUNCATE 不受影響，僅供測試庫清理）
CREATE FUNCTION attendance_history_reject_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'attendance_history is append-only (% not allowed)', TG_OP;
END;
$$;

CREATE TRIGGER trg_attendance_history_immutable
    BEFORE UPDATE OR DELETE ON attendance_history
    FOR EACH ROW EXECUTE FUNCTION attendance_history_reject_change();
