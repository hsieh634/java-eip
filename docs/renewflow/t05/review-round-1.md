# T05 第 1 輪複審修正（R1～R3）

- **基準**：`77dac92235c2f85388ece4c33e7ce1b2dcc53a92`。
- **狀態**：已實作／作者自測完成／等待 ChatGPT 複審。T05 **尚未結案**。

**ChatGPT 本輪證據的性質**：
- 核對指定 SHA、實際程式、V7、規格與測試設計。
- 以實際的前端函式與 API 函式做 12 項受控檢查，確認以下三個問題。hooks、fetch 等使用受控替身。
- **沒有**完整重跑瀏覽器 E2E 或 Spring／PostgreSQL 整合測試。
- 310 項後端測試、12 支 E2E、build／lint 仍是作者自測證據。

## 1. Requirement：問題、觸發條件、原因、影響

### R1 成功回應無法使用，卻被當成「明確拒絕」而解鎖表單

| 觸發條件 | 原因 | 影響 |
|---|---|---|
| POST 已被後端處理（申請已建立），但前端拿不到可用的成功回應：① 201 的 JSON 損壞（`SyntaxError`）；② 成功狀態下讀取本文時連線中斷（`TypeError`）；③ JSON 合法但缺欄位，例如 `{}`；④ 回應內容與本次提交對不上，例如 requestId 不同 | `LeaveForm` 只把 `ApiError` 的 status 0 或 5xx 視為「結果不明」；其他例外都走「首次明確拒絕」分支；成功回應也沒有在執行期驗證 | ①②③：表單回到可編輯、沒有保存快照。使用者改內容後，會用**同一個 requestId** 送出不同內容（後端回 409 `REQUEST_ID_CONFLICT`，原申請不會被覆寫），原提交的確認流程被破壞。④：直接提示成功並換新表單 |

### R2 相同條件再按「查詢」沒有重新讀取

| 觸發條件 | 原因 | 影響 |
|---|---|---|
| 三種清單（我的請假、請假審核、請假管理）在同一頁用相同條件再按「查詢」 | `LeaveList.apply()` 建立新的 query 物件，但 `useLoad` 的 key 是 `JSON.stringify(query)`，條件相同時 key 不變 | 不會發出讀取請求，看不到別人剛完成的核准、撤回、改派；讀取失敗後按相同條件「查詢」也無法恢復（只能按「重試」） |

### R3 原指定審核人失效時，下拉選項與受控值不一致

| 觸發條件 | 原因 | 影響 |
|---|---|---|
| 指定請假審核人之後被停用或降成 EMPLOYEE，再打開這位員工的設定 | `approverId` 的 state 仍是原 ID，但選項只列有效的 MANAGER／ADMIN；受控的 value 沒有對應選項 | 畫面看起來是「不指定」，實際值卻是原 ID；變更摘要出現孤立的 `#ID`。看起來像已移除指定，實際上沒有 |

## 2. Domain、Architecture、Data Model、API、Security

都**沒有變動**：
- 業務規則（狀態機、重疊、審核人決定、requestId 語意、權限、禁止自審）不變。
- 後端、API 契約、資料表、V1～V7 migration 不變。
- 共用元件 `api/http.ts`、`useLoad`、認證流程不變。

## 3. Proposal：修改範圍

| 問題 | 修改的檔案 |
|---|---|
| R1 | 新增 `frontend/src/leave/submitOutcome.ts`（`checkSubmitResult`、`isUnconfirmed`、`UnconfirmedResultError`）；`api/leave.ts` 的 `submitLeave` 回傳 `unknown`；`pages/LeavePage.tsx` 的 `LeaveForm.send` |
| R2 | `frontend/src/leave/LeaveList.tsx`：新增 `searchSeq`，每次有效的「查詢」都遞增，並放進 useLoad 的 key |
| R3 | `frontend/src/pages/LeaveAdminPage.tsx` 的 `SettingForm`、`SettingEditor`：失效原值的保留選項（disabled）、警告、正確的前後摘要 |

不新增套件；只在請假提交的邊界修正，不改共用的 HTTP 行為。

## 4. Design

### 4.1 R1：提交結果的三分類

| 分類 | 條件 | 表單 |
|---|---|---|
| 已取得並驗證成功回應 | `checkSubmitResult` 通過：物件結構；`replayed` 是布林；`request.id` 是正整數；`requestId` 與本次相同；狀態合法（新建必為 PENDING，重送可為任何狀態）；假別、日期、起訖、原因與本次送出的快照完全相同；有審核人名稱 | 提示成功、換新表單（新的 requestId） |
| 已取得可確認的拒絕 | `ApiError` 且 4xx（含 401） | 第一次送出：可編輯；重試時：保留快照、仍鎖定；401：既有登入失效流程 |
| 無法確認 | 網路錯誤（status 0）、5xx、**任何非 ApiError**（本文讀取中斷、JSON 解析失敗、`UnconfirmedResultError`） | 保存當次快照與 requestId，鎖定為 unknown；重送只用原快照 |

狀態轉移：

```
editing ─送出→ submitting ─已驗證成功→（換新表單）
                         ├─可確認拒絕 4xx→ editing（第一次）
                         └─無法確認→ unknown（保存 pending 快照）
unknown ─重新送出原內容→ submitting（retrying）
          ├─已驗證成功→ 換新表單
          ├─401→ 登入失效流程
          └─其他任何結果（403、409、5xx、網路、回應無法使用）→ unknown（快照保留）
unknown ─放棄追蹤（看過警告）→ 換新表單
```

錯誤訊息只用固定的中文說明，不顯示 `SyntaxError`、`TypeError` 或解析細節。卸載後（`life.alive === false`）不更新畫面，也不呼叫 `onDone`。

**取捨**：「非 ApiError 一律無法確認」的前提是：`submitLeave` 在 fetch 之前不會丟出非 ApiError，`api()` 會把 fetch 本身的失敗轉成 `ApiError(0)`。所以非 ApiError 只會出現在「已收到回應之後」。

### 4.2 R2

`LeaveList` 的 key 加上 `searchSeq`。條件不合法時不遞增，也不發請求。換頁不遞增，key 本來就不同。

舊請求的回應交給 useLoad 原本的機制處理：key 一變，effect 的 `active` 變成 false，舊的成功、失敗或 401 都會被丟棄。不新增 polling，不修改 useLoad。

### 4.3 R3

原指定審核人不在有效選項中時，多列一個 `disabled` 的保留選項「目前指定：姓名（員工編號）（已無效）」：
- 受控值有了對應選項，畫面如實顯示原值。
- `disabled` 代表它不能被重新選取；其他員工的選項也不會出現這個人。
- 管理員可以直接選「不指定」或另一位有效審核人，不必先選別人。

系統不自動清空、不偷偷改用主管、不改主管關係。前後摘要一律用姓名與員工編號。後端仍拒絕新指派無效的審核人。

## 5. Specification：驗收與測試對照

測試：`frontend/e2e/leave-review.mjs`（`npm run e2e:leave-review`，隔離堆疊）。

| 驗收 | 測試 |
|---|---|
| 受控函式：合法的新建、replayed（已核准、已撤回）通過；`{}`、null、缺 replayed、不合法狀態、id 型別錯、requestId 或內容不符、缺審核人 → 無法確認；解析、讀取、網路、5xx → 無法確認；400／403／409／401 → 明確回應 | R1-0 |
| 後端已建立 → 201 損壞 JSON → unknown、原快照；重試遇 403 仍鎖定；再重試成功；3 次本文相同、後端 1 筆、1 筆送出歷程 | R1-1 |
| 201＋`{}` → 不提示成功、不清空；原申請被撤回後確認 → 顯示已建立（已撤回），不重建 | R1-2 |
| 成功回應 requestId 不符 → 不提示成功 | R1-3 |
| 成功狀態下本文讀取中斷（受控替身）→ 相同保護 | R1-4 |
| 首次明確拒絕（409 重疊）仍可編輯；合法新建正常完成 | R1-5 |
| 卸載後損壞回應晚到 → 不更新畫面；結果不明時帳號被停用 → 重試得到真實 401 → 登入頁、沒有再送出；沒有未處理例外 | R1-6 |
| 三種清單相同條件按兩次都讀取兩次；他人核准、撤回、改派後按相同條件看到最新 | R2-1、R2-4、R2-5 |
| 舊回應晚到不覆蓋新結果 | R2-2 |
| 失敗後再次查詢、重試都能恢復 | R2-3 |
| 失效原值：選單值與選項一致、明示已無效、摘要沒有 `#ID`；直接選「不指定」儲存成功（版本 2）；再換成有效審核人（版本 3）；歷程與原因正確；主管關係不變；後端仍拒絕新指派無效者；其他員工的選項不出現該人 | R3 |

## 6. Tasks

| # | 工作 | 狀態 |
|---|---|---|
| RR1-1 | 本文件 | ☑ |
| RR1-2 | 新測試 `leave-review.mjs`，在基準版本執行並記錄失敗 | ☑ |
| RR1-3 | R1 修正 | ☑ |
| RR1-4 | R2 修正 | ☑ |
| RR1-5 | R3 修正 | ☑ |
| RR1-6 | build、lint、相關 E2E 回歸 | ☑ |
| RR1-7 | 文件（學習、規格、README、version-log） | ☑ |
| — | ChatGPT 複審 | ☐ 等待中 |

## 7. Implementation、Automated Tests、Manual Verification

結果見 `verification.md` 第 6 節與 `docs/learning/T05-review-fixes.md`。

## 8. 本輪限制

- 本文讀取中斷是用頁面內的受控替身模擬：請求是真的，後端也真的建立，只把交給程式的回應換成讀取時會失敗的物件。這不是真實網路斷線。
- 受控函式測試是從隔離堆疊的 Vite 載入實際模組來執行，沒有新增單元測試框架。
- 跨重新整理的保護仍然沒有（原設計，A-88）。
