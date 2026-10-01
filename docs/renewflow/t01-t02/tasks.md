# Tasks：T01＋T02

狀態標記：☐ 未開始　◐ 已實作待驗證　☑ 已驗證（有證據，見 docs/learning/T01-T02-foundation-and-login.md 第 9 節）。全部 T01、T02 項目目前為 ☑，但尚未經對話助理審查。

## T01 專案基礎

| # | 工作 | 完成條件 |
|---|---|---|
| T01-1 | 環境檢查與安裝步驟 | README 列出缺少工具與指令 |
| T01-2 | `backend/` Maven 專案、Wrapper | `mvnw -v` 可執行 |
| T01-3 | `docker-compose.yml`、`.env.example`、DB 初始化腳本 | `docker compose up -d` 後 DB 可連線；`renewflow_test` 存在 |
| T01-4 | `application*.yml`（分環境） | 無密碼寫死；`ddl-auto=validate` |
| T01-5 | `V1__create_employee.sql` | 乾淨資料庫可套用 |

## T02 登入與首頁框架

| # | 工作 | 完成條件 |
|---|---|---|
| T02-1 | Employee／Role／Repository／UserDetails | 編譯通過；schema validate 通過 |
| T02-2 | SecurityConfig、CSRF、401/403 JSON | S-08、S-12、S-13 |
| T02-3 | 登入／`/me`／登出 | S-03 ~ S-07、S-10、S-11 |
| T02-4 | 開發帳號初始化 | dev 啟動後可登入；無 seed 密碼時不建立 |
| T02-5 | 整合測試 | `mvnw test` 全數通過 |
| T02-6 | 前端專案、代理、API 層、AuthProvider | `npm run build` 通過 |
| T02-7 | 登入頁、首頁框架 | S-17 |
| T02-8 | 學習文件與完成報告 | `docs/learning/` 有 10 項內容 |

## 之後階段（概要，不在本輪）

T03 打卡 → T04 專案與工時 → T05 請假審核 → T06 首頁彙整、設定、驗收與同源部署。
