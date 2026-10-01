-- T03：上下班打卡。V1 已套用，不修改；本檔是新的版本。
CREATE TABLE attendance (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- RESTRICT：有出勤紀錄的員工不能被刪除，出勤歷史不會被連帶清掉
    employee_id  bigint      NOT NULL REFERENCES employee (id) ON DELETE RESTRICT,
    -- 業務日期（Asia/Taipei 的日曆日），由後端依打卡時間點換算後寫入
    work_date    date        NOT NULL,
    -- 實際時間點（UTC 時間點，與連線時區無關）
    clock_in_at  timestamptz NOT NULL,
    clock_out_at timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    -- 一人一天一筆：並行重複上班的最終裁判；同時也是「某人某日／某區間」查詢的索引
    CONSTRAINT uq_attendance_employee_work_date UNIQUE (employee_id, work_date),
    CONSTRAINT ck_attendance_clock_out_after_in CHECK (clock_out_at IS NULL OR clock_out_at >= clock_in_at)
);
