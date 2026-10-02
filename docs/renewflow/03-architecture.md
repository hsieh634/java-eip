# 架構設計

## 1. 形態

模組化單體：一個後端應用、一個 PostgreSQL、一個 SPA 前端。依業務分模組，不拆微服務。

```
java-eip/
├─ backend/            Spring Boot（Maven Wrapper）
│  └─ src/main/java/com/renewflow/eip/
│     ├─ EipApplication.java
│     ├─ identity/       員工、角色、登入／登出／目前使用者   ← T02
│     │  ├─ Employee, Role, EmployeeRepository      （Entity／Repository）
│     │  ├─ EmployeeUserDetails, EmployeeUserDetailsService   （與 Spring Security 的橋接）
│     │  ├─ AuthController, AuthService             （HTTP 邊界／使用案例）
│     │  ├─ LoginRequest, CurrentUserResponse       （DTO）
│     │  ├─ AccountStatusService, AccountState      （共用的最新帳號有效性判斷，T03）
│     │  └─ DevSeedRunner                           （僅 dev profile；可選「只更新密碼雜湊」）
│     ├─ attendance/     上下班打卡與本人出勤紀錄（T03）；更正／重新打卡申請、管理端、歷程（T03.1）
│     │  ├─ Attendance, AttendanceRepository, AttendanceService, AttendanceController, *Response
│     │  ├─ AttendanceRequest*, AttendanceRequestService/Controller（員工申請）
│     │  ├─ AttendanceAdminService/Controller（/api/admin/**，僅 ADMIN）
│     │  └─ AttendanceHistory*, AttendanceAuditLog（只新增的歷程）
│     ├─ shared/         跨模組共用
│     │  ├─ security/    SecurityConfig, CSRF 處理, 401/403 JSON 回應, ActiveAccountFilter（T03）
│     │  ├─ time/        TimeConfig（可注入的 Clock）, BusinessTime（Asia/Taipei 業務日期）
│     │  ├─ validation/  MaxUtf8Bytes（自訂 Bean Validation：UTF-8 位元組上限）
│     │  └─ web/         ApiError, GlobalExceptionHandler
│     └─ (timesheet, leave, project, dashboard 於後續階段建立)
├─ frontend/           React + TypeScript（Vite）
├─ docker/             PostgreSQL 初始化腳本
├─ docker-compose.yml  本機 PostgreSQL
└─ docs/               renewflow（規格）與 learning（教材）
```

## 2. 分層與責任

| 層 | 責任 | 不做 |
|---|---|---|
| Controller | 解析 HTTP、驗證輸入格式（`@Valid`）、呼叫 Service、決定狀態碼 | 業務規則、直接碰 Repository |
| Service | 使用案例、權限／資料範圍檢查、交易邊界 | 處理 HTTP 細節 |
| Repository | Spring Data JPA 介面，查詢與保存 | 業務判斷 |
| Entity | 資料對映與必要的狀態規則 | 直接對外公開（API 一律用 DTO） |

使用建構子注入。Schema 只由 Flyway migration 管理，`spring.jpa.hibernate.ddl-auto=validate`。

## 3. 設計模式

本輪**沒有刻意套用 GoF 模式**。唯一值得說明的是 Spring Security 的 `UserDetailsService` 與 `PasswordEncoder` 是框架提供的 **Strategy** 擴充點：我們只實作介面，由框架決定何時呼叫。理由是這是框架的標準做法，不是為了展示模式。

## 4. 執行環境

| 環境 | 前端 | 後端 | 資料庫 | Cookie `Secure` | 備註 |
|---|---|---|---|---|---|
| 開發（dev profile） | Vite `localhost:5173`，**代理** `/api` → `localhost:8080` | `localhost:8080` | Docker `localhost:5432` | false（HTTP） | 瀏覽器只看到同源 `5173`，因此不需要 CORS |
| 測試（test profile） | 無 | MockMvc | 同一容器的 `renewflow_test` | false | 每次測試前重建 schema |
| 正式（預設設定） | 建置後與後端**同源**提供 | 反向代理後的 HTTPS | 正式 DB | **true** | 本輪只定義設定值，不部署；同源部署於 T06 處理 |

## 5. 後續階段（概要）

| 階段 | 新增模組 | 與本輪的銜接 |
|---|---|---|
| T03 | attendance | 以 Session 身份取得員工 ID；Clock 注入；員工＋業務日期唯一限制 |
| T03.1 | attendance（申請、管理端、歷程） | 版本、作廢、`/api/admin/**`，見 [`t03-1/`](t03-1/) |
| T03.2 | geofence（定位設定、定位關卡）；attendance（打卡證據） | 打卡交易內以 `FOR SHARE` 取得設定快照；見 [`t03-2/03-architecture.md`](t03-2/03-architecture.md) |
| T04 | project、timesheet | 專案與工時的資料範圍 |
| T05 | leave | 狀態轉移、審核關係（需新增審核人欄位）、樂觀鎖 |
| T06 | dashboard、設定 | 首頁彙整取代「尚未啟用」卡片；同源部署文件 |
