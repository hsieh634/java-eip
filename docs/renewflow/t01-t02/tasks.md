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

## 審查修正（對應 commit d72fb81 的審查）

| # | 工作 | 狀態 |
|---|---|---|
| R1 | 測試資料庫清空保護（實際資料庫名稱＋明確允許＋開發／測試不同）與防護測試 | ☑ |
| R2 | 認證機制故障回 500 而非 401，附受控故障測試 | ☑ |
| R3 | 登出失敗的 UI 提示、真實狀態確認、E2E 失敗情境 | ☑ |
| R4 | 密碼 UTF-8 bytes 上限（後端強制＋前端提示）與邊界測試 | ☑ |
| 文件 | 停用員工行為精確描述、T03 前置檢查記錄、名稱更正、npm ci、學習文件 | ☑ |

證據見 `docs/learning/T01-T02-review-fixes.md`；仍尚未經對話助理審查。

## 之後階段（概要，不在本輪）

T03 打卡（**前置：共用的帳號有效性檢查**，見 `../06-security-design.md`）→ T04 專案與工時 → T05 請假審核 → T06 首頁彙整、設定、驗收與同源部署。
