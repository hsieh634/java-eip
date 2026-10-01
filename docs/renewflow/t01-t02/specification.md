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
| S-16 | 員工登入後被停用 | 已有 Session 呼叫 `/me` → 401 | 自動 |
| S-17 | 前端：未登入看到登入頁；錯誤帳密顯示錯誤；成功進首頁；首頁顯示使用者與「尚未啟用」卡片；登出回登入頁；重新整理後狀態正確 | | 腳本：`frontend/e2e/smoke.mjs`（`npm run e2e`） |
