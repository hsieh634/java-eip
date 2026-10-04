# T04 第 1 輪複審修正（R1、R2）與 STMAX 示範初始化——學習說明

審查基準：`3b1e796`；本批 commit 見交付報告（標題 `fix: T04 review round 1 ...`）。規格：`docs/renewflow/t04/review-round-1.md`、`docs/renewflow/demo-stmax.md`。
狀態：**已實作／作者自測完成／等待 ChatGPT 複審**。

---

## 1. 三分鐘摘要

**R1**：新增工時時回應遺失，畫面說「結果不明」，但欄位還能改。使用者改成 120 分鐘再送——用的是同一個 requestId（正確）但**內容不同**。後端說「這個 requestId 已建立」（指向 90 分鐘那筆），前端卻當成完成並清空表單，120 分鐘就這樣不見了。
**修正**：requestId 與「當時送出的那份內容」綁在一起。送出中與結果不明時鎖住表單；重試只重送原內容；確認建立後要改內容，走正常的「修改」流程；明確被拒（上限、停用、欄位錯誤）才解鎖讓使用者修正。

**R2**：送出時發現專案已停用 → 背景重新取得專案清單 → 這次又網路錯誤 → 專案狀態變成 error → 表單因為「沒有專案清單」被卸載，草稿、錯誤訊息、requestId 全部消失。
**修正**：工時頁自己管理專案清單，區分「初次載入失敗」（沒有表單可顯示）與「背景更新失敗」（保留上一份清單與表單，另外顯示更新錯誤與重試）。選中的專案不在新清單時照實標示，不偷偷改選。

**STMAX**：虛構的示範公司，dev 限定、預設關閉、沒有預設密碼的初始化，建立六個示範帳號；重跑不改任何既有帳號，碰撞就整批停止。

**證明**：新的 `timesheets-review.mjs` 在修正前 8 項失敗、修正後 24/24；示範初始化有 7 個後端測試；開發庫建立前後只有六個新帳號的差異。

## 2. 真實修改的檔案與位置

| 檔案 | 位置（行） | 內容 |
|---|---|---|
| `frontend/src/pages/TimesheetPage.tsx` | `useSelectableProjects`（31）、`load`（47）、`reload`（75）、更新錯誤橫幅（156） | R2：頁內專案清單載入 |
| 同上 | `Phase`（363）、`EntryFields`（372）、`requestId`（377）、`pending`（388）、`locked`（392）、`selectedUnavailable`（400）、`edit`（407）、`send`（411）、`DUPLICATE_SUBMISSION` 處理（425）、`submit`（463）、放棄警告（533） | R1：提交快照、鎖定、重送原內容 |
| `frontend/src/styles.css` | `.form-fieldset` | 鎖定時整組欄位停用的樣式 |
| `frontend/e2e/timesheets-review.mjs` | R1-1～R1-5、R2 | 回歸測試（受控回應） |
| `frontend/e2e/timesheets.mjs` | W2、W5 | 期望隨新規則更新 |
| `backend/.../identity/StmaxDemoSeedRunner.java` | `run`（76）、`seed`（87） | 示範初始化 |
| `backend/.../identity/EmployeeRepository.java` | `findByEmployeeNo` | 員工編號碰撞檢查 |
| `backend/src/main/resources/application.yml` | `renewflow.demo.stmax.*` | 開關與密碼（預設關閉、無密碼） |
| `backend/src/test/.../identity/StmaxDemoSeedRunnerTest.java` | D-01～D-07 | 初始化測試 |
| `.env.example` | `RENEWFLOW_DEMO_STMAX_*` | 只有占位值 |

## 3. requestId 與提交內容的關係

requestId 回答的是「**這一次邏輯上的新增**是不是已經做過」。後端的去重規則是「同一個人、同一個 requestId 只會建立一筆」——它**不比較內容**，也不應該比較（同樣內容的兩筆工作是合法的）。

所以前端必須保證：**同一個 requestId 只會搭配同一份內容送出**。否則就會發生 R1：後端說「已經有了」，指的是舊內容，前端卻以為新內容也處理完了。

```ts
const [requestId] = useState(newRequestId)          // 表單建立時產生一次
const [pending, setPending] = useState<Submission | null>(null)  // 結果不明時「已送出的那一份」
...
if (phase === 'unknown' && pending) {
  await send(pending)                                 // 只重送原內容
  return
}
```

什麼時候換新的 requestId（外層換 key 重建表單）：成功、收到 `DUPLICATE_SUBMISSION`（原提交已存在）、使用者確認「放棄追蹤，開始新的一筆」。明確拒絕**不換**——那個識別碼底下沒有任何紀錄，修正後沿用是安全的。

## 4. 結果不明 vs 明確失敗

| | 結果不明 | 明確失敗 |
|---|---|---|
| 來源 | 網路中斷、回應遺失、5xx | 400、404、409（上限、停用、版本） |
| 伺服器做了嗎？ | **不知道**（可能已寫入） | **確定沒有**建立或修改 |
| 表單 | 鎖在原內容 | 解鎖，可修正 |
| 下一步 | 重新送出原內容確認（同 requestId）；或明確放棄（先警告可能已建立） | 修正後再送 |
| 不能做 | 改內容後用同 requestId 送；每次換新 requestId 自動重送 | — |

這是 T03 以來「結果不明時重新查詢、不自動重送」原則的延伸：不明時不能讓使用者以為新內容已存，也不能偷偷建立第二筆。

## 5. React 卸載、key 與草稿遺失

- 表單的狀態（日期、時數、內容、requestId）存在 `EntryFields` 這個元件實例裡。**元件被卸載，這些 state 就消失**；之後即使「同一個位置」再渲染一次，也是全新的實例（新的 `useState(newRequestId)`）。
- 修正前：`{selectable && <EntryFields key=… />}`，而 `selectable` 來自 `useLoad` 的 `state.kind === 'ready'`。背景更新失敗 → `kind` 變成 `error` → `selectable` 變成 null → 條件為假 → 卸載。
- **key** 是我們刻意用來重建表單的工具（成功後換 `new:${seq}` 讓表單清空、換新 requestId）。問題不是 key 本身，而是「非刻意」的卸載：條件渲染依賴了一個會因背景錯誤而變成 null 的值。
- 修正後：`selectable` 是「最後一次成功的清單」，背景錯誤不會把它清成 null；錯誤另外存一個欄位顯示。

## 6. 初次載入 vs 背景更新

```ts
const load = useCallback((): Promise<void> => {
  const mine = ++seq.current
  const life = lifetime.current
  const isLatest = () => life.alive && mine === seq.current
  return fetchSelectableProjects().then(
    (data) => { if (!isLatest()) return; setProjects(data); setError(null); setRefreshing(false) },
    (e)    => { if (!isLatest()) return; setRefreshing(false); /* 401 → 登入流程 */ setError(訊息) },
  )
}, [refresh])
```

- 錯誤**不清除** `projects`：初次載入時 `projects` 本來就是 null（顯示錯誤與重試）；背景更新時保留舊清單（顯示「專案清單更新失敗」與重試）。
- 序號 `seq`：只採用最後發出的請求（T03.2 R2 的同一個原則），舊回應晚到不覆蓋新清單（測試 R2 最後一段）。
- 為什麼不改共用的 `useLoad`：它被出勤、管理、定位設定等頁面使用，它「錯誤時顯示錯誤」的行為在那些頁面是正確的；只為這一頁改全站行為風險太大。局部用一個小 hook 解決。
- 為什麼掛載有效期間寫在 hook 內而不是用 `useLifetime()`：React Compiler 無法從自訂 hook 的回傳值看出它是 ref，`useCallback` 讀 `.current` 會被判定為依賴不一致；寫成同一個 hook 內的 `useRef` 就沒有這個問題。

## 7. dev seed 的冪等、安全與環境隔離

| 要求 | 做法 |
|---|---|
| 環境隔離 | `@Profile("dev")`：其他 profile 根本沒有這個元件（測試 D-01 檢查 bean 不存在） |
| 預設關閉 | `renewflow.demo.stmax.enabled` 預設 `false`；必須明確設定 |
| 沒有預設密碼 | 密碼空白就不建立（D-02）；只以 PasswordEncoder 雜湊；不寫進日誌 |
| 冪等 | 帳號與員工編號都相同 → 視為先前建立的，完全不動（不重設密碼、不改角色或啟用狀態；D-04） |
| 碰撞時停止 | 帳號或員工編號被別人用了 → 一個都不建立，也不接管（D-05、D-06） |
| 全有或全無 | 缺少的帳號在同一個交易內建立 |
| 部分存在 | 只補建缺少的（D-07） |

對照既有的 `DevSeedRunner`：它有一個「重設密碼」旗標（明確要求才會改密碼）。STMAX 初始化刻意**沒有**這個功能，因為需求明確要求重跑不得重設既有帳號的密碼。

## 8. 測試如何證明修正有效

1. **先寫測試、不改程式**，在修正前的版本執行 `timesheets-review.mjs`：20 項中 8 項失敗。其中 R1-1 記錄到同一 requestId 先後送出 `90 分／原內容` 與 `120 分／新內容`；R2 記錄到表單被清成空白。
2. 修正後同一支測試 24/24（多出的項目是修正後才存在的按鈕流程，例如放棄警告、重試更新）。
3. 事件順序用**受控回應**安排：回應遺失（請求轉送到伺服器後讓瀏覽器收到網路錯誤）、扣住請求再放行、偽造較舊的專案清單、讓背景更新中斷。「不應該發生」的斷言只在事件確定送達後短暫等待。
4. 既有 `timesheets.mjs`（49 項）與其他 E2E 全部重跑。
5. 後端 `StmaxDemoSeedRunnerTest` 7 項；開發庫實際建立前後比對，只有六個新帳號。

## 9. 面試重點

**Q1. 冪等鍵（idempotency key）只保證什麼？** 只保證「同一個鍵不會被執行兩次」，不保證「內容相同」。用戶端要確保同一個鍵只搭配同一份請求內容。

**Q2. 為什麼結果不明時不能讓使用者改內容？** 改了再用同一個鍵送，若原請求已成功，伺服器會回「已存在」，新內容其實沒有被保存，卻容易被當成成功。

**Q3. 為什麼明確失敗後可以沿用同一個 requestId？** 明確失敗代表伺服器沒有以這個鍵建立任何東西，鍵仍是「未使用」，修正後再送不會造成誤判。

**Q4. React 的草稿為什麼會消失？** 狀態存在元件實例裡，條件渲染讓元件卸載就會遺失。背景錯誤不應改變「要不要渲染表單」的條件。

**Q5. 初次載入失敗與背景更新失敗為什麼要分開？** 前者沒有資料可顯示；後者已有可用資料，錯誤不該讓使用者失去畫面上的工作。

**Q6. 開發用種子資料要注意什麼？** 環境隔離、預設關閉、不寫死密碼、冪等、不覆寫既有資料、碰撞時停止、結果寫日誌但不寫密碼。

## 練習（只有這一題）

**背景**：修正後，新增結果不明時表單鎖在原內容，只能「重新送出原內容」或「放棄追蹤」；明確拒絕時解鎖並沿用同一個 requestId。

**問題**：假設使用者在結果不明時按了「重新送出原內容」，這一次後端回 **409 `DAILY_LIMIT_EXCEEDED`**（每日上限）。請推理：在目前後端的檢查順序下，這個回應能不能代表「原提交其實沒有建立」？如果能，請說明是哪一個檢查順序保證了這件事；如果後端把「requestId 是否已建立」的檢查挪到「每日上限檢查」之後，前端的這個判斷會出什麼問題？

請把你的回答帶回 ChatGPT 對話，由它批改並決定下一題。
