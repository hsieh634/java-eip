# API 設計（T02；T03 出勤 API 見 [`t03/03-api-design.md`](t03/03-api-design.md)；T03.1 見 [`t03-1/04-api-design.md`](t03-1/04-api-design.md)；T03.2 定位打卡與設定見 [`t03-2/05-api-design.md`](t03-2/05-api-design.md)）

基底路徑 `/api`；請求與回應皆為 JSON（UTF-8）。

## 通用規則

- 寫入方法（POST／PUT／PATCH／DELETE）必須帶 CSRF 標頭 `X-XSRF-TOKEN`，值取自 Cookie `XSRF-TOKEN`。
- 錯誤回應格式：`{ "code": "<機器可讀代碼>", "message": "<給人看的訊息>" }`。
- 不在回應中洩漏堆疊、SQL、雜湊或「帳號是否存在」。

## 端點

### POST /api/auth/login

| 項目 | 內容 |
|---|---|
| 需要登入 | 否（但需要 CSRF） |
| 請求 | `{ "username": "string(1..50 字元)", "password": "string(非空白，UTF-8 ≤ 72 bytes)" }` |
| 200 | `CurrentUserResponse`，並以 `Set-Cookie: JSESSIONID`（HttpOnly）建立 Session，更新 `XSRF-TOKEN` |
| 400 | `VALIDATION_FAILED`：欄位空白、帳號超過 50 字元、**密碼超過 72 個 UTF-8 位元組**（在認證之前就拒絕） |
| 500 | `INTERNAL_ERROR`：認證機制本身故障（例如資料庫連不上）；**不是 401**，回應不含細節 |
| 401 | `BAD_CREDENTIALS`：帳號不存在、密碼錯誤、帳號停用——**三者訊息相同** |
| 403 | `CSRF_REJECTED`：缺少或錯誤的 CSRF token |

密碼上限是 **72 個 UTF-8 位元組**（不是 72 個字元）：bcrypt 只使用前 72 bytes，超過的部分會被忽略，「看似不同的密碼其實等價」。英數字 1 byte、中文字 3 bytes、emoji 4 bytes。不 trim、不截斷。

### GET /api/auth/me

| 項目 | 內容 |
|---|---|
| 需要登入 | 是 |
| 200 | `CurrentUserResponse` |
| 401 | `UNAUTHENTICATED`：未登入、Session 過期、員工已被停用或刪除 |

### POST /api/auth/logout

| 項目 | 內容 |
|---|---|
| 需要登入 | 否（未登入呼叫也回 204，登出是冪等操作）；需要 CSRF |
| 204 | Session 失效、`JSESSIONID` 與 `XSRF-TOKEN` Cookie 清除 |
| 403 | `CSRF_REJECTED` |

## DTO

`CurrentUserResponse`

```json
{ "id": 1, "employeeNo": "E0001", "username": "employee", "displayName": "示範員工", "role": "EMPLOYEE" }
```

不包含 `passwordHash`、`enabled`、時間戳等不需要的欄位。

## 其他路徑

任何其他 `/api/**` 路徑：未登入回 401；登入後不存在則回 404（`NOT_FOUND`）。

## SPA 如何處理 CSRF（與前端的約定）

1. 頁面載入時前端呼叫 `GET /api/auth/me`。不論結果是 200 或 401，回應都會帶上 `XSRF-TOKEN` Cookie。
2. 每次寫入前**即時讀取** Cookie 並放入 `X-XSRF-TOKEN`（不快取在記憶體，登入／登出後 token 會更新）。
3. 登入成功與登出成功的回應都會 `Set-Cookie` 新的 `XSRF-TOKEN`，因此登出後不需額外請求即可再次登入。
4. 前端不自行記憶登入狀態：登入後以回應設定畫面；登出後重新呼叫 `/me`，以後端回覆（401）為準。
