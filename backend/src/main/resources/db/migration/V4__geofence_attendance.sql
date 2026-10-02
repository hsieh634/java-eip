-- T03.2：公司範圍定位打卡與管理員設定。V1～V3 已套用，不修改；本檔只新增資料表，不改動既有資料表。

-- ---------------------------------------------------------------
-- 1. geofence_setting：單一公司地點（只有一列，id 固定為 1）
-- ---------------------------------------------------------------
CREATE TABLE geofence_setting (
    id                smallint     PRIMARY KEY,
    enabled           boolean      NOT NULL DEFAULT false,
    -- 尚未設定公司中心時為 NULL；0 是合法座標，所以用 NULL 表示「未設定」
    center_latitude   double precision,
    center_longitude  double precision,
    radius_meters     integer      NOT NULL DEFAULT 100,
    scope             varchar(10)  NOT NULL DEFAULT 'BOTH',
    -- 每次修改 +1；管理員提交時比對，過期的提交被拒絕
    version           bigint       NOT NULL DEFAULT 0,
    updated_by        bigint       REFERENCES employee (id) ON DELETE RESTRICT,
    updated_at        timestamptz,
    CONSTRAINT ck_geofence_singleton CHECK (id = 1),
    CONSTRAINT ck_geofence_center_pair CHECK ((center_latitude IS NULL) = (center_longitude IS NULL)),
    -- BETWEEN 對 NaN、Infinity 都不成立，所以非有限數值也會被擋下
    CONSTRAINT ck_geofence_latitude CHECK (center_latitude IS NULL OR center_latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_geofence_longitude CHECK (center_longitude IS NULL OR center_longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_geofence_radius CHECK (radius_meters BETWEEN 10 AND 5000),
    CONSTRAINT ck_geofence_scope CHECK (scope IN ('CLOCK_IN', 'CLOCK_OUT', 'BOTH')),
    -- 啟用時必須已有公司中心
    CONSTRAINT ck_geofence_enabled_needs_center CHECK (NOT enabled OR center_latitude IS NOT NULL),
    CONSTRAINT ck_geofence_updated_pair CHECK ((updated_by IS NULL) = (updated_at IS NULL))
);

-- 初始狀態：關閉、公司中心未設定、半徑 100 公尺、上下班都限制（不填入任何虛構位置）
INSERT INTO geofence_setting (id) VALUES (1);

-- ---------------------------------------------------------------
-- 2. geofence_setting_history：設定變更歷程（只新增）
-- ---------------------------------------------------------------
CREATE TABLE geofence_setting_history (
    id                       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 修改後的版本
    setting_version          bigint           NOT NULL,
    before_enabled           boolean          NOT NULL,
    before_center_latitude   double precision,
    before_center_longitude  double precision,
    before_radius_meters     integer          NOT NULL,
    before_scope             varchar(10)      NOT NULL,
    after_enabled            boolean          NOT NULL,
    after_center_latitude    double precision,
    after_center_longitude   double precision,
    after_radius_meters      integer          NOT NULL,
    after_scope              varchar(10)      NOT NULL,
    reason                   varchar(500)     NOT NULL,
    actor_id                 bigint           NOT NULL REFERENCES employee (id) ON DELETE RESTRICT,
    occurred_at              timestamptz      NOT NULL,
    CONSTRAINT ck_geofence_history_reason CHECK (length(btrim(reason)) > 0)
);

CREATE INDEX ix_geofence_setting_history_id ON geofence_setting_history (id DESC);

-- ---------------------------------------------------------------
-- 3. attendance_clock_event：成功打卡的原始證據（只新增）
--    與可更正的 attendance 分開：更正、作廢、補登都不會改寫這裡
-- ---------------------------------------------------------------
CREATE TABLE attendance_clock_event (
    id                       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id              bigint           NOT NULL REFERENCES employee (id) ON DELETE RESTRICT,
    attendance_id            bigint           NOT NULL REFERENCES attendance (id) ON DELETE RESTRICT,
    action                   varchar(10)      NOT NULL,
    -- 伺服器接受打卡的時間（來自伺服器 Clock）
    accepted_at              timestamptz      NOT NULL,
    location_required        boolean          NOT NULL,
    -- 實際採用的設定快照
    setting_version          bigint           NOT NULL,
    setting_enabled          boolean          NOT NULL,
    setting_scope            varchar(10)      NOT NULL,
    setting_center_latitude  double precision,
    setting_center_longitude double precision,
    setting_radius_meters    integer          NOT NULL,
    -- 只有受限制時才保存員工的定位與後端計算的距離
    latitude                 double precision,
    longitude                double precision,
    accuracy_meters          double precision,
    captured_at              timestamptz,
    distance_meters          double precision,
    CONSTRAINT ck_clock_event_action CHECK (action IN ('CLOCK_IN', 'CLOCK_OUT')),
    CONSTRAINT ck_clock_event_location CHECK (
        location_required = (latitude IS NOT NULL AND longitude IS NOT NULL AND accuracy_meters IS NOT NULL
                             AND captured_at IS NOT NULL AND distance_meters IS NOT NULL)),
    CONSTRAINT ck_clock_event_no_location_when_free CHECK (
        location_required OR (latitude IS NULL AND longitude IS NULL AND accuracy_meters IS NULL
                              AND captured_at IS NULL AND distance_meters IS NULL)),
    CONSTRAINT ck_clock_event_required_center CHECK (
        NOT location_required OR (setting_enabled AND setting_center_latitude IS NOT NULL AND setting_center_longitude IS NOT NULL))
);

-- 刻意不建立 (attendance_id, action) 唯一限制：撤銷下班後再下班，同一筆出勤會合法地有第二筆下班事件
CREATE INDEX ix_clock_event_attendance ON attendance_clock_event (attendance_id, id);
CREATE INDEX ix_clock_event_employee ON attendance_clock_event (employee_id, id);

-- ---------------------------------------------------------------
-- 4. 只新增：設定歷程與打卡事件連直接下 SQL 的 UPDATE／DELETE 也擋下（TRUNCATE 僅供測試庫清理）
-- ---------------------------------------------------------------
CREATE FUNCTION reject_append_only_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (% not allowed)', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER trg_geofence_setting_history_immutable
    BEFORE UPDATE OR DELETE ON geofence_setting_history
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_change();

CREATE TRIGGER trg_attendance_clock_event_immutable
    BEFORE UPDATE OR DELETE ON attendance_clock_event
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_change();
