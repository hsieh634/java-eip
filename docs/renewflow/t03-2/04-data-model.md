# T03.2 資料模型

Migration：`backend/src/main/resources/db/migration/V4__geofence_attendance.sql`。V1～V3 不修改；V4 只**新增**資料表，不改動 `employee`、`attendance`、`attendance_request`、`attendance_history`。

## 1. geofence_setting（單一列）

| 欄位 | 型別 | 說明 |
|---|---|---|
| `id` | smallint PK | 固定為 1（`ck_geofence_singleton`） |
| `enabled` | boolean | 預設 false |
| `center_latitude`／`center_longitude` | double precision | 預設 NULL＝未設定；成對（`ck_geofence_center_pair`）；-90～90／-180～180（`BETWEEN` 對 NaN、Infinity 不成立） |
| `radius_meters` | integer | 預設 100；10～5000 |
| `scope` | varchar(10) | `CLOCK_IN`／`CLOCK_OUT`／`BOTH`；預設 BOTH |
| `version` | bigint | 每次修改 +1 |
| `updated_by`／`updated_at` | FK／timestamptz | 最近修改人與時間；成對 |

`ck_geofence_enabled_needs_center`：啟用時必須有中心。後端也做相同驗證（回 400），資料庫是最後一道。

## 2. geofence_setting_history（只新增）

修改後版本、修改前後的五個欄位（enabled、中心、半徑、scope）、原因（非空白）、`actor_id`、`occurred_at`。觸發器擋 UPDATE／DELETE。

## 3. attendance_clock_event（只新增）

| 欄位 | 說明 |
|---|---|
| `employee_id`、`attendance_id` | 員工與出勤紀錄 |
| `action` | `CLOCK_IN`／`CLOCK_OUT` |
| `accepted_at` | 伺服器接受時間（Clock） |
| `location_required` | 當次是否要求定位 |
| `setting_version`、`setting_enabled`、`setting_scope`、`setting_center_*`、`setting_radius_meters` | 實際採用的設定快照 |
| `latitude`、`longitude`、`accuracy_meters`、`captured_at`、`distance_meters` | 只在受限制時有值（兩個 CHECK 保證「受限制 ⇔ 全部有值」） |

- 沒有 `(attendance_id, action)` 唯一限制：撤銷下班後再下班會產生第二筆合法的下班事件。
- 更正、作廢、補登、核准都不寫這張表；補登的紀錄因此沒有任何打卡事件。
- 觸發器擋 UPDATE／DELETE；外鍵 RESTRICT 使有事件的出勤與員工無法被刪除。

## 4. 關鍵 SQL

```sql
-- 打卡：取得設定快照並加共享鎖（同一交易內，直到提交）
select id, enabled, center_latitude, center_longitude, radius_meters, scope, version, updated_by, updated_at
  from geofence_setting where id = 1 for share;

-- 修改設定：先鎖住（等待進行中的打卡），再以版本條件更新
select ... from geofence_setting where id = 1 for update;
update geofence_setting set enabled = ?, center_latitude = ?, center_longitude = ?, radius_meters = ?, scope = ?,
       version = version + 1, updated_by = ?, updated_at = ?
 where id = 1 and version = ?;
```

## 5. 升級

既有 V3 資料庫套用 V4：只新增三張表與一列預設設定。驗證見 `GeofenceSchemaTest`（V3 → V4，員工、有效／作廢出勤、申請、歷程原封不動）。
