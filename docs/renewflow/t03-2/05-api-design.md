# T03.2 API 設計（契約）

承襲 T02／T03／T03.1：寫入需 `X-XSRF-TOKEN`；錯誤格式 `{ "code", "message" }`（範圍外另含距離與半徑）；DTO 為 record，Entity 不對外。
所有受保護請求先經 `ActiveAccountFilter`（停用、刪除、角色變更 → 401）。時間為 ISO 8601（UTC）；距離與半徑單位為公尺。

## 1. 員工端

### POST /api/attendance/clock-in、POST /api/attendance/clock-out

本文**可省略**。定位限制關閉、或本次操作不受限制時，沿用 T03 的無本文請求。

```json
{ "latitude": 25.033964, "longitude": 121.564468, "accuracy": 18.5, "capturedAt": "2026-10-02T01:00:03.120Z" }
```

| 欄位 | 規則 |
|---|---|
| `latitude` | 有限數值，-90～90 |
| `longitude` | 有限數值，-180～180 |
| `accuracy` | 有限數值，≥ 0（瀏覽器 `coords.accuracy`，公尺，95% 信心） |
| `capturedAt` | 瀏覽器 `position.timestamp`（取得位置的時間） |

- 員工身分來自 Session；出勤日期與打卡時間來自伺服器 Clock。本文不接受、也不採用 `distance`、`inside`、公司中心、半徑或「已通過」等欄位（未知欄位會被忽略，不影響判斷）。
- 不受限制時，即使送了位置也**不保存**。

**後端處理順序**（同一交易）：
1. `SELECT … FOR SHARE` 取得目前設定的完整快照（讀取失敗 → 500，不會當作關閉而放行）。
2. 判斷本次操作是否受限制：`enabled` 且 `scope` 包含此操作。
3. 受限制時依序驗證：欄位齊全 → 有限數值與範圍 → `accuracy ≤ 50` → `capturedAt` 在 `[伺服器現在 − 60 秒, 伺服器現在 + 10 秒]`。
4. Haversine 計算距離；`distance ≤ radiusMeters` 才通過（不取整，不把 accuracy 加進半徑）。
5. 執行原本的原子打卡 SQL（T03／T03.1 規則不變）。
6. 寫入打卡事件（證據）；失敗則整筆回滾。

**錯誤**

| 狀態 | code | 情況 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 欄位超出範圍（Bean Validation）、`accuracy` 為負；有上下限的欄位為 `NaN`／`Infinity` 也在這裡被擋下 |
| 400 | `INVALID_LOCATION` | 受限制時的第二道檢查：非有限數值（例如只有下限的 `accuracy` 為 `Infinity`） |
| 400 | `BAD_REQUEST` | JSON 無法解析、時間格式錯誤 |
| 422 | `LOCATION_REQUIRED` | 本次操作受限制，但缺少任一個定位欄位 |
| 422 | `LOCATION_ACCURACY_TOO_LOW` | `accuracy` 大於 50 公尺 |
| 422 | `LOCATION_TIME_INVALID` | `capturedAt` 早於 60 秒前或晚於 10 秒後 |
| 403 | `OUTSIDE_GEOFENCE` | 距離大於半徑。本文另含 `distanceMeters`（未取整）與 `radiusMeters` |
| 409 | `ALREADY_CLOCKED_IN`／`CLOCK_IN_REQUIRED`／`ALREADY_CLOCKED_OUT` | 沿用 T03 |
| 401／403 | `UNAUTHENTICATED`／`FORBIDDEN` | 未登入或帳號失效／CSRF 錯誤 |
| 500 | `INTERNAL_ERROR` | 資料庫或內部故障（含設定讀取失敗、證據寫入失敗） |

```json
{ "code": "OUTSIDE_GEOFENCE", "message": "目前位置距離公司 236 公尺，超出允許範圍 100 公尺", "distanceMeters": 236.41, "radiusMeters": 100 }
```

定位相關的拒絕都發生在寫入之前：出勤、版本、打卡事件都不變。

### GET /api/attendance/location-policy → 200

員工在按下打卡時取得最新的定位要求。**不回傳公司中心座標。**

```json
{ "enabled": true, "clockInRequired": true, "clockOutRequired": true, "radiusMeters": 100, "maxAccuracyMeters": 50, "version": 3 }
```

### GET /api/attendance/me/today

多回傳 `locationPolicy`（內容同上），讓卡片事先顯示「本次是否需要定位、允許半徑」。

## 2. 管理端（`/api/admin/geofence`，需要 ROLE_ADMIN）

未登入 401；EMPLOYEE／MANAGER 403；Service 內再檢查一次角色。

### GET /api/admin/geofence → 200

```json
{
  "enabled": false, "latitude": null, "longitude": null, "radiusMeters": 100, "scope": "BOTH",
  "version": 0, "updatedAt": null, "updatedBy": null,
  "limits": { "radiusMin": 10, "radiusMax": 5000, "maxAccuracyMeters": 50 }
}
```

`scope`：`CLOCK_IN`（只限制上班）、`CLOCK_OUT`（只限制下班）、`BOTH`（上下班都限制）。`updatedBy` 為 `{ id, employeeNo, displayName }` 或 null。

### PUT /api/admin/geofence → 200（回傳更新後的設定）

```json
{ "enabled": true, "latitude": 25.033964, "longitude": 121.564468, "radiusMeters": 100, "scope": "BOTH", "expectedVersion": 0, "reason": "公司位置確認後啟用" }
```

| 規則 | 錯誤 |
|---|---|
| `enabled`、`radiusMeters`、`scope`、`expectedVersion`、`reason` 必填；原因非空白、≤ 500 字 | 400 `VALIDATION_FAILED` |
| 緯度 -90～90、經度 -180～180、半徑 10～5000（整數） | 400 `VALIDATION_FAILED` |
| 座標必須是有限數值（`"NaN"`、`"Infinity"`、`1e400` 先被範圍檢查擋下；Service 另有有限數值檢查作為第二道） | 400 `VALIDATION_FAILED`（第二道為 `INVALID_SETTING`） |
| 緯度與經度成對提供（都有或都沒有）；0 是合法值 | 400 `INVALID_SETTING` |
| 啟用時必須已有中心座標 | 400 `INVALID_SETTING` |
| 與目前設定完全相同 | 400 `INVALID_SETTING` |
| `expectedVersion` 與目前版本不同 | 409 `SETTING_CHANGED`（不覆蓋較新的設定） |

成功時版本 +1，並在同一交易寫入設定歷程（修改前、修改後、原因、修改人、時間）。歷程寫入失敗則設定一起回滾。

### GET /api/admin/geofence/history → 200

`{ "entries": [ { id, version, before{enabled,latitude,longitude,radiusMeters,scope}, after{…}, reason, actor{…}, occurredAt } ] }`，新到舊，最多 100 筆。沒有修改或刪除歷程的 API。

## 3. 不提供

打卡事件（定位證據）的查詢 API、管理員即時地圖、多據點、地址搜尋。員工無法透過任何 API 看到他人的定位。
