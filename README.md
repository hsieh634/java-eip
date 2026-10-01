# TeamPortal（企業員工入口平台）

Java 學習專案，依 `RenewFlow-v2-Java-EIP-Learning-Guide.md` 與 **RenewFlow Engineering Method v2**（開發方法名稱）建立。
**目前進度：T01、T02、T03、T03.1**（專案基礎、登入／登出、上下班打卡與本人出勤紀錄、出勤更正／重新打卡申請與管理權限）。T04 以後尚未開始。

> 命名說明：產品顯示名稱是 TeamPortal；Repository（`java-eip`）、資料夾、Java package（`com.renewflow.eip`）、資料庫、容器與環境變數名稱沿用既有設定。

| 想看什麼 | 去哪裡 |
|---|---|
| 規格（需求、架構、資料、API、安全、本階段 proposal／design／specification／tasks） | [docs/renewflow/](docs/renewflow/) |
| T03 打卡規格 | [docs/renewflow/t03/](docs/renewflow/t03/) |
| T03.1 更正／重新打卡／管理權限規格 | [docs/renewflow/t03-1/](docs/renewflow/t03-1/)（第 1 輪複審：[review-round-1.md](docs/renewflow/t03-1/review-round-1.md)） |
| 程式解說、資料流、驗證證據、面試題 | [docs/learning/](docs/learning/)（最新：[T03-1-review-fixes.md](docs/learning/T03-1-review-fixes.md)；T03.1 主文件：[T03-1-attendance-corrections.md](docs/learning/T03-1-attendance-corrections.md)） |

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
| `RENEWFLOW_DEV_SEED_RESET` | 設為 `true` 並重啟一次：只更新開發帳號的密碼雜湊（保留 ID 與出勤資料）；用完改回 `false` | `false` |

隔離 E2E 堆疊（`npm run e2e:attendance`、`e2e:corrections` 自動設定，平常不需要碰）使用 `RENEWFLOW_E2E_RUN_ID`、`RENEWFLOW_E2E_PASSWORD`、`E2E_API_PORT`（8081）、`E2E_API_TARGET`、`E2E_VITE_PORT`（5174）。

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

`npm run e2e` 依序執行五支腳本，也可單獨執行：
- `npm run e2e:smoke`（基本流程）、`npm run e2e:logout`（登出失敗情境）、`npm run e2e:password`（密碼位元組提示）：對你正在跑的 dev 環境（5173／8080）執行，只登入／登出開發帳號、讀取畫面，**不會打卡、不會提出申請**。
- `npm run e2e:attendance`（T03 打卡）、`npm run e2e:corrections`（T03.1 申請／核准／重新打卡）：**不使用你的 dev 環境**。各自啟動一組**隔離堆疊**——另一個後端（`spring-boot:test-run`，埠 8081，連**測試資料庫**）、另一個 Vite（埠 5174）、並為本次建立全新的專用員工與管理員；結束時自動關閉。因此不會碰到你的開發帳號與出勤資料，重複執行也不需要清空任何東西。需要 Java／Maven、資料庫容器在執行，且 8081、5174 沒有被占用（被占用時它會拒絕執行）。隔離後端在**套用 migration 之前**就會確認連到的是測試資料庫。

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

**出勤 API（T03）**——不接受員工 ID、日期或打卡時間，一律由 Session 與伺服器決定：

```powershell
curl.exe -s -b jar.txt http://localhost:8080/api/attendance/me/today                    # 今日狀態
curl.exe -s -b jar.txt "http://localhost:8080/api/attendance/me?from=2026-10-01&to=2026-10-31"   # 本人紀錄（含已作廢；省略參數＝本月；最多 31 天）
curl.exe -s -X POST -b jar.txt -H "X-XSRF-TOKEN: $token" http://localhost:8080/api/attendance/clock-in
```

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

本機學習規則（不代表任何公司制度）：固定白班、台北時區、每人每日最多一筆**有效**出勤；不支援跨日班、輪班、未來時間。誤按或漏打需透過申請（或管理員直接更正），且都會留下歷程；原始紀錄不會被硬刪。

沒有登入失敗鎖定、改密碼、員工管理、多層審核、通知；Session 存在記憶體（重啟後端需重新登入）；正式環境的同源部署與 HTTPS 設定尚未處理（只定義了 Cookie `Secure` 預設為 true）。詳見安全設計文件。

## 9. 出勤更正操作步驟（T03.1）

> 系統不會替任何人自動作廢或修改出勤。以下步驟都由你自己在網站上操作。

**先選對申請（員工）**——三種操作對資料的影響不同：

| 情況 | 用哪個 | 在哪裡 | 核准後 |
|---|---|---|---|
| 還在工作，卻誤按了下班 | **申請撤銷下班打卡** | 「今日打卡」卡片（只在已下班時出現） | 同一筆紀錄：上班時間完全不變、下班清空，恢復「工作中」，可再按下班 |
| 今天整組都要重來（例如測試時誤按上下班，或上班打錯） | **申請整組作廢並重新打卡** | 「今日打卡」卡片（只有上班也可以） | 整筆紀錄作廢（原時間保留在歷程），今日回到「尚未上班」，要重新打上班與下班卡 |
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
