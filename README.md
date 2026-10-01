# RenewFlow Java EIP 學習專案

依 `RenewFlow-v2-Java-EIP-Learning-Guide.md` 與 RenewFlow Engineering Method v2 建立。**目前進度：T01、T02**（專案基礎、資料庫初始化、登入／登出／目前使用者、登入頁與首頁框架）。T03 以後尚未開始。

| 想看什麼 | 去哪裡 |
|---|---|
| 規格（需求、架構、資料、API、安全、本階段 proposal／design／specification／tasks） | [docs/renewflow/](docs/renewflow/) |
| 程式解說、資料流、驗證證據、面試題 | [docs/learning/T01-T02-foundation-and-login.md](docs/learning/T01-T02-foundation-and-login.md) |

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
notepad .env          # 把 DB_PASSWORD 與 RENEWFLOW_DEV_SEED_PASSWORD 改成你自己的值（只用英數字元）

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

看到 `Started EipApplication` 即成功；第一次會套用 migration 並建立三個開發帳號。

終端機 B — 前端（http://localhost:5173）：

```powershell
cd frontend
npm run dev
```

用瀏覽器開 <http://localhost:5173>。

**開發帳號**（密碼都是你在 `.env` 設定的 `RENEWFLOW_DEV_SEED_PASSWORD`）：

| 帳號 | 角色 |
|---|---|
| `employee` | 員工 |
| `manager` | 主管 |
| `admin` | 管理員 |

開發帳號只會在 `dev` profile 且該帳號不存在時建立；改 `.env` 的密碼**不會**更新已存在的帳號。忘記或想改開發帳號密碼時，**不需要重建整個資料庫**，只刪除這三個開發帳號後重啟後端即可重新建立（見第 7 節）。

停止：兩個終端機各按 `Ctrl+C`；資料庫 `docker compose stop`（保留資料）或 `docker compose down`（保留資料卷）。

## 4. 環境變數

| 名稱 | 用途 | 預設 |
|---|---|---|
| `DB_HOST` / `DB_PORT` | 資料庫位置 | `localhost` / `5432` |
| `DB_NAME` | 開發用資料庫 | `renewflow` |
| `DB_TEST_NAME` | 整合測試用資料庫（測試會清空它！） | `renewflow_test` |
| `DB_USER` | 資料庫帳號 | 無（必填） |
| `DB_PASSWORD` | 資料庫密碼 | 無（必填，缺少則後端無法啟動） |
| `RENEWFLOW_DEV_SEED_PASSWORD` | 開發帳號共用密碼；僅 dev profile 讀取 | 空＝不建立開發帳號 |

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

瀏覽器端冒煙測試（`frontend/e2e/smoke.mjs`）：需要**先**啟動資料庫、後端（dev profile）與前端開發伺服器，再開第三個終端機：

```powershell
cd frontend
npm run e2e
```

`npm run e2e` 依序執行三支腳本，也可單獨執行：`npm run e2e:smoke`（基本流程）、`npm run e2e:logout`（登出失敗情境）、`e2e:password`（密碼位元組提示）。腳本使用本機已安裝的 Edge／Chrome（找不到時設定環境變數 `BROWSER_PATH`），密碼自動讀根目錄 `.env`；截圖輸出在 `frontend/e2e/output/`（不進版控）。它登入／登出開發帳號，所以只能對 dev 環境執行。

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

## 7. 常見問題

| 現象 | 原因／處理 |
|---|---|
| 後端啟動失敗：`Could not resolve placeholder 'DB_PASSWORD'` | 沒有 `.env`，或不是從 `backend/` 目錄啟動 |
| 後端啟動失敗：連線被拒絕 | 資料庫沒啟動：`docker compose up -d`，並確認 Docker Desktop 已執行 |
| `docker compose` 說無法連到 `dockerDesktopLinuxEngine` | Docker Desktop 尚未啟動完成 |
| 測試失敗：`database "renewflow_test" does not exist` | 容器比初始化腳本更早建立，缺少測試資料庫。**只補建，不刪資料**：`docker compose exec db psql -U renewflow -d renewflow -c "CREATE DATABASE renewflow_test"`（名稱與使用者請對應 `.env` 的 `DB_TEST_NAME`、`DB_USER`、`DB_NAME`）。已存在時會回報 already exists，無害。 |
| 忘記／想更改開發帳號密碼 | 1) 改 `.env` 的 `RENEWFLOW_DEV_SEED_PASSWORD`；2) 只刪三個開發帳號：`docker compose exec db psql -U renewflow -d renewflow -c "DELETE FROM employee WHERE username IN ('admin','manager','employee')"`；3) 重新啟動後端（dev profile），它會用新密碼重新建立。這只影響這三筆資料；若之後的階段有資料關聯到這些帳號，改用其他方式（屆時文件會說明）。 |
| 測試啟動時出現「拒絕對資料庫執行 clean」 | 這是保護機制在擋：實際連到的資料庫不是明確允許的測試資料庫，或開發／測試資料庫設定相同。檢查 `.env` 的 `DB_NAME` 與 `DB_TEST_NAME` 必須不同，且測試庫名稱需為小寫並以 `_test` 結尾。 |
| 前端顯示「伺服器暫時無法使用」 | 後端沒開 |
| 5173 或 8080 埠被占用 | 關掉占用程式；前端設定了 `strictPort`，不會自動換埠 |

## 8. 本階段限制

沒有登入失敗鎖定、改密碼、員工管理；Session 存在記憶體（重啟後端需重新登入）；正式環境的同源部署與 HTTPS 設定尚未處理（只定義了 Cookie `Secure` 預設為 true）。詳見安全設計文件。
