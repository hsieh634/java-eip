# Proposal：T01＋T02

## 範圍

- T01：專案建置（Maven Wrapper、Spring Boot）、設定檔、PostgreSQL 連線、初始 migration（`employee`）。
- T02：登入、登出、目前使用者 API；登入頁；登入後首頁框架。

## 成果定義

後端與前端皆可在 Windows 啟動；乾淨資料庫可由 migration 重建；可用開發帳號登入、看到首頁框架、登出；未登入無法取得受保護資料；缺 CSRF 的寫入被拒。

## 明確不做

打卡、工時、請假、專案、部門、審核人、員工管理、改密碼、登入鎖定、正式部署、Redis、JWT、Testcontainers、前端 UI／狀態管理套件。

## 假設與取捨

見 `../02-assumptions.md`（A-01 ~ A-06、D-01 ~ D-10）。

## 風險

| 風險 | 緩解 |
|---|---|
| Spring Boot 4 / Spring Security 7 資料較新，API 與 3.x 有差異 | 以實際編譯與測試為準，不憑記憶；版本固定 |
| 本機無 PostgreSQL | Docker Compose；README 附原生安裝替代 |
| SPA 的 CSRF token 在登入／登出後失效 | 登入時輪替 token、前端每次讀 Cookie、登出後重取；以測試覆蓋 |
