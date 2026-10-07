# T03.3 R1：重新查資料時，保留使用者指定的目標

基準：`d3d2d52761f9cb8e958828022d6b2f67d633ef5f`。本輪由 Codex 修正，等待 ChatGPT 複審；逐案結果見 [review-round-1](../renewflow/t03-3/review-round-1.md)。

## 問題原因與資料流

AttendancePage 每列「申請更正」傳 initialDate 與 initialRecordId；CorrectionForm 再查這一天的最新紀錄。原本 `find(initialRecordId) ?? (records.length === 1 ? records[0] : null)` 在指定組已作廢時採用唯一剩餘組。原指定 #11 失效，剩 #22，就把申請目標改為 #22；全部失效時則自動改為補登。

重新取得最新資料，能避免沿用過期時間與版本；保留指定 ID，才能維持使用者選的是哪一組。兩者必須同時成立。查不到指定組是需要使用者處理的狀態，不能用另一筆的最新資料代替。

資料流仍是 AttendancePage → CorrectionForm → submitRequest → 後端 RequestService。後端看到 #22 與它正確的版本，就會接受合法申請；它沒有收到「原本想改 #11」這個意圖，因此版本檢查無法防止前端選錯另一筆有效紀錄。

## 修改位置與關鍵程式

實作只有 `frontend/src/components/CorrectionForm.tsx`。AttendancePage 已正確傳目標，不需修改；API、後端、migration、時間工具與共用 HTTP 沿用。

```ts
type Target =
  | { kind: 'choose'; invalidId?: number }
  | { kind: 'new' }
  | { kind: 'record'; record: AttendanceRecord }
```

ready 狀態同時保存有效清單與 Target；選擇與送出快照不再分成兩份 state。record 保存所選 ID、版本、時間；new 表示明確補登；choose 表示意圖尚未確認，不能提交。

```ts
const record = initialRecordId !== undefined
  ? (!dateChanged ? records.find(r => r.id === initialRecordId) : null)
  : (records.length === 1 ? records[0] : null)
```

指定入口只找同 ID；查不到就用 choose 並保存 invalidId 作提示。通用入口才保留「一組預填、無組補登、多組待選」。按鈕 disabled 與 handleSubmit 都檢查 choose；程式直接觸發 submit 也不會送 POST。

```ts
if (original.target.kind === 'choose')
  return setError('請明確選擇要更正的時段，或選擇補登新時段。')
const record = original.target.kind === 'record' ? original.target.record : null
```

明選另一組時更新目標與原／申請時間，再送該 ID＋版本；明選補登清空時間，送兩個 null，維持原契約。有其他有效組時，提示「另有有效出勤，補登缺少時段」，避免誤稱當日沒有紀錄。

指定入口一旦換日期，立即清空時間／快照，之後即使切回原日期也待選。active cleanup 保證舊查詢晚到不能覆蓋新日期。通用入口換日仍保留既有正常預設。未改預填時間繼續使用 submittedTime 保留微秒；快照取得後再被更正或作廢，仍由後端 409 RECORD_CHANGED 拒絕。

## 測試證據與取捨

同一支 `attendance-target-review.mjs` 在原基準 **21／29**，修補後 **29／29**；腳本 SHA-256 相同，涵蓋 R1-01～R1-08。檢查選取、POST 本文、真隔離資料庫的 target ID／版本，以及出勤／事件／申請／歷程的副作用。未重新選擇的案例強制 submit，確認無 POST、沒有新增資料；原版則實際建立了申請。

`npm run build`、`npm run lint`、`npm run e2e:attendance-target-review` 與多組 17／17、更正審核 74／74、基本出勤 37／37 都通過。使用既有隔離堆疊、專用帳號與 PostgreSQL，不安裝新框架。舊清單與 GET 晚到使用受控回應；表單查詢、POST 與資料庫比較是真的。它不是真人操作或網路設備故障驗證。詳細命令／log 見複審文件。

日期切換後要求指定入口重新選擇，多一個選擇步驟，但使用者可以看到並確認新目標。沒有加入共用選擇框架；局部 union 足以表示這個表單的狀態。開發庫仍 V7、新版服務尚未載入；真人驗收前需由使用者依 README 啟動新版並套 V8，本輪不操作開發資料。

## 面試重點

- 最新資料與正確目標是不同的不變條件；重新查詢不代表可以換身份。
- ID 定義作用對象，version 防止過期寫入；兩者都合法仍可能是錯誤的使用者意圖。
- 用 union 區分待選、補登與紀錄，避免 record=null 同時代表多種意義。
- UI 停用是操作提示，提交處理的 guard 與後端版本／交易檢查才補齊防線。

練習：指定 #11 後切到另一日期，該日期恰好只有 #33；請說明表單應保留哪些狀態，並設計一個檢查 POST 本文的案例。
