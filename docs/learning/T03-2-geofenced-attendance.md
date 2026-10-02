# T03.2：公司範圍定位打卡與管理員設定——技術文件

對應版本：基準 `4f14ee4`，T03.2 commit 見交付報告（標題 `feat: T03.2 ...`）。規格：`docs/renewflow/t03-2/`。
狀態：**已實作／作者自測完成／等待 ChatGPT 複審**。本文件只記錄作者自己的驗證，不代表已通過審查。

---

## 1. 三分鐘摘要

**用途**：管理員設定一個公司中心與允許半徑；啟用後，員工按下「受限制」的上班或下班按鈕時，瀏覽器提供一次位置，**後端**計算距離，在範圍內才接受打卡。

**主要流程**：按鈕 → 前端取最新定位要求 → （需要時）`getCurrentPosition` → 送出原始定位 → 後端在打卡交易內「鎖住設定快照 → 驗證定位品質 → Haversine 距離 → 範圍判斷 → 原本的原子打卡 SQL → 寫入只新增的打卡證據」→ 前端重新查詢今日狀態。

**最重要的規則**
1. 距離與是否在範圍內只由後端決定；前端只送緯度、經度、accuracy、取得時間。
2. 先驗證精度（≤ 50 公尺）與時間（伺服器現在 −60～+10 秒），再算距離；`distance ≤ radius` 才通過；不取整、不把 accuracy 加進半徑。
3. 定位拒絕發生在任何寫入之前；打卡與證據同交易；設定讀取失敗 → 500，絕不當作關閉。
4. 打卡用 `FOR SHARE` 鎖住設定列取得快照，設定修改用 `FOR UPDATE`＋版本條件：先後明確、不混用不同版本。
5. 證據獨立且只新增：更正、作廢、補登都不改寫它，也不替補登製造證據。
6. **限制**：瀏覽器定位可被模擬或偽造，本功能不能完全防止代打卡；手機用區網 HTTP 開網站時通常無法定位（需要 HTTPS），本階段沒有實機驗證。

## 2. 操作入口

| 誰 | 畫面 | 輸入 | 結果 |
|---|---|---|---|
| ADMIN | 左側「定位打卡設定」（`#/admin/geofence`） | 啟用、緯度、經度、半徑、適用操作、原因 | 確認前後差異 → 儲存 → 版本 +1、最近修改人、設定歷程 |
| 員工（含 ADMIN 自己） | 首頁或「出勤打卡」的今日打卡卡片 | 按上班／下班；需要時允許瀏覽器取得位置 | 成功；或顯示拒絕原因（權限、逾時、不可用、不支援、非安全來源、精度、範圍外的距離與半徑）與「重試」 |

操作步驟見 README 第 10 節。

## 3. 實際程式碼定位

| 檔案 | 類別／方法 | 責任 |
|---|---|---|
| `backend/.../geofence/GeofenceGate.java` | `checkForClock`（35 行起）、`policy` | 定位關卡：快照、驗證、距離、範圍 |
| `backend/.../geofence/GeoDistance.java` | `haversineMeters`（17）、`isWithin`（32） | 球面距離與範圍判斷 |
| `backend/.../geofence/GeofenceRules.java` | 常數 | 半徑 10～5000、精度 50、時間窗口 |
| `backend/.../geofence/GeofenceSettingRepository.java` | `lockForClock`（22，FOR SHARE）、`lockForUpdate`（25）、`updateIfVersion`（41） | 鎖與版本條件更新 |
| `backend/.../geofence/GeofenceSettingsService.java` | `update`（64 起） | 驗證 → 鎖 → 版本 → 更新 → 歷程 |
| `backend/.../geofence/GeofenceSnapshot.java` | record | 一次判斷只用一份設定 |
| `backend/.../attendance/AttendanceService.java` | `clockIn`（41）、`clockOut`（61）、`recordWithEvidence`（121） | 打卡交易：關卡 → SQL → 證據 |
| `backend/.../attendance/ClockEventLog.java`、`AttendanceClockEvent.java` | `record` | 只新增的打卡證據 |
| `backend/.../geofence/GeofenceDtos.java` | `ClockLocationRequest`、`UpdateSettingsRequest`… | DTO 與 Bean Validation |
| `backend/.../shared/web/GlobalExceptionHandler.java` | `onGeofence`、`onOutsideGeofence` | 400／422／403／409 的 JSON |
| `backend/src/main/resources/db/migration/V4__geofence_attendance.sql` | — | 三張新表、預設設定、觸發器 |
| `frontend/src/lib/geolocation.ts` | `getCurrentLocation`（32）、`POSITION_OPTIONS` | 取一次位置、錯誤分類 |
| `frontend/src/attendance/useTodayAttendance.ts` | `perform`（191 起）、`locate`（160 起）、`stillAllowed`（29） | 打卡流程與過期 callback 防護 |
| `frontend/src/components/TodayAttendanceCard.tsx` | `ClockActions`、`LocationPolicyNote`、`RetryButton` | 階段、說明、重試 |
| `frontend/src/pages/GeofenceSettingsPage.tsx`、`attendance/geofenceForm.ts` | `SettingsForm`、`parseForm` | 管理頁與前端驗證 |

## 4. 完整資料流（受限制的上班打卡）

```
[畫面] 按「上班打卡」
  └ useTodayAttendance.perform('in')：inFlight=true；action 固定為 'in'
     ├ GET /api/attendance/location-policy → { clockInRequired: true, radiusMeters, maxAccuracyMeters }
     ├ navigator.geolocation.getCurrentPosition({ enableHighAccuracy, maximumAge: 0, timeout: 15000 })
     ├ 回來後：仍在畫面？今日仍是「尚未上班」？accuracy ≤ 上限？
     └ POST /api/attendance/clock-in  { latitude, longitude, accuracy, capturedAt }  ＋ X-XSRF-TOKEN
[Security] CsrfFilter → Session → ActiveAccountFilter（帳號仍有效、角色未變）→ 授權（已登入）
[Controller] AttendanceController.clockIn：@Valid（範圍、NaN/Infinity）；員工 ID 取自 Session
[Service] AttendanceService.clockIn（@Transactional 開始）
  ├ now = Clock
  ├ GeofenceGate.checkForClock（MANDATORY）
  │   ├ SELECT * FROM geofence_setting WHERE id = 1 FOR SHARE   → 快照（版本 N）
  │   ├ 需要定位？→ 欄位齊全？有限數值？精度？時間窗口？
  │   ├ Haversine(中心, 位置) = d
  │   └ d ≤ radius ？否 → OutsideGeofenceException（403）
  ├ INSERT … ON CONFLICT (employee_id, work_date) WHERE voided_at IS NULL DO NOTHING
  ├ SELECT 剛寫入的有效紀錄
  └ INSERT INTO attendance_clock_event（設定版本 N 與快照、座標、精度、時間、距離）
[提交] 釋放 FOR SHARE
[畫面] 回應 → reload() 重新查詢今日 → 顯示「工作中」
```

## 5. 核心程式解說

### 5.1 定位關卡（`GeofenceGate.checkForClock`）

```java
@Transactional(propagation = Propagation.MANDATORY)
public LocationDecision checkForClock(ClockAction action, ClockLocationRequest location, Instant now) {
    GeofenceSnapshot setting = settingRepository.lockForClock()
            .orElseThrow(() -> new IllegalStateException("geofence setting row is missing"))
            .snapshot();
    if (!setting.requiresLocation(action)) return LocationDecision.notRequired(setting);
    ...
    if (accuracy > GeofenceRules.MAX_ACCURACY_METERS) throw … "LOCATION_ACCURACY_TOO_LOW"
    if (captured.isBefore(now.minus(LOCATION_MAX_AGE)) || captured.isAfter(now.plus(LOCATION_MAX_AHEAD))) throw … "LOCATION_TIME_INVALID"
    double distance = GeoDistance.haversineMeters(setting.centerLatitude(), setting.centerLongitude(), latitude, longitude);
    if (!GeoDistance.isWithin(distance, setting.radiusMeters())) throw new OutsideGeofenceException(distance, setting.radiusMeters());
    return new LocationDecision(setting, true, latitude, longitude, accuracy, captured, distance);
}
```

- **輸入**：操作、前端的原始定位（可能為 null）、伺服器時間（與出勤時間同一個值）。
- **輸出**：通過時的判斷結果（含快照，給證據用）；不通過時丟出帶 code 的例外。
- **副作用**：在呼叫端交易內取得設定列的共享鎖，持有到提交。
- **`MANDATORY`**：如果有人在交易外呼叫，Spring 直接丟例外。沒有交易就沒有「持有到提交」的鎖，判斷與寫入之間設定可能被改——寧可失敗也不要悄悄失去保證。
- **讀不到設定 → `IllegalStateException` → 500**：不是「當作關閉」。這是安全預設：故障時拒絕（fail closed）。

### 5.2 Haversine（`GeoDistance`）

```java
double a = sin²(Δφ/2) + cos φ1 · cos φ2 · sin²(Δλ/2);
a = Math.min(1.0, Math.max(0.0, a));   // 浮點誤差可能讓 a 略大於 1 → asin(√a) 會變 NaN
return 2 * R * Math.asin(Math.sqrt(a));
```

- **單位**：輸入是十進位**度**，三角函數要**弧度**（`Math.toRadians`）；輸出公尺（R = 6,371,008.8 公尺）。
- **為什麼夾住 a**：同一點或對蹠點附近，浮點計算可能得到 1.0000000000000002，`asin` 會回 NaN；NaN 和任何數比較都是 false，`NaN <= 100` 為 false，結果「被拒絕」——雖然安全，但原因難懂。
- **邊界**：`isWithin(distance, radius)` 是 `distance <= radius`。`GeoDistanceTest` 驗證 100.0 → 內、`Math.nextUp(100.0)` → 外、100.4 → 外（若先四捨五入成 100 就會誤放行）。
- **跨 ±180 經度**：Δλ 用 sin²，179.9995 與 −179.9995 的差是 359.999 度，sin²(359.999°/2) = sin²(0.001°/2)，所以結果是正確的約 111 公尺。

### 5.3 打卡交易（`AttendanceService.clockIn`）

```java
@Transactional
public AttendanceRecordResponse clockIn(Long employeeId, ClockLocationRequest location) {
    Instant now = now();
    LocalDate today = BusinessTime.workDateOf(now);
    LocationDecision decision = geofenceGate.checkForClock(ClockAction.CLOCK_IN, location, now);  // 先過關卡
    int inserted = attendanceRepository.insertIfAbsent(employeeId, today, now);                    // T03 的原子 SQL
    ...
    return recordWithEvidence(employeeId, today, ClockAction.CLOCK_IN, now, decision);            // 同交易寫證據
}
```

順序就是規則：關卡在寫入**之前**，所以任何定位拒絕都不會改到資料；證據在同一交易，失敗就整筆回滾（`GeofenceTransactionIntegrationTest` T-01、T-02）。

### 5.4 設定修改（`GeofenceSettingsService.update`）

```java
GeofenceSnapshot before = settingRepository.lockForUpdate()...snapshot();   // 等進行中的打卡
if (before.version() != body.expectedVersion()) throw changed();          // 409 SETTING_CHANGED
if (before.sameSettingsAs(...)) throw invalid("沒有變更");
if (settingRepository.updateIfVersion(body.expectedVersion(), ...) == 0) throw changed();   // 最後一道防線
GeofenceSnapshot after = settingRepository.current()...snapshot();
auditLog.record(before, after, reason, actor.getEmployeeId(), now);       // 同交易
```

`before` 是在鎖內讀到的，所以歷程的「修改前」一定是真正被覆蓋的那一版。

### 5.5 前端：過期 callback 與重複點擊（`useTodayAttendance`）

> **第 1 輪複審修正背景（請一併閱讀 [`T03-2-review-fixes.md`](T03-2-review-fixes.md)）**：本節描述的是 T03.2 初版的寫法。複審發現初版的取消防護不完整——
> （R1）只在「定位結果回來之後」檢查，等待定位設定期間切頁，舊操作仍可能定位或送出打卡；
> （R2）今日狀態的舊回應可能覆蓋新回應。初版 E2E G5 只驗證了「定位中切頁」這一種時機，**不能**代表「切頁後一定不送出」已完整驗證。
> 現行程式已改為「掛載有效期間（Lifetime）＋操作識別＋查詢序號」，下方片段保留作為對照。

```ts
const perform = useCallback(async (action: Action) => {
  if (inFlight.current) return              // ref：同一個事件迴圈內的第二次點擊也擋得住
  inFlight.current = true
  ...
  policy = await attendanceApi.fetchLocationPolicy()   // 取不到 → 不打卡，不改用「不限制」
  if (requiresLocation(action, policy)) {
    const located = await locate(action, policy)        // 'abandoned'：元件已卸載
    ...
  }
  await (action === 'in' ? clockIn(location) : clockOut(location))   // action 在按下時就固定
  await reload()                                                     // 不論結果都以後端為準
}, [...])
```

- `inFlight` 用 **ref** 不用 state：state 的更新要等下一次 render，連續兩次 `click()` 會看到同一個舊值；ref 立即生效（E2E G4「快速連按兩次 → 只定位一次、只送出一次」）。
- `mounted` ref：切頁後卡片卸載，晚到的定位結果直接丟棄，不送出（E2E G5）。
- `latestState` ref：定位可能要好幾秒，回來時今日狀態可能已在別的分頁改變；用最新狀態重新確認「這個操作仍成立」。注意不是重新「決定」操作——`action` 來自閉包，所以不會從上班變成下班。
- **重要註解**：`stillAllowed` 在狀態未知時回 true——前端只是改善體驗，後端的 409 才是最終裁決。

## 6. 設計理由與取捨

| 主題 | 選擇 | 為什麼 | 替代與成本 |
|---|---|---|---|
| 判斷位置 | 後端 | 前端可被任意竄改 | 前端判斷：等於沒有限制 |
| 為何仍不能防作弊 | 文件明示 | 後端只能驗證「資料合理」，無法驗證「資料真實」；瀏覽器定位可被 DevTools、模擬定位 App 改變 | 裝置認證、Wi-Fi／IP、人臉：本階段不做，且各有隱私與成本 |
| 設定一致性 | 列鎖（FOR SHARE／FOR UPDATE） | 單一設定列、打卡互不阻擋、先後可測 | SERIALIZABLE：衝突時需重試；樂觀讀：無法保證判斷與證據同版本 |
| 證據 | 獨立只新增表 | 出勤可更正，證據不可改 | 放在 attendance：更正會覆蓋 |
| 精度 | 先檢查、不加進半徑 | 加進半徑會讓精度越差的定位越容易通過 | 「整個誤差圓都在範圍內」（distance + accuracy ≤ radius）：更嚴格，室內常無法打卡 |
| 時間窗口 | −60／+10 秒 | 擋明顯過期的資料；容許小的時鐘偏差 | 更嚴格：手機時鐘稍有偏差就失敗 |
| 前端精度預檢 | 用後端提供的上限 | 省一次請求、即時提示；後端仍檢查 | 只靠後端：也可以，只是多一次往返 |

**學習假設**：單一地點、圓形範圍、固定 50 公尺精度、地球為球體——都不是正式制度。

## 7. 資料模型與 SQL

見 `docs/renewflow/t03-2/04-data-model.md`。重點：

```sql
-- 鎖（程式中的原生 SQL，原文如下）
select * from geofence_setting where id = 1 for share;
select * from geofence_setting where id = 1 for update;
update geofence_setting set enabled = ?, center_latitude = ?, center_longitude = ?, radius_meters = ?, scope = ?,
       version = version + 1, updated_by = ?, updated_at = ? where id = 1 and version = ?;

-- 證據（JPA save 產生；欄位順序為推估，未擷取實際 SQL 日誌）
insert into attendance_clock_event (accepted_at, accuracy_meters, action, attendance_id, captured_at, distance_meters, employee_id,
       latitude, location_required, longitude, setting_center_latitude, setting_center_longitude, setting_enabled,
       setting_radius_meters, setting_scope, setting_version) values (…) returning id;
```

資料庫層的保證：`ck_geofence_singleton`、座標成對、`BETWEEN` 擋 NaN／Infinity、半徑範圍、啟用需中心、事件「受限制 ⇔ 有完整定位」、兩個只新增的觸發器。

## 8. 安全與錯誤

| 情況 | 結果 |
|---|---|
| 未登入 | 401（回登入頁） |
| EMPLOYEE／MANAGER 呼叫設定 API | 403 |
| ADMIN 被停用或降級後的舊 Session | 401 |
| 缺或偽造 CSRF | 403 |
| 座標格式／範圍錯誤、NaN、Infinity | 400 `VALIDATION_FAILED`（第二道：`INVALID_LOCATION`／`INVALID_SETTING`） |
| 受限制卻沒有位置 | 422 `LOCATION_REQUIRED` |
| 精度 > 50 | 422 `LOCATION_ACCURACY_TOO_LOW` |
| 定位時間不在窗口 | 422 `LOCATION_TIME_INVALID` |
| 範圍外 | 403 `OUTSIDE_GEOFENCE`＋`distanceMeters`（未取整）＋`radiusMeters` |
| 設定過期提交 | 409 `SETTING_CHANGED` |
| 重複打卡 | 409（沿用 T03） |
| 設定讀取失敗、證據寫入失敗 | 500，資料不變 |

前端：只有 401 回登入頁；其餘都顯示訊息，網路中斷不自動重送。

### 瀏覽器面（依 MDN 與 W3C Geolocation API）
- **Browser Geolocation ≠ GPS**：瀏覽器用裝置上「最好的可用方式」——GPS、Wi-Fi、IP 都可能；桌機通常沒有 GPS。
- **權限**：第一次呼叫時詢問；可以是 granted／denied／prompt。拒絕 → error code 1。
- **安全來源**：只有 HTTPS 或 localhost／127.0.0.1；非安全來源同樣以 code 1 失敗。前端先檢查 `window.isSecureContext`，訊息才說得清楚。
- **timeout**：不包含等待使用者授權的時間（W3C 規格）。逾時 → code 3；取得失敗 → code 2。
- **accuracy**：95% 信心的誤差半徑（公尺），是估計值。

## 9. 驗證證據

**本次實際執行**

| 指令 | 結果 |
|---|---|
| `cd backend; .\mvnw.cmd test` | 209 個測試，0 失敗（新增：`GeoDistanceTest` 5、`GeofenceSettingsIntegrationTest` 10、`GeofenceClockIntegrationTest` 15、`GeofenceEvidenceIntegrationTest` 3、`GeofenceTransactionIntegrationTest` 7、`GeofenceSchemaTest` 3） |
| `npm run build`、`npm run lint` | 通過 |
| `npm run e2e:geofence`（隔離） | 59/59 |
| `npm run e2e:attendance`、`e2e:corrections`（隔離） | 37/37、74/74 |
| `npm run e2e:smoke`、`e2e:logout`、`e2e:password`（dev，唯讀） | 18/18、23/23、10/10 |
| 變異驗證（每次只改一處，跑相應測試，再還原） | 移除距離判斷 → `GeofenceClockIntegrationTest` 5 個失敗；移除設定版本比對 → `GeofenceSettingsIntegrationTest` 2 個失敗；證據寫入失敗被吞掉 → `GeofenceTransactionIntegrationTest` 2 個失敗；`FOR SHARE` 改成不加鎖 → 同檔 2 個並行測試失敗。還原後全部 209 通過 |
| 開發庫套用 V4 | 套用前後員工、出勤、申請、歷程的逐列雜湊相同；定位設定為關閉、中心未設定、版本 0 |

**沿用先前的證據**：T03／T03.1 的行為（本次重跑的既有測試與 E2E 仍通過）。

**未驗證**：手機實機、真實 GPS 精度、HTTPS、桌機真實 Wi-Fi 定位的精度、防作弊能力。E2E 的定位都是模擬：注入的假 `navigator.geolocation` 證明的是「我們的程式如何處理各種回應」；DevTools 定位模擬證明的是「透過瀏覽器真正的 API 能走通」。兩者都**不能**證明真實裝置能取得足夠精確的位置。

## 10. 技術問答

**Q1. 為什麼範圍判斷一定要在後端？** 前端送來的任何東西（含「我在範圍內」）都能被改。後端自己取設定、自己算距離。追問：那後端就安全了嗎？——不，後端只能確認資料「合理」，無法確認「真實」；位置本身可被偽造。

**Q2. accuracy 為什麼不加進半徑？** accuracy 越大代表越不確定；加進半徑等於「越不準越容易通過」。本專案先要求 accuracy ≤ 50，再以中心點判斷。追問：更嚴格的做法？——`distance + accuracy ≤ radius`（整個誤差圓都在範圍內），代價是室內常打不了卡。

**Q3. Haversine 的單位陷阱？** 經緯度是度，三角函數要弧度；R 用公尺結果才是公尺。浮點誤差可能讓 `a` 超過 1 → NaN，要夾住。

**Q4. 為什麼用 `FOR SHARE` 而不是 `FOR UPDATE` 鎖打卡？** 打卡彼此不需要互斥（各自寫不同列），共享鎖讓它們並行；只有「修改設定」需要等所有進行中的打卡結束。追問：如果用 `FOR UPDATE`？——所有員工的打卡會排隊。

**Q5. 為什麼證據要獨立成只新增的表？** 出勤是「目前認定的事實」，會被更正；證據是「當時發生了什麼」，不該被改。補登沒有證據，正好說明它不是實際打卡。

**Q6. DTO Validation 與業務驗證差在哪？** Bean Validation 處理與情境無關的格式與範圍（緯度 −90～90）；業務驗證依情境（這次是否需要定位、是否啟用、版本是否相符）。本專案兩層都檢查有限數值：第一層擋下大部分，第二層防止日後有人移除註解。

**Q7. 前端如何避免過期 callback？** 操作在按下時固定；每次掛載一個永不復活的 `Lifetime`、每次打卡一個 `Operation`，在每個 `await` 之後與每個副作用之前確認仍有效；今日狀態只採用最後發出的查詢；`latestState` 在採用時同步更新。後端仍是最終裁決。（初版只用 `mounted` 布林值且只在定位後檢查，見第 1 輪複審修正。）

**Q8. 測試模擬能證明什麼、不能證明什麼？** 能證明程式對「成功、拒絕、逾時、不可用、精度、範圍外、延遲」的處理正確，且透過真正的 Geolocation API 可以走通。不能證明真實裝置的定位品質、HTTPS 環境、或任何防作弊效果。

## 練習（只有這一題）

**背景**：目前 `GeofenceGate.checkForClock` 標註 `Propagation.MANDATORY`，由 `AttendanceService.clockIn` 在同一個 `@Transactional` 內呼叫；它以 `SELECT … FOR SHARE` 取得設定快照，鎖持有到打卡交易提交。

**問題**：假設有人為了「讓 Controller 先擋掉範圍外的請求」，把定位檢查移到 `AttendanceController.clockIn` 裡，在呼叫 `attendanceService.clockIn` **之前**先呼叫一個沒有交易（或自己獨立交易）的檢查方法，`AttendanceService` 內不再檢查。請描述一個具體的時間線（員工、管理員各在什麼時候做了什麼），說明這個改動會讓「判斷用的設定」與「證據記錄的設定」不一致，或讓員工在新設定下本應被拒絕卻打卡成功；並說明為什麼原本的設計不會發生這件事。

請把你的回答帶回 ChatGPT 對話，由它批改並決定下一題。
