# TeamPortal（企業員工入口平台）

Java 學習專案，依 `RenewFlow-v2-Java-EIP-Learning-Guide.md` 與 **RenewFlow Engineering Method v2**（開發方法名稱）建立。
**目前進度：T01～T05.1**（專案基礎、登入／登出、上下班打卡與本人出勤紀錄、出勤更正／重新打卡申請與管理權限、公司範圍定位打卡與管理員設定、最小專案管理與個人工時回報、最小組織與員工管理、請假申請與主管審核）。T06 尚未開始。產品方向與路線圖見下表（候選模組不是實作授權）。

> 命名說明：產品顯示名稱是 TeamPortal；Repository（`java-eip`）、資料夾、Java package（`com.renewflow.eip`）、資料庫、容器與環境變數名稱沿用既有設定。

| 想看什麼 | 去哪裡 |
|---|---|
| 規格（需求、架構、資料、API、安全、本階段 proposal／design／specification／tasks） | [docs/renewflow/](docs/renewflow/) |
| T03 打卡規格 | [docs/renewflow/t03/](docs/renewflow/t03/) |
| T03.1 更正／重新打卡／管理權限規格 | [docs/renewflow/t03-1/](docs/renewflow/t03-1/)（第 1 輪複審：[review-round-1.md](docs/renewflow/t03-1/review-round-1.md)） |
| T03.3 同日多組出勤、去重與工時未知結果修補 | [docs/renewflow/t03-3/](docs/renewflow/t03-3/)、[主教材](docs/learning/T03-3-multiple-attendance-and-submit-results.md)、[第 1 輪 R1](docs/renewflow/t03-3/review-round-1.md)、[目標選擇教材](docs/learning/T03-3-target-selection-review-fix.md) |
| T03.2 定位打卡規格 | [docs/renewflow/t03-2/](docs/renewflow/t03-2/) |
| T04 專案與工時規格 | [docs/renewflow/t04/](docs/renewflow/t04/)（複審：[第 1 輪](docs/renewflow/t04/review-round-1.md)、[第 2 輪](docs/renewflow/t04/review-round-2.md)） |
| T05 前置：組織與員工規格 | [docs/renewflow/t05-foundation/](docs/renewflow/t05-foundation/) |
| T05 請假申請與主管審核規格 | [docs/renewflow/t05/](docs/renewflow/t05/) |
| T05.1 修改／重送／核准後變更與銷假 | [規格](docs/renewflow/t05-1/)、[真人教材](docs/renewflow/t05-1/manual-verification.md)、[學習文件](docs/learning/T05-1-leave-revisions-and-cancellation.md) |
| 產品方向、路線圖 | [product-direction.md](docs/renewflow/product-direction.md)、[roadmap.md](docs/renewflow/roadmap.md) |
| STMAX 示範情境（虛構公司與帳號） | [demo-stmax.md](docs/renewflow/demo-stmax.md) |
| 程式解說、資料流、驗證證據、面試題 | [docs/learning/](docs/learning/)（最新：[T05-leave-and-approval.md](docs/learning/T05-leave-and-approval.md)；T05 前置：[T05-foundation-organization-and-employees.md](docs/learning/T05-foundation-organization-and-employees.md)；T04 第 2 輪：[T04-retry-state-boundaries.md](docs/learning/T04-retry-state-boundaries.md)；T04 主文件：[T04-projects-and-timesheets.md](docs/learning/T04-projects-and-timesheets.md)） |

## 1. 環境需求（Windows 11）

於 2026-10-01 在這台電腦實測的檢查結果：

| 工具 | 需求 | 本機狀態 |
|---|---|---|
| JDK | 21 | ✅ Temurin 21.0.12（`JAVA_HOME` 指向它；另有 JDK 25，不影響） |
| Maven | 不需自行安裝 | ✅ 專案內建 Maven Wrapper（`mvnw.cmd`）；另有 3.9.12 |
| Node.js / npm | Node 20.19+ 或 22.12+（Vite 8 需求） | ✅ Node 24.14.0 / npm 11.9.0 |
| Docker Desktop | 用來跑 PostgreSQL | ✅ 已安裝；**檢查時引擎未啟動**，需先開啟 Docker Desktop |
| PostgreSQL | 17（以 Docker 提供） | ⚠️ 本機沒有原生安裝（也沒有 `psql`），改用 Docker，不需另裝 |
| Git | 版本控制（推送到 GitHub 需要） | ✅ 2.53.0；已初始化，遠端 `https://github.com/hsieh634/java-eip` |
| Edge 或 Chrome | 只有執行 `npm run e2e` 才需要 | ✅ Edge（系統內建） |

### 缺少或需補的項目與安裝步驟（PowerShell）

沒有必裝的缺項。若在另一台電腦，缺什麼就裝什麼：

```powershell
winget install EclipseAdoptium.Temurin.21.JDK     # JDK 21
winget install OpenJS.NodeJS.LTS                   # Node.js
winget install Docker.DockerDesktop                # Docker（安裝後需重新開機並啟動它）
# 安裝後請「重新開啟」PowerShell，再確認：
java -version; node -v; npm -v; docker --version
```

確認 `JAVA_HOME` 指向 JDK 21：`echo $env:JAVA_HOME`

**不想用 Docker？** 可改裝原生 PostgreSQL：`winget install PostgreSQL.PostgreSQL.17`，安裝時記下 `postgres` 超級使用者密碼，然後用 `psql` 建立使用者與兩個資料庫（`renewflow`、`renewflow_test`），並讓 `.env` 的 `DB_*` 與之對應。本專案主線只驗證過 Docker 做法。

## 2. 第一次設定

在專案根目錄（`java-eip`）：

```powershell
# 1. 建立 .env（含資料庫密碼與開發帳號密碼）。.env 已被 .gitignore 排除
Copy-Item .env.example .env
notepad .env          # 設定 DB_PASSWORD、RENEWFLOW_DEV_SEED_PASSWORD、RENEWFLOW_DEV_DEVELOPER_PASSWORD（只用英數字元）

# 2. 啟動資料庫（需先開啟 Docker Desktop，等右下角圖示顯示 running）
docker compose up -d
docker compose ps     # 看到 renewflow-db ... (healthy)

# 3. 安裝前端套件（npm ci 依 package-lock.json 安裝完全相同的版本；只有要升級套件時才用 npm install）
cd frontend; npm ci; cd ..
```

> 資料庫初始化腳本只有在「資料卷第一次建立」時才會跑。若資料庫容器是更早建立的、缺少 `renewflow_test`，**不需要刪除資料卷**，只補建缺少的資料庫（見下方第 7 節「常見問題」）。
> **請不要把 `docker compose down -v` 當成預設處理方式**：它會刪除整個本機資料庫（包含所有開發資料），只有你確定要從零重來時才使用。

## 3. 啟動（開兩個終端機）

終端機 A — 後端（http://localhost:8080）：

```powershell
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

看到 `Started EipApplication` 即成功；啟動時會套用尚未套用的 migration，並建立尚不存在的開發帳號。

終端機 B — 前端（http://localhost:5173）：

```powershell
cd frontend
npm run dev
```

用瀏覽器開 <http://localhost:5173>。

**開發帳號**（只在 dev profile 建立）：

| 帳號 | 角色 | 密碼在哪裡看 |
|---|---|---|
| `employee` | 員工 | `.env` 的 `RENEWFLOW_DEV_SEED_PASSWORD` |
| `manager` | 主管（**沒有**管理權限） | 同上 |
| `admin` | 管理員 | 同上 |
| `developer` | 管理員（本機開發用） | `.env` 的 `RENEWFLOW_DEV_DEVELOPER_PASSWORD`（**獨立密碼**，不與一般帳號共用；沒有預設值，未設定就不建立，日誌會提示變數名稱） |

用 `notepad .env` 打開 `.env` 即可看到以上兩個變數的值（`.env` 不進 Git）。

開發帳號只會在該帳號不存在時建立；改 `.env` 的密碼**不會**自動更新已存在的帳號。忘記或想改密碼時，請用「只更新密碼雜湊」的維護流程（**員工 ID 與出勤資料都保留**，見第 7 節），不要刪帳號重建。

停止：兩個終端機各按 `Ctrl+C`；資料庫 `docker compose stop`（保留資料）或 `docker compose down`（保留資料卷）。

## 4. 環境變數

| 名稱 | 用途 | 預設 |
|---|---|---|
| `DB_HOST` / `DB_PORT` | 資料庫位置 | `localhost` / `5432` |
| `DB_NAME` | 開發用資料庫 | `renewflow` |
| `DB_TEST_NAME` | 整合測試用資料庫（測試會清空它！） | `renewflow_test` |
| `DB_USER` | 資料庫帳號 | 無（必填） |
| `DB_PASSWORD` | 資料庫密碼 | 無（必填，缺少則後端無法啟動） |
| `RENEWFLOW_DEV_SEED_PASSWORD` | `admin`／`manager`／`employee` 的共用密碼；僅 dev profile 讀取 | 空＝不建立這三個帳號 |
| `RENEWFLOW_DEV_DEVELOPER_PASSWORD` | `developer`（ADMIN）的**獨立**密碼；僅 dev profile 讀取 | 空＝不建立 developer（沒有預設密碼） |
| `RENEWFLOW_DEMO_STMAX_ENABLED` | 是否建立虛構示範公司 STMAX 的六個帳號；僅 dev profile 讀取（見 [demo-stmax.md](docs/renewflow/demo-stmax.md)） | `false` |
| `RENEWFLOW_DEMO_STMAX_PASSWORD` | STMAX 六個示範帳號的共用密碼；只放在本機 `.env` | 空＝不建立（沒有預設密碼） |
| `RENEWFLOW_DEMO_STMAX_ORG_ENABLED` | 把 STMAX 六個帳號排進三個示範部門並設定主管（只補未指派的欄位、不改回管理員的調整）；僅 dev profile 讀取 | `false` |
| `RENEWFLOW_DEV_SEED_RESET` | 設為 `true` 並重啟一次：只更新開發帳號的密碼雜湊（保留 ID 與出勤資料）；用完改回 `false` | `false` |

隔離 E2E 堆疊（`npm run e2e:attendance`、`e2e:corrections`、`e2e:geofence`、`e2e:races`、`e2e:timesheets`、`e2e:timesheets-review`、`e2e:timesheets-retry`、`e2e:organization`、`e2e:leave`、`e2e:leave-review` 自動設定，平常不需要碰）使用 `RENEWFLOW_E2E_RUN_ID`、`RENEWFLOW_E2E_PASSWORD`、`E2E_API_PORT`（8081）、`E2E_API_TARGET`、`E2E_VITE_PORT`（5174）。

後端由 `backend/` 目錄啟動時會自動讀取上一層的 `.env`；也可直接設定同名的真正環境變數。

## 5. 測試與建置

```powershell
# 後端測試（單元＋整合；整合測試需要資料庫容器在執行）。只會清空並重建 renewflow_test，
# 清空前會先確認「資料庫實際回報的名稱」就是允許的測試庫，且與開發庫不同，否則直接中止
cd backend
.\mvnw.cmd test

# 前端型別檢查＋建置、靜態檢查
cd ..\frontend
npm run build
npm run lint
```

瀏覽器端 E2E：需要**先**啟動資料庫、後端（dev profile）與前端開發伺服器，再開第三個終端機：

```powershell
cd frontend
npm run e2e
```

`npm run e2e` 依序執行十三支腳本，也可單獨執行：
- `npm run e2e:smoke`（基本流程）、`npm run e2e:logout`（登出失敗情境）、`npm run e2e:password`（密碼位元組提示）：對你正在跑的 dev 環境（5173／8080）執行，只登入／登出開發帳號、讀取畫面，**不會打卡、不會提出申請**。
- `npm run e2e:attendance`（T03 打卡）、`npm run e2e:corrections`（T03.1 申請／核准／重新打卡）、`npm run e2e:geofence`（T03.2 定位打卡與設定；以注入的假定位與 DevTools 定位模擬）、`npm run e2e:races`（T03.2 複審 R1／R2：以請求攔截與手動定位控制事件順序的競態測試）、`npm run e2e:timesheets`（T04 專案管理與工時回報）、`npm run e2e:timesheets-review`（T04 複審 R1／R2：結果不明的重送與背景更新失敗）、`npm run e2e:timesheets-retry`（T04 第 2 輪：重試失敗不解鎖、清單為空仍可確認）、`npm run e2e:organization`（T05 前置：部門、員工、主管、過期表單、Session 失效、權限、手機）、`npm run e2e:leave`（T05：請假送出、核准、退回、撤回、改派、指定審核人、越權、版本衝突、回應遺失後重送、手機）、`npm run e2e:leave-review`（T05 第 1 輪複審：成功回應無法使用時保持結果不明、相同條件再查詢會重新讀取、指定審核人失效時的設定畫面）：**不使用你的 dev 環境**。各自啟動一組**隔離堆疊**——另一個後端（`spring-boot:test-run`，埠 8081，連**測試資料庫**）、另一個 Vite（埠 5174）、並為本次建立全新的專用員工與管理員；結束時自動關閉。因此不會碰到你的開發帳號與出勤資料，重複執行也不需要清空任何東西。需要 Java／Maven、資料庫容器在執行，且 8081、5174 沒有被占用（被占用時它會拒絕執行）。隔離後端在**套用 migration 之前**就會確認連到的是測試資料庫，啟動時並把測試庫的定位設定恢復為預設（關閉）。

腳本使用本機已安裝的 Edge／Chrome（找不到時設定環境變數 `BROWSER_PATH`），密碼自動讀根目錄 `.env`；截圖與隔離堆疊日誌輸出在 `frontend/e2e/output/`（不進版控）。

## 6. API 使用方式（curl 範例，Windows 請用 `curl.exe`）

寫入請求需要 CSRF token：先 GET 取得 `XSRF-TOKEN` Cookie，再放進 `X-XSRF-TOKEN` 標頭。

```powershell
# 取得 token（401 也會帶 Cookie）並保存 Cookie
curl.exe -s -c jar.txt http://localhost:8080/api/auth/me
$token = (Select-String -Path jar.txt -Pattern 'XSRF-TOKEN').Line.Split("`t")[-1]

# 登入（請把 <密碼> 換成 .env 內的 RENEWFLOW_DEV_SEED_PASSWORD）
curl.exe -s -b jar.txt -c jar.txt -H "X-XSRF-TOKEN: $token" -H "Content-Type: application/json" `
  -d '{\"username\":\"employee\",\"password\":\"<密碼>\"}' http://localhost:8080/api/auth/login

# 目前使用者
curl.exe -s -b jar.txt http://localhost:8080/api/auth/me
```

登出：`POST /api/auth/logout`（同樣需要 `X-XSRF-TOKEN`，成功回 204；登出後 Cookie 內的 token 已被換新，重新讀取 `jar.txt` 再用）。完整契約見 [docs/renewflow/05-api-design.md](docs/renewflow/05-api-design.md)。

**出勤 API（T03.3）**——不接受員工 ID、日期或打卡時間，一律由 Session 與伺服器決定：

```powershell
curl.exe -s -b jar.txt http://localhost:8080/api/attendance/me/today                    # 今日狀態
curl.exe -s -b jar.txt "http://localhost:8080/api/attendance/me?from=2026-10-01&to=2026-10-31"   # 本人紀錄（含已作廢；省略參數＝本月；最多 31 天）
curl.exe -s -X POST -b jar.txt -H "X-XSRF-TOKEN: $token" -H "Content-Type: application/json" `
  -d '{\"requestId\":\"<本次 UUID>\"}' http://localhost:8080/api/attendance/clock-in
```

打卡必填 requestId；下班另須指定 expectedAttendanceId＋expectedVersion。未知結果重試沿用原 UUID／本文，不改成新操作。完整契約見 [T03.3 API](docs/renewflow/t03-3/05-api-design.md)。

**申請與管理 API（T03.1）**：員工端 `/api/attendance/requests`（提出、列出、撤回）與 `/api/attendance/me/history`；管理端 `/api/admin/attendance/**`（只有 ADMIN）。完整契約與錯誤碼見 [docs/renewflow/t03-1/04-api-design.md](docs/renewflow/t03-1/04-api-design.md)。

## 7. 常見問題

| 現象 | 原因／處理 |
|---|---|
| 後端啟動失敗：`Could not resolve placeholder 'DB_PASSWORD'` | 沒有 `.env`，或不是從 `backend/` 目錄啟動 |
| 後端啟動失敗：連線被拒絕 | 資料庫沒啟動：`docker compose up -d`，並確認 Docker Desktop 已執行 |
| `docker compose` 說無法連到 `dockerDesktopLinuxEngine` | Docker Desktop 尚未啟動完成 |
| 測試失敗：`database "renewflow_test" does not exist` | 容器比初始化腳本更早建立，缺少測試資料庫。**只補建，不刪資料**：`docker compose exec db psql -U renewflow -d renewflow -c "CREATE DATABASE renewflow_test"`（名稱與使用者請對應 `.env` 的 `DB_TEST_NAME`、`DB_USER`、`DB_NAME`）。已存在時會回報 already exists，無害。 |
| 登入不了 `developer`／日誌出現「RENEWFLOW_DEV_DEVELOPER_PASSWORD 未設定」 | 在 `.env` 設定 `RENEWFLOW_DEV_DEVELOPER_PASSWORD`（英數字元）後重啟後端，developer 會被建立 |
| 忘記／想更改開發帳號密碼 | **保留員工 ID 與出勤資料、只更新密碼雜湊**：1) 在 `.env` 改好新密碼（一般帳號用 `RENEWFLOW_DEV_SEED_PASSWORD`、developer 用 `RENEWFLOW_DEV_DEVELOPER_PASSWORD`），並設定 `RENEWFLOW_DEV_SEED_RESET=true`；2) 重新啟動後端（dev profile）——日誌會出現「已重設開發帳號密碼」；3) 把 `RENEWFLOW_DEV_SEED_RESET` 改回 `false`。這不是公開 API，只在啟動時由 dev profile 執行；已登入的舊 Session 不會被中斷。**不要**刪除帳號再重建：有出勤、申請或歷程的員工因外鍵（`ON DELETE RESTRICT`）無法刪除，而且會失去員工 ID。 |
| 測試啟動時出現「拒絕對資料庫執行 clean 或初始化寫入」 | 這是保護機制在擋：實際連到的資料庫不是明確允許的測試資料庫，或開發／測試資料庫設定相同。檢查 `.env` 的 `DB_NAME` 與 `DB_TEST_NAME` 必須不同，且測試庫名稱需為小寫並以 `_test` 結尾。 |
| 前端顯示「伺服器暫時無法使用」 | 後端沒開 |
| 5173 或 8080 埠被占用 | 關掉占用程式；前端設定了 `strictPort`，不會自動換埠 |
| E2E 說埠 8081 或 5174 已被占用 | 隔離堆疊拒絕使用既有服務。上一次執行若被強制中斷，可能殘留 java／node 行程：關掉它們再試（不影響 8080／5173 的開發環境） |
| 今天誤按了下班／想整組重新打卡 | 依第 9 節提出「申請撤銷下班打卡」或「申請整組作廢並重新打卡」，由 `developer` 或 `admin` 核准。系統不會直接刪除紀錄 |

## 8. 目前限制

本機學習規則（不代表任何公司制度）：固定白班、台北時區、每人每日可有多組有效出勤、最多一筆有效未完成組；不支援跨日班、輪班、未來時間。誤按或漏打需透過申請（或管理員直接更正），且都會留下歷程；原始紀錄不會被硬刪。

工時回報（T04）沒有簽核、刪除、匯出與專案成員限制；每日 24 小時只是資料合理性上限，不代表工時或加班規則。

定位打卡（T03.2）依瀏覽器提供的位置判斷，位置可被模擬或偽造，**不能完全防止代打卡**；手機用區網 HTTP 位址開網站時瀏覽器通常不允許定位（需要 HTTPS），本階段沒有 HTTPS 與手機實機驗證。

組織與員工（T05 前置）：部門是平面清單、每人最多一位直屬主管；沒有刪除、沒有修改帳號／員工編號、沒有密碼修改或重設、沒有邀請信；**主管關係不授與全公司出勤或工時查詢權**。T05.1 僅在合法請假流程詳情提供申請人相關日期的出勤摘要，不擴大 MANAGER 管理查詢權。

請假（T05）是 STMAX 示範規則：只有事假、病假、特休，沒有額度、年資、薪資或法規計算；每筆是一天內的一段時間（半小時為單位，不跨午夜）；「時段長度」未扣除休息時間，不是計薪工時；不會自動修改出勤或工時。T05.1 支援待審修改／退回重送，以及已核准假單的變更、整段與單側部分銷假；每次提交保留獨立版本。變更／銷假核准前原有效內容不變，管理員沒有繞過審核直接修改入口。單一關審核，沒有代理人、通知、附件。

沒有登入失敗鎖定、改密碼、多層審核、通知；Session 存在記憶體（重啟後端需重新登入）；正式環境的同源部署與 HTTPS 設定尚未處理（只定義了 Cookie `Secure` 預設為 true）。詳見安全設計文件。

## 9. 出勤更正操作步驟（T03.1）

> 系統不會替任何人自動作廢或修改出勤。以下步驟都由你自己在網站上操作。

**先選對申請（員工）**——三種操作對資料的影響不同：

| 情況 | 用哪個 | 在哪裡 | 核准後 |
|---|---|---|---|
| 還在工作，卻誤按了下班 | **申請撤銷下班打卡** | 「今日打卡」卡片（先選所需的已下班時段） | 同一筆紀錄：上班時間完全不變、下班清空，恢復「工作中」，可再按下班 |
| 今天整組都要重來（例如測試時誤按上下班，或上班打錯） | **申請整組作廢並重新打卡** | 「今日打卡」卡片（先選目標時段，只有上班也可以） | 所選一組作廢（原時間與事件保留），其他組不變；沒有有效未完成組時可再次上班 |
| 時間不對、漏打卡、過去日期 | **申請更正／補登時間** | 「出勤打卡」頁的「我的出勤紀錄」 | 依你填的時間更正或補登 |

沒有「只取消上班、保留下班」這種操作。每位員工同一天只能有一筆待審申請；過了今天就不能核准「整組作廢」，請改用更正／補登。

**A. 員工提出申請**
1. 以 `employee` 登入 <http://localhost:5173>，在首頁或「出勤打卡」頁的「今日打卡」卡片按 **申請撤銷下班打卡** 或 **申請整組作廢並重新打卡**。
2. 表單會列出員工、日期、目前上下班時間與核准後的結果（撤銷下班：「保留上班時間、撤銷下班時間，恢復工作中，可再次下班打卡」；上班時間唯讀）。填寫原因（必填），按 **送出申請**。
3. 卡片顯示「已提出『…』申請，等待管理員審核」。核准前打卡紀錄與按鈕**不會**改變；待審期間可按 **撤回申請**。
4. 如果在你打開表單之後紀錄被別人改過（例如管理員更正），送出會被拒絕並提示「出勤資料在你開啟表單後已經變動」，表單也會標示已過期：按 **取消** 後重新開啟即可。

**B. 管理員（`developer` 或 `admin`）核准**
1. 用另一個瀏覽器（或無痕視窗）以 `developer` 登入（密碼見 `.env` 的 `RENEWFLOW_DEV_DEVELOPER_PASSWORD`）。只有管理員看得到左側 **出勤管理**。
2. 在「申請審核」找到申請，卡片標示種類（撤銷下班打卡／整組作廢並重新打卡／更正／補登時間），按 **核准**。
3. 確認視窗列出員工、日期、原值與預計結果，按 **確認核准**；退回則按 **退回** 並填寫原因（必填）。
4. 管理員不能審核自己提出的申請；`developer` 與 `admin` 可以互相處理。

**C. 員工繼續打卡**
1. 回到 employee 的視窗（切回來時會自動重新查詢；也可按卡片右上角 **重新整理**）。
2. 撤銷下班：狀態回到「工作中」，之後再按 **下班打卡**。整組作廢：狀態回到「尚未上班」，依序按 **上班打卡**、**下班打卡**。

**D. 查看原始紀錄與更正歷程**
- 員工：「出勤打卡」頁的 **我的出勤紀錄**（整組作廢的原始紀錄以「已作廢」顯示並保留原時間；撤銷下班不會產生作廢紀錄）；**我的申請** 顯示結果與退回原因；**更正歷程** 按「查看歷程」可看到申請種類、操作者、原值／申請值／修改後的值與時間。
- 管理員：「出勤管理」→「員工出勤查詢與更正」選擇員工與日期 → **查詢**。條件不變再按一次 **查詢** 也會重新取得最新的紀錄與歷程。

**其他**
- 管理員直接更正：在查詢結果按 **更正時間**、**新增（補登）紀錄** 或 **作廢今日紀錄**，必填原因並在確認視窗確認；不能對自己操作。對話框開著期間，若那筆紀錄被作廢、換成新的一筆或被改過，送出會被拒絕（「這筆出勤資料已被其他操作更新」），請重新查詢。

## 10. 定位打卡操作步驟（T03.2）

> 開發環境的定位限制**預設關閉**、公司中心**未設定**。系統不會替你猜公司地址，也不會自動啟用。

**A. 管理員（`developer` 或 `admin`）設定**
1. 登入後點左側 **定位打卡設定**（只有管理員看得到）。
2. 填寫公司中心的 **緯度**、**經度**（十進位度數，例如 25.033964、121.564468；可在地圖服務對公司位置按右鍵取得）、**允許半徑**（10～5000 公尺，預設 100）、**適用操作**（只限制上班／只限制下班／上下班都限制）。
3. 勾選 **啟用定位限制**（沒有公司中心時不能啟用），填寫 **修改原因**，按 **儲存設定**。
4. 確認畫面列出修改前後的差異，按 **確認儲存**。頁面顯示最近修改人與時間，下方「設定歷程」列出每次修改的前後值與原因。
5. 兩位管理員同時修改時，後儲存的人會看到「已被其他管理員更新，沒有儲存」，按 **重新載入最新設定** 後再改。
6. 要停用：取消勾選、填原因、儲存。管理員自己的打卡也受限制。

**B. 員工打卡**
1. 「今日打卡」卡片會事先說明這次打卡是否需要定位、允許半徑與精度上限（50 公尺）。
2. 不需要定位時，打卡流程與以前相同，不會詢問位置。
3. 需要定位時，按下按鈕後瀏覽器會詢問位置權限；按鈕依序顯示「確認定位設定中…」「正在取得位置…」「正在送出打卡…」。
4. 被拒絕時會說明原因並提供 **重試**：拒絕權限、逾時、無法取得、不支援、非安全來源、精度不足、或不在範圍內（顯示距離與允許半徑）。
5. 人在範圍外時仍可提出「撤銷下班」「整組作廢」「更正／補登」申請，由管理員審核。

**C. 實機限制**：電腦的 `http://localhost:5173` 是安全來源，瀏覽器允許定位；桌機通常只有 Wi-Fi／IP 定位，精度可能超過 50 公尺而被拒絕。手機用 `http://192.168.x.x:5173` 開網站**不是**安全來源，瀏覽器會拒絕定位——需要 HTTPS，本階段尚未處理（要做時會先說明方案、費用與影響）。

## 11. 專案管理與工時回報操作步驟（T04）

> 開發資料庫**沒有**任何預設專案或工時。以下步驟都由你自己在網站上操作。

**A. 管理員（`developer` 或 `admin`）建立專案**
1. 登入後點左側 **專案管理**（只有管理員看得到）。
2. 在「建立專案」填 **專案代碼**（2～32 字，英文字母、數字、`_`、`-`；會自動轉大寫，**建立後不可修改**）、**專案名稱**、說明（選填），按 **建立專案**。
3. 「專案清單」可 **編輯**（名稱、說明）或 **停用**／**重新啟用**；都會跳出確認視窗。若顯示「已被其他管理員修改」，按 **重新載入** 後再操作。
4. 停用不會刪除專案或工時：員工不能再新增這個專案的工時，既有紀錄仍可查看。

**B. 員工（任何角色）填報工時**
1. 點左側 **工時回報**（首頁也有「前往工時回報」）。
2. 在「新增工時」選 **日期**（今天或以前）、**專案**、輸入 **時數（小時）**（以 0.25 為單位：0.25＝15 分、1.5＝1 小時 30 分、7.75＝7 小時 45 分；欄位下方會顯示換算結果），填 **工作內容**，按 **送出**。
3. 下方「我的工時紀錄」預設顯示本月；可改起訖日按 **查詢**（最多 31 天）或按 **本月**。會顯示每筆紀錄、**期間合計** 與 **每日合計**。
4. 同一天所有紀錄合計最多 24 小時，超過會被拒絕並說明已記錄多少。
5. 若送出時網路中斷或沒有收到回應，畫面會說「無法確認原提交是否已新增」，並**鎖定表單在剛才送出的內容**、重新查詢清單。按 **重新送出原內容** 會用同一個識別碼確認，**不會重複建立**；確認建立後要改內容，請在清單按 **修改**。重新送出若又失敗（例如安全驗證失敗、專案已停用、網路仍不通），畫面會顯示這次的原因，並說明「原提交的結果仍未確認」，表單維持鎖定，可以再按一次；即使目前沒有任何啟用中的專案，也仍可按 **重新送出原內容** 確認上一筆。若確定要放棄這次提交，按 **放棄追蹤，開始新的一筆**（會先提醒原提交可能已經建立）。這個保護只在目前開著的頁面有效，重新整理後請先查看清單確認。
6. 送出時若顯示專案已停用，表單內容會保留；專案清單若更新失敗，會另外顯示「重試更新專案清單」。原本選的專案會標示「已停用或無法選擇」，請自行改選。

**C. 修改自己的工時**
1. 在清單該列按 **修改**，表單會載入這筆的最新內容；改完按 **送出**。
2. 若這筆已在別的分頁被修改，會顯示「你的變更沒有儲存」，按 **重新載入最新內容** 後再改。
3. 專案已停用的紀錄仍可修改日期、時數、內容（專案欄會顯示「（已停用）」），也可以改到其他啟用中的專案；不能把其他紀錄改到停用專案。

## 12. STMAX 示範帳號（虛構公司）

STMAX 是虛構的網站開發與數位行銷公司，帳號與姓名全部虛構；TeamPortal 品牌不變。詳見 [docs/renewflow/demo-stmax.md](docs/renewflow/demo-stmax.md)。

1. 在本機 `.env` 設定 `RENEWFLOW_DEMO_STMAX_ENABLED=true` 與 `RENEWFLOW_DEMO_STMAX_PASSWORD`（只用英文字母與數字）。
2. 重新啟動後端（dev profile）。日誌會列出新建立或已存在的示範帳號；若與既有帳號碰撞，不會建立任何一個。
3. 以 `stmax_max`（ADMIN）、`stmax_iris`（MANAGER）、`stmax_leo`／`stmax_mia`／`stmax_ryan`／`stmax_zoe`（EMPLOYEE）登入；密碼是 `.env` 中 `RENEWFLOW_DEMO_STMAX_PASSWORD` 的值。
4. 重啟不會重設密碼或修改這些帳號；改回 `false` 只會停止建立，不會刪除已建立的帳號。

5. （T05 前置）另外設定 `RENEWFLOW_DEMO_STMAX_ORG_ENABLED=true` 並重啟：建立 MANAGEMENT／PROJECT／ADMINISTRATION 三個部門，把六人排進部門並設定主管（Iris 的主管是 Max；Leo、Mia、Ryan 的主管是 Iris；Zoe 的主管是 Max）。只補空白欄位，重啟不會改回你在畫面上的調整。

示範專案（STMAX 官網改版、虛構客戶品牌網站、虛構客戶廣告代操）只寫在文件中，需要時由 `stmax_max` 在「專案管理」手動建立。Iris 是 Leo、Mia、Ryan 的直屬主管，可審核指派給自己的請假；主管身分不代表全公司審核權。

## 13. 組織與員工操作步驟（T05 前置）

> 部門與員工都不刪除，只停用。以下步驟都由管理員自己在網站上操作；系統不會自動改任何人的主管。

**A. 查看組織（例如以 `stmax_max` 登入）**
1. 點左側 **組織與員工**（只有管理員看得到）。預設在 **員工** 分頁：清單列出員工編號、姓名、帳號、角色、部門、直屬主管、狀態。
2. 在 **搜尋** 輸入姓名、帳號或員工編號（例如 `stmax`），可再選 **狀態**（全部／啟用中／已停用），按 **搜尋**。每頁 20 人，用 **上一頁**／**下一頁** 翻頁。
3. 切到 **部門** 分頁可看到每個部門的啟用員工數與狀態。每一列的 **歷程** 顯示誰在何時改了什麼、原因與前後值。

**B. 建立部門**：在 **部門** 分頁填 **部門代碼**（2～32 字，英文字母、數字、`_`、`-`；自動轉大寫，**建立後不可修改**）與 **部門名稱**，按 **建立部門**。

**C. 新增員工**
1. **員工** 分頁按 **新增員工**，填 **員工編號**（自動轉大寫）、**帳號**（自動轉小寫；兩者建立後都不可修改）、**顯示名稱**、**角色**、**部門**與**直屬主管**（可留「未指派」）。
2. 輸入 **初始密碼**（最多 72 位元組；前後空白也算）。系統**不會寄送邀請信，也不會要求首次登入改密碼**：請你用安全的方式把帳號與初始密碼交給本人。建立後系統不會再顯示這個密碼。
3. 按 **確認建立**。新同事即可用該帳號登入、打卡、填工時（沒有部門也可以）。

**D. 修改員工（名稱、角色、啟用、部門、直屬主管）**
1. 在該列按 **編輯**。下方的 **變更摘要** 會列出每個欄位的「原值 → 新值」。
2. 改角色、啟用狀態、部門或主管時 **原因必填**；只改名稱可不填。按 **確認儲存**。
3. 停用或改角色後，對方目前的登入會立即失效，需要重新登入（停用者不能再登入）。
4. 系統會擋下：停用或降級自己；讓系統沒有任何啟用中的管理員；把仍是別人主管的人停用或改成員工（請先替他的部屬改主管）；主管形成循環（A 的主管是 B、B 的主管又是 A）；把員工指派到已停用的部門。
5. 若顯示「已被其他管理員修改」，你的輸入會保留；按 **載入最新資料（會取代目前輸入）** 後再改。

**E. 停用部門**：**部門** 分頁按 **編輯**，狀態改 **停用**、填原因、**確認儲存**。部門還有啟用中的員工時會被拒絕；已停用的員工可以保留在停用的部門，但重新啟用他時要同時改到啟用中的部門（或改成未指派）。

## 14. 請假與審核操作步驟（T05）

> 系統不會替任何人送出、核准、退回、撤回、改派或設定。以下步驟都由各帳號自己在網站上操作。STMAX 帳號密碼是 `.env` 中 `RENEWFLOW_DEMO_STMAX_PASSWORD` 的值。

**A. Max 設定自己的請假審核人（先做這一步，否則 Max 不能送出請假）**
1. 以 `stmax_max` 登入，點左側 **請假管理** → 分頁 **指定請假審核人**。
2. 在「員工」輸入 `stmax_max`，按 **搜尋**，點選 **Max（STX-001）**。
3. 畫面顯示：直屬主管「（無）」、目前指定「（未指定，採直屬主管）」、目前送出時的審核人「無法送出」。
4. 在 **指定請假審核人** 選 **Iris 林怡安（STX-002）**，「變更」會顯示「（未指定）→ Iris 林怡安」。
5. 填 **修改原因**（例如「Max 沒有直屬主管，由 Iris 審核 Max 的請假」），按 **確認儲存**。下方「設定歷程」會出現這筆紀錄。
6. 這個設定**不會**改組織的主管關係：Max 仍沒有主管，Iris 的主管仍是 Max。只影響之後送出的申請。

**B. 員工送出請假（例如 Leo）**
1. 以 `stmax_leo` 登入，點左側 **請假**。上方顯示「預計審核人：Iris 林怡安（直屬主管）」。這只是提示，送出時系統會重新確認。
2. 選 **假別**（事假／病假／特休）、**日期**、**開始**、**結束**（半小時為單位），畫面會顯示時段長度（未扣除休息時間）。
3. 填 **原因**（必填，不需要填診斷或醫療資訊），按 **送出申請**。
4. 下方「我的請假」出現一筆「待審」。按 **詳情** 可看審核人與歷程。
5. 同一天時段重疊會被拒絕並說明（例如 09:00～12:00 已有申請，就不能再送 11:00～13:00）。前一筆 12:00 結束、下一筆 12:00 開始可以。跨日請分開送出。
6. 若顯示「無法確認原提交是否已建立」，表單會鎖定在剛才的內容。網路中斷、伺服器錯誤，或伺服器有回應但內容無法使用時都會這樣（伺服器可能已經建立）：按 **重新送出原內容** 確認（不會重複建立），或在看過警告後按 **放棄追蹤，開始新的一筆**。重新整理頁面後，請先查看清單確認。

**C. 審核人處理（Leo、Mia、Ryan 的申請由 Iris；Iris、Zoe 的由 Max；Max 的由 Iris）**
1. 以審核人登入（例如 `stmax_iris`），點左側 **請假審核**。預設只列出**指派給你的待審**申請。
2. 按 **詳情** → **核准** 或 **退回**。確認畫面會列出申請人、假別、日期、時間、原因。退回原因必填，申請人看得到。
3. 清單上方的「查詢」每按一次都會重新向伺服器取得最新資料（條件相同也一樣）。若顯示「這筆申請已不是待審狀態」或「已被更新」，代表申請人已撤回或管理員已改派：按 **重新載入**，系統不會假裝成功。

**D. 員工查看結果或撤回**
1. 回到 **請假**，在「我的請假」按 **重新整理**，可以看到「已核准」或「已退回」。退回的申請在詳情中顯示退回原因；需要時重新送出新的申請。
2. **待審**的申請可以在詳情按 **撤回這筆申請** → **確認撤回**。已核准的申請不能撤回或修改。

**E. 管理員改派（例如 Iris 休假中，Leo 的待審申請改給 Max）**
1. 以 `stmax_max` 登入，**請假管理** → **申請查詢與改派**，找到該申請按 **詳情**。
2. 在「改派」選新的審核人。選項不含申請人與目前審核人。填 **改派原因**，按 **檢查改派內容**，確認「Iris 林怡安 → Max」後按 **確認改派**。
3. 改派後 Iris 不再看得到這筆申請，由 Max 在 **請假審核** 處理。
4. 若某位員工的指定審核人之後被停用或改成員工，設定畫面的下拉選單會顯示「目前指定：姓名（員工編號）（已無效）」，這位員工此時無法送出請假。請直接改選「不指定（採直屬主管）」或另一位有效審核人，填原因後儲存；系統不會自動清除。
5. **Max 自己的申請**不能由 Max 改派：畫面會提示需由另一位管理員操作（例如 `developer` 或 `admin`）。Max 也不能審核自己的申請。


## T03.3 本輪驗證與交接

以下為T03.3前批歷史交接；本輪最新本機狀態以T05.1交接為準。多組出勤與工時成功本文故障回歸：`npm run e2e:multiple-attendance`、`npm run e2e:timesheets-success-body`。驗證範圍、數量與限制見 [verification.md](docs/renewflow/t03-3/verification.md)，接續開發見 [development-handoff.md](docs/renewflow/development-handoff.md)。程式更新後，使用者需自行重啟既有開發後端套用 V8；本輪沒有重啟使用者的 8080 服務。已套用 migration 不修改，資料庫與歷程保留。

## T05.1 本輪交付與操作

2026-10-13：T03.3 R1 已收到使用者提供的 ChatGPT 程式複審通過，實際表單邏輯受控檢查基準11／20→修正20／20；BUG-TP-001前輪通過。完整後端／E2E是作者自測，真人驗收尚未完成。T05.1作者證據與本機實際版本見 [verification](docs/renewflow/t05-1/verification.md) 及 [handoff](docs/renewflow/development-handoff.md)。

Leo在「請假」詳情可修改待審／退回重送；目前有效核准版本可申請變更、整段／部分銷假。部分銷假填保留時段，只縮短一側。Iris在「請假審核」查看原核准、目前有效、本次、差異與相關出勤，再確認。列表合計只算本頁目前有效列，歷史核准不重複計算。結果不明保留原本文／UUID，重試拒絕仍鎖定；重新整理不保存草稿，先查真實資料。

本輪唯讀核對Leo→Iris，沒有指定審核人；Max無主管／指定設定，沒有自動替Max設定Iris。密碼取本機`.env`對應變數，勿重設或把秘密放進Git。真人逐步案例仍全部「未執行」，見 [教材](docs/renewflow/t05-1/manual-verification.md)。

新E2E：`npm run e2e:leave-revisions`；隔離埠／庫可由`E2E_API_PORT`、`E2E_VITE_PORT`、`DB_TEST_NAME`指定。JUnit與E2E不能同時操作同一測試庫。新遷移是V9，V1～V8不改；先備份並在新臨時庫還原確認再升級，不對開發庫clean／repair。

可設定與發布的簽核流程、職稱／職位解析、流程版本、拖拉式設計器、條件分支及代理未實作。跨日、多日、取消中間拆兩段也不在本批。
