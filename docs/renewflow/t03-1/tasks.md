# Tasks：T03.1

狀態：☐ 未開始　◐ 已實作待驗證　☑ 已驗證（證據見 `docs/learning/T03-1-attendance-corrections.md` 第 9 節）。**尚未經對話助理審查。**

| # | 工作 | 狀態 |
|---|---|---|
| T031-1 | 規格（需求、領域、資料、API、安全、proposal／design／specification） | ☑ |
| T031-2 | V3 migration（版本、作廢、部分唯一索引、申請表、歷程表＋觸發器） | ☑ |
| T031-3 | 既有上下班 SQL 與查詢改為只看有效紀錄、維護版本 | ☑ |
| T031-4 | 申請（員工端）：提出／撤回／列出／歷程 | ☑ |
| T031-5 | 管理端：審核、直接更正／作廢、查詢、歷程；ADMIN 驗證 | ☑ |
| T031-6 | `developer` 開發帳號與 `.env` 設定 | ☑ |
| T031-7 | E2E 資料庫保護提前（migration 之前） | ☑ |
| T031-8 | 後端測試（狀態轉移、並行、回滾、權限、升級） | ☑ |
| T031-9 | 前端：員工申請、我的申請、歷程、自動重新取得 | ☑ |
| T031-10 | 前端：出勤管理（ADMIN） | ☑ |
| T031-11 | E2E `attendance-corrections.mjs`（隔離堆疊） | ☑ |
| T031-12 | README、共用文件、學習文件 `T03-1-attendance-corrections.md` | ☑ |

## 第 1 輪複審（R1–R3＋三種申請操作）

細節與驗收情境見 `review-round-1.md`；狀態：**已實作／作者自測完成／等待 ChatGPT 複審**。

| # | 工作 | 狀態 |
|---|---|---|
| RR-1 | 規格與 tasks（`review-round-1.md`、API 文件） | ☑ |
| RR-2 | R3 `TemporaryDatabases`＋替身測試；改寫真實啟動測試 | ☑ |
| RR-3 | R1 直接更正 ID＋版本 | ☑ |
| RR-4 | 申請快照核對、`kind`、今日回應 `recordId／version` | ☑ |
| RR-5 | 前端三種操作、R2 重新查詢 | ☑ |
| RR-6 | 後端與 E2E 回歸 | ☑ |
| RR-7 | 學習文件 `T03-1-review-fixes.md`、README、版本紀錄 | ☑ |
