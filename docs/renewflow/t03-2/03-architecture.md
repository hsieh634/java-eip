# T03.2 架構

沿用模組化單體。新增 `geofence` 模組（公司範圍設定與定位判斷），`attendance` 模組在打卡交易內呼叫它。

```
backend/src/main/java/com/renewflow/eip/
├─ geofence/                          ← T03.2 新增
│  ├─ GeofenceRules                   工程假設常數（半徑範圍、精度上限 50、時間窗口 −60／+10 秒）
│  ├─ GeoDistance                     Haversine 距離、範圍判斷（distance ≤ radius）
│  ├─ ClockAction, GeofenceScope      上班／下班；適用操作
│  ├─ GeofenceSetting(+Repository)    單一列設定；FOR SHARE／FOR UPDATE 鎖；版本條件 UPDATE
│  ├─ GeofenceSnapshot                不可變的設定快照（一次判斷只用一份）
│  ├─ GeofenceGate                    打卡前的定位關卡（必須在打卡交易內：MANDATORY）
│  ├─ GeofenceSettingsService         管理端讀寫與歷程（ADMIN）
│  ├─ GeofenceSettingHistory(+Repository), GeofenceSettingAuditLog   只新增的設定歷程
│  ├─ GeofenceAdminController         /api/admin/geofence
│  ├─ GeofenceDtos                    DTO（打卡定位、員工端定位要求、管理端設定、歷程）
│  └─ GeofenceException, OutsideGeofenceException
├─ attendance/
│  ├─ AttendanceService（擴充）       打卡：定位關卡 → 原子 SQL → 證據
│  ├─ AttendanceController（擴充）    打卡本文可省略；GET /location-policy
│  ├─ AttendanceClockEvent(+Repository), ClockEventLog   只新增的打卡證據
│  └─ TodayAttendanceResponse（擴充） locationPolicy
└─ shared/web/GlobalExceptionHandler（擴充）、OutsideGeofenceError
```

```
frontend/src/
├─ lib/geolocation.ts                 getCurrentPosition 包裝（只取一次）、錯誤分類、安全來源檢查
├─ attendance/useTodayAttendance.ts   打卡流程：取最新定位要求 → 定位 → 送出前重新確認 → 送出 → 重新查詢
├─ components/TodayAttendanceCard.tsx 階段顯示、定位說明、重試按鈕
├─ api/adminGeofence.ts, attendance/geofenceForm.ts（前端驗證）
└─ pages/GeofenceSettingsPage.tsx     #/admin/geofence（僅 ADMIN 顯示入口）
```

## 完整資料流（受限制的上班打卡）

畫面按「上班打卡」→ `GET /api/attendance/location-policy`（最新要求）→ 需要定位 → `navigator.geolocation.getCurrentPosition` →
送出前確認（仍在畫面、仍是尚未上班、精度 ≤ 上限）→ `POST /api/attendance/clock-in` 帶原始定位 →
Security（CSRF、Session、`ActiveAccountFilter`）→ `AttendanceController.clockIn` → `AttendanceService.clockIn`（交易開始）→
`GeofenceGate.checkForClock`：`SELECT … FOR SHARE` 取設定快照 → 驗證 → Haversine → 範圍判斷 →
`AttendanceRepository.insertIfAbsent`（`INSERT … ON CONFLICT DO NOTHING`）→ `ClockEventLog.record`（INSERT 證據）→ 提交 →
回應 → 畫面重新查詢今日狀態。

## 為什麼判斷放在後端

前端的任何值都能被竄改（開發者工具、改寫請求）。後端自行取得設定、自行計算距離、自行比較，前端只提供「原始定位資料」。
但後端也**無法驗證**這些定位資料是真的：瀏覽器定位可被模擬（例如 DevTools、模擬定位 App）。所以本功能是「規則與紀錄」，不是防作弊保證。
