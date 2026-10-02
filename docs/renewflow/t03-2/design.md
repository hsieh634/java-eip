# Design：T03.2

## 1. 打卡交易（`AttendanceService.clockIn／clockOut`）

```
@Transactional
now = Clock（截到微秒）
decision = geofenceGate.checkForClock(action, location, now)   // MANDATORY：必須在這個交易內
  ├─ settingRepository.lockForClock()      SELECT … FOR SHARE → 快照
  ├─ 不需要定位 → notRequired(快照)
  └─ 需要定位：欄位齊全(422) → 有限數值與範圍(400) → 精度 ≤ 50(422) → 時間窗口(422) → Haversine → distance ≤ radius 否則 403
原子打卡 SQL（T03／T03.1 不變）
clockEventLog.record(…, decision)        // INSERT 證據；失敗 → 整筆回滾
```

**為什麼定位關卡在打卡交易內？** 共享鎖要持有到打卡提交，才能保證「判斷用的設定」＝「證據記錄的設定」，而且設定修改不會插在中間。`Propagation.MANDATORY` 讓「在交易外呼叫」直接失敗，而不是悄悄沒有鎖。

## 2. 一致性規則（設定 × 打卡）

| 情況 | 結果 |
|---|---|
| 設定修改交易先取得 `FOR UPDATE` | 打卡的 `FOR SHARE` 等待；修改提交後打卡讀到新版本 |
| 打卡交易先取得 `FOR SHARE` | 設定修改的 `FOR UPDATE` 等待；打卡用舊版本完成並記錄舊版本；之後的打卡使用新版本 |
| 多個打卡同時 | 共享鎖互不阻擋 |

證據的 `setting_version` 與快照內容必定來自同一版本（`GeofenceTransactionIntegrationTest` T-05～T-07）。

## 3. 設定修改（`GeofenceSettingsService.update`）

驗證（原因、座標成對與有限、啟用需中心）→ `lockForUpdate` → 比對 `expectedVersion`（409）→ 沒有變更（400）→ `updateIfVersion`（版本條件、version + 1）→ 讀回 → `GeofenceSettingAuditLog.record`（同交易）。

## 4. 錯誤

`GeofenceException(status, code)` 由 `GlobalExceptionHandler` 轉成 `{code, message}`；`OutsideGeofenceException` 另含 `distanceMeters`（未取整）與 `radiusMeters`。資料庫故障等其他例外維持 500。

Bean Validation（`@DecimalMin／@DecimalMax／@Min／@Max／@NotNull／@NotBlank`）先擋格式與範圍（包含 NaN、Infinity，回 `VALIDATION_FAILED`）；Service 再做業務規則，並保留有限數值檢查作為第二道防線。

## 5. 前端打卡流程（`useTodayAttendance.perform`）

1. `inFlight` ref 擋連按；操作（上班／下班）在按下時固定於閉包。
2. 取最新定位要求；取不到 → 不打卡（不改用不限制模式）。
3. 需要定位才呼叫 `getCurrentPosition({enableHighAccuracy: true, maximumAge: 0, timeout: 15000})`。
4. 定位回來後：元件已卸載 → 丟棄；今日狀態已不允許此操作 → 取消並重新查詢；精度 > 上限 → 提示重試。
5. 送出；成功或失敗都重新查詢今日狀態；網路中斷不重送；401 才回登入頁。

## 6. 管理頁

`GeofenceSettingsPage`：`useLoad` 載入設定與歷程（失敗可重試）；表單以版本為 key，載入新版本即重設；前端驗證（十進位、範圍、成對、啟用需中心、沒有變更、原因必填）；確認視窗列出前後差異；409 時顯示「已被其他管理員更新」與重新載入。

## 7. 測試資料庫

`TestData.reset` 清空（TRUNCATE）並重建預設設定列；`E2eSeedRunner` 在隔離後端啟動時把設定恢復成預設（僅測試庫）。
