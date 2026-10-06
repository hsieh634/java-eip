# Tasks：T05 請假申請與主管審核

狀態：**已實作／作者自測完成／等待 ChatGPT 複審**。Review 與 Archive 尚未進行。

| # | 工作 | 依賴 | 完成條件（驗收） | 證據 | 狀態 |
|---|---|---|---|---|---|
| T5-1 | Requirement、Domain、假設 | — | `01`、`02` | 本資料夾 | ☑ |
| T5-2 | Architecture、Data Model、API、Security | 1 | `03`～`06`；API 契約先於實作 | 本資料夾 | ☑ |
| T5-3 | Proposal、Design、Specification | 2 | 鎖、狀態機、requestId、驗收對照 | `proposal.md`、`design.md`、`specification.md` | ☑ |
| T5-4 | V7 migration；`TestData.reset`、版本清單加入 7 | 2 | 乾淨庫＋V6 升級 | LS-01、LS-02 | ☑ |
| T5-5 | `OrganizationLock.acquireFor`（取鎖後重新確認操作者），組織寫入一併套用 | — | 舊身分 → 401 | L-12、O-09、O-09b | ☑ |
| T5-6 | 後端：送出、撤回、核准、退回、審核人決定 | 4、5 | 正常、輸入、重疊、範圍、自審、requestId、版本 | L-01～L-15 | ☑ |
| T5-7 | 後端：管理查詢、改派、指定審核人設定 | 6 | 改派、設定 | L-10、L-16、L-17 | ☑ |
| T5-8 | 並行與回滾測試 | 6、7 | 每輪恰好一個成功；歷程失敗整筆回滾 | LC-01～LC-05、LA-01～LA-03 | ☑ |
| T5-9 | 前端：請假、請假審核、請假管理、導覽、首頁 | 6、7 | build、lint | 學習文件第 9 節 | ☑ |
| T5-10 | E2E `leave.mjs`；既有 E2E 導覽檢查隨功能更新 | 9 | 全部通過 | 學習文件第 9 節 | ☑ |
| T5-11 | 變異驗證 8 項 | 8 | 對應測試失敗、還原後通過 | `specification.md` 第 9 節 | ☑ |
| T5-12 | 全部後端測試、全部 E2E、build、lint | 全部 | 全部通過 | 學習文件第 9 節 | ☑ |
| T5-13 | 開發庫套用 V7（先說明、前後比對）；唯讀檢查 STMAX 帳號 | 4 | 既有表相同；預計審核人正確 | `verification.md` | ☑ |
| T5-14 | README、共用文件、STMAX、roadmap、version-log、學習文件 | 全部 | 文件完成 | — | ☑ |
| — | Review（ChatGPT 複審） | — | — | — | ☐ 等待中 |
| — | Archive | Review | 複審通過後整理 | — | ☐ 未開始 |
