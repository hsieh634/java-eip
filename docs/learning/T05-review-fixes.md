# T05 第 1 輪複審修正（R1～R3）——學習文件

> 規格：[`docs/renewflow/t05/review-round-1.md`](../renewflow/t05/review-round-1.md)。
> 狀態：已實作待驗證（作者自測完成，等待 ChatGPT 複審）。只修改前端；後端、API、migration 都沒有變動。

## 1. 快速摘要

| | 問題 | 修正 |
|---|---|---|
| R1 | 請假送出後，若成功回應「拿不到或不能用」（JSON 損壞、本文讀取中斷、缺欄位、requestId 對不上），表單誤以為「後端沒有建立」而解鎖，甚至提示成功 | 成功回應先在執行期驗證；POST 送出後凡是不能確認的結果，一律保存原快照與 requestId、鎖定表單 |
| R2 | 相同條件再按「查詢」不會重新讀取 | 每次有效的查詢遞增 `searchSeq`，放進 useLoad 的 key |
| R3 | 原指定審核人失效後，下拉選單看起來是「不指定」，實際值卻是原 ID | 加一個 disabled 的「目前指定（已無效）」保留選項，摘要用姓名與員工編號 |

## 2. 操作入口

- **R1**：「請假」頁送出申請。若出現「無法確認原提交是否已建立」，表單會鎖定在原內容，可以按「重新送出原內容」，或看過警告後按「放棄追蹤」。
- **R2**：三個清單（我的請假、請假審核、請假管理）的「查詢」按鈕。
- **R3**：「請假管理」→「指定請假審核人」→ 搜尋員工 → 下拉選單。

## 3. 檔案與方法

| 檔案 | 方法／重點 |
|---|---|
| `frontend/src/leave/submitOutcome.ts` | `checkSubmitResult(raw, requestId, sent)`、`isUnconfirmed(error)`、`UnconfirmedResultError` |
| `frontend/src/api/leave.ts` | `submitLeave` 改為回傳 `unknown` |
| `frontend/src/pages/LeavePage.tsx` | `LeaveForm.send`：先驗證、再判斷 |
| `frontend/src/leave/LeaveList.tsx` | `searchSeq`、`apply()` |
| `frontend/src/pages/LeaveAdminPage.tsx` | `SettingForm`：`originalMissing`、`labelOf`、保留選項；`SettingEditor`：目前指定顯示「已無效」 |
| `frontend/e2e/leave-review.mjs` | 本輪的回歸測試 |

## 4. 「伺服器已提交」與「前端收到可用回應」是兩件事

```
瀏覽器 ──POST──► 後端：INSERT、COMMIT（申請已建立）
       ◄──201──  回應標頭到達：response.ok === true
       讀本文：response.json()  ← 可能在這裡失敗：連線中斷（TypeError）、內容截斷（SyntaxError）
       使用資料：result.request.id ← 可能在這裡失敗：欄位不存在
```

HTTP status 只告訴你「伺服器怎麼處理」，而本文要讀完、解析完、驗證完，前端才「知道結果是什麼」。在這中間任何一步失敗，後端早已提交，所以**不能**推論成「沒有建立」。

原本的程式只把 `ApiError` 的 status 0 和 5xx 當成「結果不明」：
```ts
const uncertain = e instanceof ApiError && (e.status === 0 || e.status >= 500)
```
`SyntaxError`、`TypeError` 不是 `ApiError`，就掉進「明確拒絕」分支：表單解鎖、快照沒保存。

現在改為：
```ts
export function isUnconfirmed(error: unknown): boolean {
  if (error instanceof ApiError) return error.status === 0 || error.status >= 500
  return true // 讀取中斷、解析失敗、結構驗證失敗：POST 已送出，結果無法確認
}
```
只有拿到可確認的 4xx 拒絕（含 401），才算「明確」。

## 5. 為什麼 TypeScript 型別不會驗證網路資料

`api<{ replayed: boolean; request: LeaveRequest }>(…)` 只是告訴編譯器「請相信它是這個形狀」。編譯後型別完全消失，`response.json()` 回傳什麼就是什麼。

所以 `submitLeave` 改成回傳 `unknown`，強迫呼叫端先驗證：
```ts
const result = checkSubmitResult(await submitLeave({ ...values, requestId }), requestId, values)
```
`checkSubmitResult` 只驗證畫面實際會用的欄位，並確認這是**這一次**提交：
- `id` 是正整數、狀態合法（新建必為 PENDING）；
- `requestId` 相同；
- 假別、日期、起訖、原因與送出的快照完全一致；
- 有審核人名稱。

不符就丟出 `UnconfirmedResultError`，由同一套「無法確認」流程處理。

## 6. requestId 與原內容快照

requestId 代表「這一次邏輯上的提交」，它必須永遠和**同一份內容**綁在一起：
- 結果不明時保存 `pending`（當次送出的內容）。重送只送 `pending`，不依目前欄位重組。
- 後端用 requestId 判斷：內容相同 → 回傳原申請（`replayed`，可能已被核准、撤回…）；不同 → 409。
- 舊程式在 R1 的情境會解鎖表單，讓使用者改內容後用同一個 requestId 送出，正好撞上後端的 409。後端保護住了資料，但前端的確認流程被破壞。

## 7. 相同查詢條件、React state 與 effect key（R2）

```ts
const list = useLoad(() => load(query), `${loadKey}:${JSON.stringify(query)}:${extraKey}:${refreshSeq}:${searchSeq}`)
```
- useLoad 以 key 決定「要不要重新讀取」。`setQuery({ ...draft })` 雖然換了一個新物件，但 `JSON.stringify` 的結果一樣，key 也就一樣，effect 不會重跑。
- 解法是把「使用者按了一次查詢」本身變成狀態（`searchSeq`）。
- 舊回應不會覆蓋新結果，是 useLoad 既有的保護：key 一變，舊 effect 的 `active` 就是 false，舊回應的成功、失敗、401 都會被丟棄。

## 8. 下拉選項缺少受控 value（R3）

React 的受控 `<select value={x}>`，若 `x` 沒有對應的 `<option>`：
- 瀏覽器會顯示第一個選項（這裡是「不指定」）；
- React 的 state 仍是原 ID。

畫面和資料不一致，摘要也找不到名稱，只能顯示 `#ID`。

修正：原指定審核人不在有效選項時，多列一個保留選項：
```tsx
{originalMissing && original && (
  <option value={original.id} disabled>目前指定：{original.displayName}（{original.employeeNo}）（已無效）</option>
)}
```
`disabled` 讓它只表示原值、不能被新選取。後端的 `APPROVER_INVALID` 規則沒有放寬。

## 9. 測試如何重現與驗證

測試是 `frontend/e2e/leave-review.mjs`，在隔離堆疊執行。為了公平比較，我暫時 stash 本輪的前端修改，用**最終版測試**跑舊程式，跑完立刻還原。

| 版本 | 結果 |
|---|---|
| 舊程式（基準 `77dac92`） | **24／43**，失敗項見下表 |
| 修正後 | **46／46** |

舊程式的失敗：

| 項目 | 舊版本的實際結果 | 是否算舊缺陷證據 |
|---|---|---|
| R1-1 損壞 JSON | `phase: editing`、未鎖定；只送出 1 次（無法重送原快照） | 是 |
| R1-2 `{}` | `phase: editing` | 是 |
| R1-3 requestId 不符 | 表單被清空（視為成功） | 是 |
| R1-4 本文讀取中斷 | `phase: editing` | 是 |
| R1-6 401 流程 | 前置的「結果不明」無法成立，所以沒有重試 | **否**（舊程式的 401 流程本身沒有問題） |
| R1-0 受控函式 | 模組不存在 | **否**（只代表新函式不存在） |
| R2-1、R2-4、R2-5 | 相同條件按兩次查詢：**0 次**讀取；看不到他人的核准、撤回、改派 | 是 |
| R2-2、R2-3（準備） | 沒有發出請求，無法安排先後或失敗 | 是（同一個原因） |
| R3 | 選單值 `""`、顯示「不指定」；摘要「E2E 臨時主管 M → #193」 | 是 |

**回歸（修正後，本次執行）**：
- `npm run lint`、`npm run build` 通過。
- E2E：leave 56／56、leave-review 46／46、timesheets 49／49、timesheets-review 24／24、timesheets-retry 25／25、organization 54／54。

**沒有重跑**：
- 其他 7 支 E2E：smoke、logout、password、attendance、corrections、geofence、races。本輪沒有修改共用的 HTTP、認證、useLoad 或導覽。
- 後端 310 項（後端沒有修改，沿用上一輪證據）。

**證據範圍**：
- 損壞 JSON、`{}`、requestId 不符：瀏覽器真的送出 POST，由測試轉送給後端，後端真的建立；瀏覽器收到的是被替換的成功回應。
- 本文讀取中斷：請求是真的、後端真的建立，但交給程式的回應物件是受控替身，不是真實的網路斷線。
- 受控函式：從 Vite 載入實際模組，直接呼叫函式。
- 每一項都比對 requestId、送出內容、讀取次數與後端筆數，不只比對提示文字。

## 10. 取捨與面試重點

- **取捨**：「非 ApiError 一律無法確認」的前提是 fetch 之前不會丟出非 ApiError（`api()` 已把 fetch 的失敗轉成 `ApiError(0)`）。代價是：未來若在送出前加入可能丟例外的程式，就要重新檢查這個前提。
- **面試**：
  1. 「201 但讀不到本文」該怎麼處理？為什麼不能視為失敗？
  2. 執行期驗證和 TypeScript 型別各自負責什麼？
  3. 為什麼要驗證「回應就是這一次提交」（requestId、內容）？
  4. 用 key 驅動資料讀取時，「相同條件再查一次」要怎麼設計？
  5. 受控 select 的值不在選項中會發生什麼？

## 練習（不附答案）

工時表單（`TimesheetPage.tsx` 的 `EntryFields.send`）目前也只把 `ApiError` 的 status 0／5xx 視為結果不明，而且新增成功後沒有驗證回應內容。請找出在哪些情況下它會出現與 R1 相同的問題，並說明你會怎麼用最小的修改處理；後端的 `DUPLICATE_SUBMISSION` 會讓處理方式和請假有什麼不同？
