# Tasks：T04

狀態：**已實作／作者自測完成／等待 ChatGPT 複審**。證據見 `docs/learning/T04-projects-and-timesheets.md` 第 9 節。

| # | 工作 | 依賴 | 完成條件 | 狀態 |
|---|---|---|---|---|
| T04-1 | 規格（需求、領域、假設、架構、資料、API、安全、proposal、design、specification、tasks） | — | 本資料夾 | ☑ |
| T04-2 | V5 migration；既有 schema 測試的版本清單加入 5 | 1 | 乾淨庫與 V4 升級通過 | ☑ |
| T04-3 | 後端專案管理 | 2 | `ProjectIntegrationTest` | ☑ |
| T04-4 | 後端本人工時（鎖、版本、requestId、上限） | 2、3 | `TimesheetIntegrationTest` | ☑ |
| T04-5 | 並行、交易、遷移測試 | 4 | `TimesheetConcurrencyIntegrationTest`、`TimesheetSchemaTest` | ☑ |
| T04-6 | 前端工時回報頁、專案管理頁、導覽與首頁 | 3、4 | build、lint | ☑ |
| T04-7 | E2E `timesheets.mjs`；既有 E2E 的導覽檢查隨功能啟用更新 | 6 | 全部通過 | ☑ |
| T04-8 | 針對性變異驗證（員工列鎖、版本、所有權、requestId） | 5 | 相應測試失敗、還原後通過 | ☑ |
| T04-9 | 開發庫套用 V5，確認既有資料與定位設定保留 | 2 | 前後比對相同 | ☑ |
| T04-10 | README、共用文件、version-log、學習文件 | 全部 | 文件完成 | ☑ |
| — | ChatGPT 複審 | — | — | ☐ 等待中 |
