# T03 API 設計（最終契約）

基底：`/api/attendance`；JSON（UTF-8）。規則承襲 T02：寫入需 `X-XSRF-TOKEN`；錯誤格式 `{ "code", "message" }`。
**所有端點都不接受前端指定的員工 ID、日期或打卡時間**（多送的欄位會被忽略）。身份＝Session；時間＝伺服器。

## 通用

| 情況 | 狀態 | code |
|---|---|---|
| 未登入、Session 失效、帳號已停用／刪除、角色已變更（需重新登入） | 401 | `UNAUTHENTICATED` |
| 缺少或錯誤的 CSRF token（POST） | 403 | `CSRF_REJECTED` |
| 系統故障（含帳號檢查或資料庫故障） | 500 | `INTERNAL_ERROR`（不誤報成 401／409） |

## POST /api/attendance/clock-in

- 無請求本文。成功 **200**，回 `AttendanceRecordResponse`（`status=WORKING`）。
- **409 `ALREADY_CLOCKED_IN`**：今日已有紀錄（不論是否已下班；已下班者訊息為「今日已完成上下班，無法再次上班」）。

## POST /api/attendance/clock-out

- 無請求本文。成功 **200**，回 `AttendanceRecordResponse`（`status=COMPLETED`）。
- **409 `CLOCK_IN_REQUIRED`**：今日沒有上班紀錄（即使昨日有未下班的紀錄也一樣，不替昨日補登）。
- **409 `ALREADY_CLOCKED_OUT`**：今日已下班；原下班時間不被覆蓋。

## GET /api/attendance/me/today

200 `TodayAttendanceResponse`，**即使沒有紀錄也是 200**：

```json
{
  "status": "NOT_STARTED | WORKING | COMPLETED",
  "workDate": "2026-10-01",
  "timeZone": "Asia/Taipei",
  "clockInAt": "2026-10-01T01:05:00Z",
  "clockOutAt": null,
  "unfinishedBefore": "2026-09-30"
}
```

- `workDate`：以台北時區換算的今日。`clockInAt`／`clockOutAt`：ISO 8601 UTC，無則 `null`。
- `unfinishedBefore`：今日之前**最近一筆**「有上班、無下班」的業務日期；沒有則 `null`（前端據此顯示提醒）。

## GET /api/attendance/me?from=YYYY-MM-DD&to=YYYY-MM-DD

- `from`、`to` 須**同時提供或同時省略**；省略＝今日所在月份的 1 日到月底。
- 範圍含起訖日最多 **31 個日曆日**。
- 200 `AttendanceListResponse`，只含本人，依 `workDate` 由新到舊：

```json
{
  "from": "2026-10-01", "to": "2026-10-31", "timeZone": "Asia/Taipei",
  "records": [
    { "workDate": "2026-10-01", "clockInAt": "...Z", "clockOutAt": "...Z", "status": "COMPLETED" }
  ]
}
```

- 紀錄 `status`：`COMPLETED`（有下班）；`WORKING`（今日、尚未下班）；`INCOMPLETE`（過去日期、未下班）。
- **400 `VALIDATION_FAILED`**：日期格式錯誤（例如 `2026-13-40`、`abc`）。
- **400 `INVALID_DATE_RANGE`**：只給一個日期、起日大於迄日、超過 31 個日曆日。
- 查詢字串中的 `employeeId` 等多餘參數一律忽略。

## DTO

`AttendanceRecordResponse(workDate, clockInAt, clockOutAt, status)`、`TodayAttendanceResponse`、`AttendanceListResponse` 皆為 record；Entity 不對外。
