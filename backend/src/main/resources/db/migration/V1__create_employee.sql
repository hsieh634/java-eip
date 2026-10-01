-- T01：最小身份／角色模型。已套用的 migration 不可修改；之後的變更請新增 V2__...
CREATE TABLE employee (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_no   varchar(20)  NOT NULL,
    username      varchar(50)  NOT NULL,
    display_name  varchar(100) NOT NULL,
    -- DelegatingPasswordEncoder 的輸出（例如 {bcrypt}$2a$10$...），絕不存明文
    password_hash varchar(255) NOT NULL,
    role          varchar(20)  NOT NULL,
    enabled       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_employee_employee_no UNIQUE (employee_no),
    CONSTRAINT uq_employee_username UNIQUE (username),
    -- 避免 Max 與 max 被視為兩個帳號
    CONSTRAINT ck_employee_username_lower CHECK (username = lower(username)),
    CONSTRAINT ck_employee_role CHECK (role IN ('EMPLOYEE', 'MANAGER', 'ADMIN'))
);
