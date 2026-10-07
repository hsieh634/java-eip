-- 原出勤與所有關聯保持原樣；每日有效唯一改為每日有效未完成唯一。
DROP INDEX uq_attendance_active_employee_work_date;
CREATE UNIQUE INDEX uq_attendance_open_employee_work_date
    ON attendance (employee_id, work_date) WHERE voided_at IS NULL AND clock_out_at IS NULL;
CREATE TABLE attendance_clock_submission (
    employee_id bigint NOT NULL REFERENCES employee(id) ON DELETE RESTRICT,
    request_id uuid NOT NULL,
    action varchar(10) NOT NULL CHECK (action IN ('CLOCK_IN', 'CLOCK_OUT')),
    fingerprint varchar(64) NOT NULL,
    attendance_id bigint NOT NULL REFERENCES attendance(id) ON DELETE RESTRICT,
    response_json text NOT NULL,
    PRIMARY KEY (employee_id, request_id)
);
