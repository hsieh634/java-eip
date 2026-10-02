# Design：T04

## 1. 新增工時（`TimesheetService.create`，單一交易）

```
驗證（不需鎖）：分鐘數為 15 倍數、內容去除空白後 1～1000、日期 ≤ 台北今天
lockEmployee           SELECT id FROM employee WHERE id = ? FOR NO KEY UPDATE
同一 requestId？       有 → 409 DUPLICATE_SUBMISSION（entryId）
lockActiveProject      SELECT * FROM project WHERE id = ? FOR SHARE；不存在 404、停用 409
當日合計               SUM(minutes) + 新值 > 1440 → 409 DAILY_LIMIT_EXCEEDED
insertIfRequestNew     INSERT … ON CONFLICT (employee_id, request_id) DO NOTHING RETURNING id（最後防線）
回應                   讀回 → DTO
```

## 2. 修改工時（`TimesheetService.update`，單一交易）

```
驗證（同上）
lockEmployee
讀取本人紀錄           findByIdAndEmployeeId；否則 404（他人與不存在相同）
版本                   不符 → 409 TIMESHEET_CHANGED
專案                   同原專案：FOR SHARE 鎖住，不檢查啟用；換專案：FOR SHARE＋必須啟用
沒有變更               400
當日合計               排除自己（id <> 這筆）後加新值；換日期就是加總目標日期
updateIfVersion        WHERE id = ? AND employee_id = ? AND version = ?；0 → 409
```

## 3. 為什麼這樣鎖

- **每日上限**：兩個同一人的請求若同時「讀合計 → 檢查 → 寫入」，會都讀到舊合計而都通過（寫入偏差）。員工列鎖讓第二個請求在第一個提交後才讀合計。
- **不同員工**：鎖的是各自的員工列，互不阻擋（`TimesheetConcurrencyIntegrationTest` K-07）。
- **NO KEY UPDATE**：其他表新增引用該員工的列時，外鍵檢查取得 FOR KEY SHARE；NO KEY UPDATE 與它相容，打卡等寫入不會被工時交易擋住。
- **專案**：工時交易 FOR SHARE；停用的 UPDATE 需要 FOR NO KEY UPDATE，與 FOR SHARE 互斥 → 先後明確（K-05、K-06）。
- **順序**：員工 → 專案 → 紀錄；管理員只鎖專案。不會形成循環等待。

## 4. 專案管理（`ProjectService`）

- 建立：代碼 `trim().toUpperCase(Locale.ROOT)` → 格式檢查 → `INSERT … ON CONFLICT (code) DO NOTHING RETURNING id`（空 → 409）。
- 修改：讀取 → 版本比對 → 沒有變更 400 → `UPDATE … WHERE id = ? AND version = ?`（0 → 409）。停用／啟用也走這裡。

## 5. 錯誤

`shared/web/ApiException(status, code, message, details)` → `{code, message, …details}`。既有例外類別不變。資料庫故障等未預期例外維持 500。

## 6. 前端

- **TimesheetPage**：清單 `useLoad(…, key＝範圍＋查詢序號)`（同條件再查也會重新取得、舊回應依 key 丟棄）；儲存後 `refetch`（保留畫面、背景更新）。
- **EntryFields**：`useLifetime` 判斷送出後是否仍在畫面上；`submitting` ref 防連按；`requestId` 在表單建立時產生，**只有成功、`DUPLICATE_SUBMISSION`（等同已成功）或按「清空，開始新的一筆」才換新**；網路結果不明時保留並提示。
- **EditEntry**：每次向後端取得該筆最新內容；版本衝突顯示「重新載入最新內容」，不自動重送。原本的停用專案加入下拉選單並標示「已停用」。
- **專案清單過期**（送出後才發現停用）：背景重新取得可選專案（`refetch`），表單與錯誤訊息保留；同時刷新紀錄清單。
- **ProjectsAdminPage**：建立表單（顯示正規化後的代碼）；編輯與停用／啟用使用確認視窗；409 關閉視窗並提示重新載入。

## 7. 測試資料庫

`TestData.reset` 一併 TRUNCATE `timesheet_entry`、`project`。E2E 的專案代碼含執行識別碼，避免與測試庫先前的資料衝突。
