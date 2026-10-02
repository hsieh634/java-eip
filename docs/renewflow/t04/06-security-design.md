# T04 安全設計

## 1. 授權與資料所有權

| 資源 | 規則 |
|---|---|
| `/api/admin/projects/**` | URL 規則 `hasRole('ADMIN')`＋`ProjectService.requireAdmin`（縱深防禦）＋`ActiveAccountFilter`（停用、刪除、角色變更 → 401） |
| `/api/projects` | 已登入即可；只回傳啟用中的專案 |
| `/api/timesheets/me/**` | 已登入即可；員工一律取自 Session；路徑與本文都沒有可指定員工的欄位 |
| 前端「專案管理」入口 | 只對 ADMIN 顯示；這只是介面，不是安全機制 |

- **角色 ≠ 資料範圍**：MANAGER、ADMIN 都只能看與改自己的工時；ADMIN 的額外權限只有專案管理。
- 每個工時查詢都以 `employee_id` 限定；修改的 UPDATE 也帶 `employee_id` 條件。
- 他人或不存在的紀錄一律 404 `TIMESHEET_NOT_FOUND`，回應不含任何他人資料。
- 本文夾帶 `employeeId`、`createdAt`、`version` 會被忽略（DTO 沒有這些欄位）。
- `DUPLICATE_SUBMISSION` 附的 `entryId` 只可能是**本人**的紀錄（查詢以 Session 的員工與 requestId 限定）。

## 2. 威脅與對應

| 威脅 | 對應 |
|---|---|
| 越權讀寫他人工時 | 所有權條件在 SQL；404 不洩漏；測試 W-08 與變異驗證 |
| 只靠隱藏選單限制管理功能 | URL＋Service 檢查；E2E 直接呼叫 API 驗證 403 |
| 繞過前端加總超過每日上限 | 後端交易內加總；員工列鎖序列化同一人的並行寫入 |
| 重送造成重複紀錄 | 員工＋requestId 唯一；ON CONFLICT；前端結果不明時沿用同一 requestId |
| 兩個分頁互相覆蓋 | 版本條件更新，409 不默默覆蓋 |
| 停用與新增的競態 | 專案列 FOR SHARE vs UPDATE，先後明確 |
| CSRF | 沿用 `X-XSRF-TOKEN`；POST、PUT 都受保護 |
| 不合法數值被「修正」成合法值 | 小數分鐘 400（關閉 float→int）、非 15 倍數 400；前端不四捨五入 |
| 資料庫故障被誤報 | 只有預期規則丟 `ApiException`；其他例外維持 500 |

## 3. 錯誤與登入狀態

前端只有 401 走登入狀態刷新；400／403／404／409／5xx 只顯示訊息。網路或 5xx 導致結果不明時，重新查詢、不自動重送；再次送出沿用同一 requestId。
