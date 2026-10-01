# Specification：T01＋T02 驗收情境

「自動」＝後端整合測試；「手動／腳本」＝在瀏覽器實際操作或腳本驅動。結果見 `docs/learning/T01-T02-foundation-and-login.md` 的驗證證據。

| 編號 | 情境 | 預期 | 驗證方式 |
|---|---|---|---|
| S-01 | 後端可啟動 | 日誌顯示啟動完成，Flyway 套用 V1 | 手動 |
| S-02 | 乾淨資料庫套用 migration | `employee` 表與約束存在，`flyway_schema_history` 有 V1 且成功 | 自動 |
| S-03 | 登入成功 | 200＋使用者資料；Cookie `JSESSIONID` 為 HttpOnly；回應不含密碼雜湊 | 自動 |
| S-04 | 密碼錯誤 | 401 `BAD_CREDENTIALS` | 自動 |
| S-05 | 帳號不存在 | 401，且與 S-04 訊息相同 | 自動 |
| S-06 | 帳號停用 | 401 `BAD_CREDENTIALS` | 自動 |
| S-07 | 輸入缺漏（空帳號／空密碼） | 400 `VALIDATION_FAILED` | 自動 |
| S-08 | 未登入取 `/me` | 401 `UNAUTHENTICATED` | 自動 |
| S-09 | 未登入取不存在的 `/api/x` | 401（不洩漏路徑是否存在） | 自動 |
| S-10 | 登入後取 `/me` | 200＋正確使用者 | 自動 |
| S-11 | 登出後 `/me` | 401（舊 Session 失效） | 自動 |
| S-12 | 無 CSRF 登入 | 403 `CSRF_REJECTED` | 自動 |
| S-13 | 已登入、無 CSRF 登出 | 403，且仍保持登入 | 自動 |
| S-14 | 登入與登出後 CSRF token 更新 | 登入前、登入後、登出後三個 `XSRF-TOKEN` 值互不相同；登出後不需任何額外請求即可再次登入。（註：Cookie 型 token 屬「雙重提交」，伺服器不記憶舊 token，因此不能宣稱「舊 token 會失效」，輪替的目的是不沿用登入前的值） | 自動 |
| S-15 | 登入換發 Session ID | 登入前帶舊 JSESSIONID，登入後的 ID 不同；舊 ID 無法取得身份 | 自動 |
| S-16 | 員工登入後被停用 | 既有 Session 在**下一次** `/me` 回 401 並失效（停用到下一次 `/me` 之間，Session 仍可用——T03 起的寫入 API 需共用檢查，見安全設計） | 自動 |
| S-17 | 前端：未登入看到登入頁；錯誤帳密顯示錯誤；成功進首頁；首頁顯示使用者與「尚未啟用」卡片；登出回登入頁；重新整理後狀態正確 | | 腳本：`frontend/e2e/smoke.mjs`（`npm run e2e`） |

## 審查修正（R1～R4）新增情境

| 編號 | 情境 | 預期 | 驗證方式 |
|---|---|---|---|
| S-18 | 測試資料庫清空保護：正常測試庫放行；實際連到開發庫、開發／測試設定相同、名稱不符命名、無法確認名稱 | 全部拒絕且**不呼叫 clean** | 自動：`TestDatabaseGuardTest` |
| S-19 | 以 `?ApplicationName=eip_test` 偽裝開發庫的 URL | 資料庫實際回報開發庫名稱 → 拒絕（只讀取名稱，不改動資料） | 自動：`TestDatabaseGuardConnectionTest` |
| S-20 | 認證機制內部故障（受控替身拋出資料庫連線例外） | HTTP 500＋`INTERNAL_ERROR`；回應不含 jdbc／主機／帳號／密碼；伺服器 log 有原因 | 自動：`AuthFailureIntegrationTest` |
| S-21 | 帳號不存在、密碼錯誤、停用、缺 CSRF（在同一故障注入環境） | 401、401、401、403，行為不變 | 自動：`AuthFailureIntegrationTest` |
| S-22 | 密碼位元組：ASCII 72／73；中文 72／75；emoji 72／73；混合；必填政策不變；不 trim | 72 通過、超過 400 `VALIDATION_FAILED`，且超過時完全不進入認證 | 自動：`LoginRequestValidationTest`、`PasswordByteLimitIntegrationTest`、`AuthFailureIntegrationTest` |
| S-23 | 登出失敗：403、網路中斷、5xx（`/me` 仍為登入）；請求失敗但 `/me` 回 401；`/me` 也連不上 | 顯示可讀提示且載入結束／回登入頁／顯示 unavailable 與重試；無未處理的 Promise 拒絕 | 腳本：`npm run e2e:logout` |
| S-24 | 前端密碼位元組提示（中文 24／25、emoji 18／19、ASCII 72／73），繞過前端直送 75 bytes | 提示與停用送出符合預期；後端仍回 400 | 腳本：`npm run e2e:password` |
