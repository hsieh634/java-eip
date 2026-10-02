# T03.2 第 1 輪複審修正（R1、R2）——學習說明

審查基準：`2d6f333`；修正 commit 見交付報告（標題 `fix: T03.2 review round 1 ...`）。規格：`docs/renewflow/t03-2/review-round-1.md`。
狀態：**已實作／作者自測完成／等待 ChatGPT 複審**。

---

## 1. 三分鐘摘要

**問題**：打卡卡片的非同步流程有兩個漏洞。
- **R1**：按下打卡後，前端在等「定位設定」時員工切頁，舊操作在設定回來後仍可能呼叫定位或送出打卡——取消檢查只放在「定位結果回來之後」。
- **R2**：兩次今日狀態查詢的回應順序顛倒時，舊回應（NOT_STARTED）覆蓋新回應（WORKING），等待中的上班操作因此又被允許送出。

**修正**（只改 `frontend/src/attendance/useTodayAttendance.ts`）：
1. 每次掛載有一個**永不復活**的 `Lifetime` 物件；每次打卡有一個 `Operation`。在所有 `await` 之後、所有副作用（定位、POST、更新畫面）之前，都問一次「我還有效嗎」。
2. 今日狀態只有一個查詢入口 `load()`，每次發出拿一個序號，**只採用最後發出的那一次**的結果（成功或失敗都一樣）。
3. 採用結果時同步更新 `latestState` ref，打卡流程立刻看得到。

**取消的界線**：POST 送出前卸載 → 一定不送；POST 已送出 → 前端只是不再理會回應，**不能**撤回伺服器的寫入。

**證明**：新的 `geofence-races.mjs` 用請求攔截與手動定位安排事件順序。修正前 22 項中 11 項失敗，修正後 22/22；其他 E2E 全部通過。

## 2. 實際檔案與方法

| 檔案 | 位置 | 內容 |
|---|---|---|
| `frontend/src/attendance/useTodayAttendance.ts` | `Lifetime`（71）、`Operation`（76） | 掛載有效期間、打卡操作 |
| 同上 | `lifetime`、`activeOperation`、`loadSeq`、`latestState` refs（104～111） | 即時值 |
| 同上 | 掛載 effect（113）、`isCurrent`（122）、`applyState`（125）、`load`（134） | 有效判斷、同步採用、查詢序號 |
| 同上 | `end`（176）、`locate`（191）、`perform`（226） | 結束、定位、打卡主流程 |
| `frontend/e2e/geofence-races.mjs` | `controlRequests`、`settle`、R1-1～R2-7 | 受控回應的競態測試 |
| `frontend/e2e/geo.mjs` | `installFakeGeolocation`（含 manual 模式）、`resolveGeo`、`rejectGeo` | 共用假定位 |

## 3. 事件時間線

### R1（修正前）
```
t0 按「上班打卡」 → perform：inFlight=true，GET location-policy（等待中）
t1 點選「出勤打卡」頁 → 首頁的卡片卸載（mounted=false），新頁面掛載另一張卡片
t2 location-policy 回來：{ clockInRequired: false }
t3 舊 perform 繼續往下：不需定位 → 沒有任何檢查 → POST /api/attendance/clock-in   ← 錯
   （若回 required：先呼叫 getCurrentPosition，定位回來後才發現 mounted=false）
```
修正後：t2 之後第一行就是 `if (!isCurrent(op)) return`，舊操作在這裡結束。

### R2（修正前）
```
t0 按「上班打卡」，正在等定位（manual）
t1 刷新 → GET today（A，舊）       t2 刷新 → GET today（B，新）
t3 另一個分頁上班成功（伺服器：WORKING）
t4 B 先回 WORKING → 畫面「工作中」
t5 A 才回 NOT_STARTED → reload 只看 mounted → 畫面又變「尚未上班」   ← 錯
t6 定位回來 → stillAllowed('in', NOT_STARTED) = true → POST clock-in（後端 409）   ← 錯
```
修正後：t5 時 A 的序號不是最新（B 的比較大），A 的回應被丟棄；t6 時 `latestState` 是 WORKING，操作被取消，0 次 POST。

## 4. mounted、操作識別碼、請求序號各自解決什麼

| 工具 | 回答的問題 | 單獨使用的漏洞 |
|---|---|---|
| mounted 布林值 | 「元件現在還在嗎？」 | 只描述「現在」。StrictMode 開發模式會 setup → cleanup → setup，布林值變回 true，早先開始的流程分不出自己屬於已經結束的那次掛載 |
| `Lifetime` 物件 | 「我開始時的那一次掛載還在嗎？」 | 流程開始時記住物件；卸載把**那個物件**設為 false，新掛載是**另一個**物件，所以舊流程永遠是 false |
| 操作識別（`Operation`＋`activeOperation`） | 「我還是目前的那個操作嗎？」 | 掛載仍在、但已有新操作（或已結束）時，舊操作不能再改 busy、notice |
| 請求序號（`loadSeq`） | 「這個回應是不是最後一次發出的查詢？」 | 掛載與操作都有效時，兩個查詢的回應順序仍可能顛倒——只有序號能分辨新舊 |

三者互補：`isCurrent(op) = op.lifetime.alive && activeOperation.current === op`；`isLatest() = life.alive && seq === loadSeq.current`。

## 5. 關鍵片段

### 5.1 掛載有效期間（StrictMode 安全）
```ts
useEffect(() => {
  const current: Lifetime = { alive: true }
  lifetime.current = current
  return () => {
    current.alive = false          // 只關掉「這一次」掛載；永不再設回 true
    activeOperation.current = null
  }
}, [])
```
`lifetime` 的初始值是 `{ alive: false }`：effect 還沒執行前，任何東西都不算有效。

### 5.2 唯一的查詢入口
```ts
const load = useCallback((): Promise<void> => {
  const seq = ++loadSeq.current
  const life = lifetime.current
  const isLatest = () => life.alive && seq === loadSeq.current
  return attendanceApi.fetchToday().then(
    (data) => { if (isLatest()) applyState({ kind: 'ready', data }) },
    (error) => {
      if (!isLatest()) return                       // 過期的錯誤（含 401）完全忽略
      if (error instanceof ApiError && error.status === 401) { void refresh(); return }
      applyState({ kind: 'error', message: … })
    },
  )
}, [applyState, refresh])
```
- 規則是「最後**發出**的那一次」，不是「最後**回來**的那一次」：新查詢失敗、舊查詢晚到成功時，畫面維持錯誤（R2-3）——舊成功不能冒充最新資料；使用者按重試即可。
- 寫成 `.then(成功, 失敗)` 而不是 `async/await`：效果相同；這樣寫讓 lint 規則看得出 setState 是在非同步回呼中執行（首次載入的 effect 直接呼叫 `load()`）。

### 5.3 打卡主流程的檢查點
```ts
const op = { id: ++operationSeq.current, action, lifetime: lifetime.current }
activeOperation.current = op
...
policy = await fetchLocationPolicy()
if (!isCurrent(op)) return                    // ① 設定回來時已切頁：不定位、不送出
if (requiresLocation(action, policy)) {
  const located = await locate(op, policy)    // ② 呼叫定位前、③ 定位回來後（在 locate 內）
  ...
}
if (!isCurrent(op)) return                    // ④ 送出前（兩條路徑共用）
if (!stillAllowed(action, latestState.current)) { await load(); end(op, 取消提示); return }
await clockIn(location)                       // ← 越過這行，請求已經送出
await load()
if (!isCurrent(op)) return                    // ⑤ 已卸載：不更新畫面、不呼叫 onChanged
onChanged?.(); end(op, outcome)
```
`end(op, …)` 開頭也是 `if (!isCurrent(op)) return`：過期的操作不能清掉較新操作的 busy。`action` 存在 `op` 裡，從頭到尾不會因畫面刷新從上班變成下班。

## 6. useRef 的即時值 vs React state／effect

- `setState(x)` 不會立刻改變目前這次 render 的變數；新的值在**下一次 render** 才看得到，而 `useEffect` 又在那次 render **提交之後**才執行。
- 修正前 `latestState` 是在 `useEffect(() => { latestState.current = state }, [state])` 更新的：從「收到回應」到「ref 更新」之間至少隔一次 render 與一次 effect。非同步流程若剛好在這段空檔檢查 ref，會看到舊值。
- 修正後在 `applyState` 裡**同時**寫 ref 與 state：ref 立即反映已採用的回應，畫面隨後跟上。
- 同理，防連按要用 ref（`activeOperation`）：同一個事件迴圈內的兩次 `click()` 會看到同一個舊的 state，但 ref 的寫入立即可見。

## 7. 為什麼取消前端等待，不能撤回伺服器寫入

POST 一旦送出，請求可能已經抵達後端並提交交易。前端「不理會回應」、切頁、甚至 `AbortController.abort()`，都只是讓**瀏覽器**不再等待或不再處理回應；伺服器端的交易不會因此回滾。所以：
- 能保證的只有「送出之前」：這就是第 ④ 個檢查點存在的原因。
- 送出之後：前端只能在重新進入畫面時查詢真實狀態（`load()`），並且**不自動重送**——否則可能重複打卡（雖然後端仍會以 409 擋住，前端不該依賴這件事製造多餘請求）。
- 這也是 T03 以來「結果不明時重新查詢」的原則。

## 8. 如何用受控回應測試競態

不靠 `sleep` 碰運氣，而是讓測試**決定**每個事件發生的順序：
- **請求攔截**（puppeteer `setRequestInterception`）：`location-policy`、`me/today` 等請求被扣住；測試再決定 `continue()`（交給真的後端）、`respond()`（偽造，例如舊的 NOT_STARTED 本文）、`abort()`（模擬網路中斷）。等待條件都是「被扣住的請求數達到 n」或「畫面出現某文字」。
- **手動定位**（`e2e/geo.mjs` 的 manual 模式）：`getCurrentPosition` 的回呼被保存，測試用 `resolveGeo()`／`rejectGeo()` 決定何時成功或失敗。
- **計數**：每頁記錄 policy、today、clock-in、clock-out、auth/me 的請求數，斷言用「本情境的增量」，各情境互不影響。
- 唯一的固定等待是 `settle()`：只用在「某件事**不應該**發生」的斷言前——事件已確定送達瀏覽器，再給錯誤的程式一小段時間犯錯。

例：R2-1 先扣住兩次 today（A、B），讓 B 走真的後端（WORKING），再讓 A 回一份事先存下的 NOT_STARTED 本文，最後才讓定位成功。

## 9. 如何證明測試抓得到舊的錯誤寫法

1. 先寫測試、**不改程式**，在修正前的版本（`2d6f333`）執行：22 項中 **11 項失敗**，例如：
   - R1-1：`{"geoCalls":0,"in":1}`——舊操作送出了上班 POST
   - R1-2：`{"geoCalls":1}`——舊操作呼叫了定位
   - R2-1：畫面被改回「尚未上班」，定位回來後送出 1 次上班 POST
   - R2-4：過期的 401 觸發了登入狀態刷新
2. 修正後同一支測試 22/22。
3. R1-3（定位已開始後切頁）在修正前就通過——舊程式在定位回來後有檢查。保留它，防止日後把那個檢查移除。

本輪沒有修改後端；後端 209 個測試沿用 T03.2 的證據（**本次未重跑**）。

## 10. 設計取捨

| 選擇 | 理由 | 替代 |
|---|---|---|
| ref＋識別碼的最小實作 | 規則少、容易讀；不新增套件 | 狀態機套件（XState 等）：本規模過度 |
| 「最後發出者勝」 | 規則簡單，舊的永遠不會覆蓋新的 | 比較回應中的時間戳：後端沒有提供單調版本；「最後回來者勝」正是 R2 的錯誤 |
| 不使用 `AbortController` | 中止請求不能撤回伺服器寫入，也不改變語意；序號已足以丟棄過期回應 | 加上中止：省一點頻寬，但多一層要解釋的狀態 |
| 新查詢失敗時不退回較舊的成功 | 不把舊資料冒充成最新；使用者可重試 | 退回舊資料：畫面可能顯示錯誤的「可以上班」 |

## 11. 面試重點

**Q1. 為什麼 mounted 布林值不夠？** 它只回答「現在還在嗎」。StrictMode 的卸載再掛載會讓它變回 true；舊流程需要知道的是「我開始的那一次還在嗎」——用每次掛載一個新物件解決。

**Q2. 為什麼 setState 之後立刻讀 ref 可能是舊值？** state 在下一次 render 才生效、effect 在提交後才跑；若 ref 由 effect 同步，中間有空檔。要即時就在採用結果時同時寫 ref。

**Q3. 「最後發出者勝」與「最後回來者勝」差在哪？** 後者在回應順序顛倒時會讓舊資料覆蓋新資料（R2）。前者用發出時拿到的序號判斷。

**Q4. 切頁可以取消打卡嗎？** 只能取消「還沒送出」的部分；已送出的請求無法撤回，只能重新查詢真實狀態，且不自動重送。

**Q5. 怎麼測競態才不會時好時壞？** 讓測試控制事件順序（攔截並決定回應、手動控制定位），而不是調整 sleep 長度。

## 練習（只有這一題）

**背景**：`load()` 採「最後發出者勝」：每次查詢在發出時取得 `seq = ++loadSeq.current`，回應回來時只有 `seq === loadSeq.current` 才採用。

**問題**：假設有人把它改成「回應回來時才遞增序號，並只採用序號比上次採用者大的回應」（也就是「最後回來者勝」的一種變形）。請用 R2 的時間線（A 先發、B 後發；B 先回 WORKING、A 後回 NOT_STARTED；之後定位成功）逐步說明這個改法會發生什麼事，並說明為什麼「發出時」取得序號才能保證不丟掉自己已取得的新狀態。

請把你的回答帶回 ChatGPT 對話，由它批改並決定下一題。
