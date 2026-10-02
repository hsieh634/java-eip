# Tasks：T03.2

狀態：**已實作／作者自測完成／等待 ChatGPT 複審**。證據見 `docs/learning/T03-2-geofenced-attendance.md` 第 9 節。

| # | 工作 | 依賴 | 完成條件 | 狀態 |
|---|---|---|---|---|
| T032-1 | 規格（需求、領域、假設、架構、資料、API、安全、proposal、design、specification、tasks） | — | 本資料夾 | ☑ |
| T032-2 | V4 migration＋升級測試；既有 schema 測試固定到對應版本 | 1 | 乾淨庫與 V3 升級通過 | ☑ |
| T032-3 | 後端設定：讀寫、版本、歷程、權限 | 2 | `GeofenceSettingsIntegrationTest` | ☑ |
| T032-4 | 後端打卡：定位關卡、設定鎖、證據、錯誤碼 | 2 | `GeofenceClockIntegrationTest` | ☑ |
| T032-5 | 交易、並行、證據保留、申請不受限 | 3、4 | `GeofenceTransactionIntegrationTest`、`GeofenceEvidenceIntegrationTest` | ☑ |
| T032-6 | 前端：今日打卡的定位流程 | 4 | build、lint、E2E | ☑ |
| T032-7 | 前端：定位打卡設定頁 | 3 | build、lint、E2E | ☑ |
| T032-8 | E2E `geofence.mjs`；既有 E2E 回歸 | 6、7 | 全部通過 | ☑ |
| T032-9 | 變異驗證（距離、版本、證據交易、設定鎖） | 5 | 相應測試失敗、還原後通過 | ☑ |
| T032-10 | 共用文件、README、version-log、學習文件 | 全部 | 文件完成 | ☑ |
| — | ChatGPT 複審 | — | — | ☐ 等待中 |

## 第 1 輪複審（R1、R2）

細節見 `review-round-1.md`；狀態：**已實作／作者自測完成／等待 ChatGPT 複審**。

| # | 工作 | 狀態 |
|---|---|---|
| RR-1 | 回歸測試 `geofence-races.mjs`＋共用假定位 `e2e/geo.mjs` | ☑ |
| RR-2 | 以修正前程式執行，確認抓得到舊行為（11/22 失敗） | ☑ |
| RR-3 | R1：打卡操作生命週期與檢查點 | ☑ |
| RR-4 | R2：今日狀態查詢序號、同步更新最新狀態 | ☑ |
| RR-5 | 回歸：build、lint、全部 E2E | ☑ |
| RR-6 | 文件 | ☑ |
