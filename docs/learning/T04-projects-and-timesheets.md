# T04：最小專案管理與個人工時回報——技術文件

對應版本：基準 `93048f3`，T04 commit 見交付報告（標題 `feat: T04 ...`）。規格：`docs/renewflow/t04/`。
狀態：**已實作／作者自測完成／等待 ChatGPT 複審**（本文件只記錄作者自己的驗證）。

---

## 1. 三分鐘摘要

**用途**：管理員建立專案；每位使用者記錄自己每天在各專案做了什麼、花多少時間，並看到每日與期間合計。

**主要流程**：員工在「工時回報」輸入小時（例如 1.5）→ 前端換成整數分鐘（90）並帶上這次新增的 `requestId` → 後端在一個交易內「鎖住本人員工列 → 檢查是否重送 → 鎖住並檢查專案 → 加總當日 → 寫入」→ 畫面以後端資料重新查詢清單與合計。

**最重要的規則**
1. 資料一律是**整數分鐘**：15～1440、15 的倍數；畫面的「小時」只是換算，不經浮點數、不四捨五入。
2. **每日合計 ≤ 24 小時**：跨多列的規則，資料庫 CHECK 做不到；靠「同一人的寫入排隊」（員工列鎖）在交易內保證。
3. **重送不重複**：員工＋`requestId` 唯一；結果不明時沿用同一個 `requestId`，後端回「已建立」並附那筆的 ID。
4. **只能碰自己的資料**：所有查詢以 Session 的員工限定；別人的紀錄與不存在一樣回 404。MANAGER、ADMIN 也一樣。
5. **停用不刪除**：停用的專案不能再新增工時，舊紀錄照常顯示，維持原專案時仍可修改。
6. **版本**：專案與工時都用版本比對，過期的表單 409，不默默覆蓋。

## 2. 操作入口

| 誰 | 畫面 | 輸入 | 結果 |
|---|---|---|---|
| ADMIN | 左側「專案管理」（`#/admin/projects`） | 代碼、名稱、說明；編輯；停用／啟用 | 清單、確認視窗、版本衝突提示 |
| 所有使用者 | 左側「工時回報」（`#/timesheets`）、首頁「前往工時回報」 | 日期、專案、時數（小時）、工作內容；查詢範圍 | 新增、清單、期間合計、每日合計、修改 |

逐步操作見 README 第 11 節。

## 3. 實際程式碼定位

| 檔案 | 類別／方法（行） | 責任 |
|---|---|---|
| `backend/.../timesheet/TimesheetService.java` | `list`（49）、`create`（64）、`update`（86）、`lockEmployee`（116）、`lockActiveProject`（121）、`requireWithinDailyLimit`（130）、`requireValidMinutes`（139） | 本人工時的規則與交易 |
| `backend/.../timesheet/TimesheetEntryRepository.java` | `lockEmployee`（24）、`sumMinutesOfDay`（36）、`insertIfRequestNew`（50）、`updateIfVersion`（66） | 原生 SQL：鎖、加總、新增、版本更新 |
| `backend/.../timesheet/TimesheetController.java` | `list`、`get`、`create`、`update` | `/api/timesheets/me`（員工取自 Session） |
| `backend/.../timesheet/TimesheetViews.java`、`TimesheetRules.java`、`TimesheetDtos.java`、`TimesheetEntry.java` | — | DTO 轉換與每日合計、規則常數、DTO、Entity |
| `backend/.../project/ProjectService.java` | `create`（58）、`update`（73）、`listSelectable` | 專案規則（ADMIN） |
| `backend/.../project/ProjectRepository.java` | `insertIfCodeFree`（28）、`updateIfVersion`、`lockForTimesheet`（53） | 代碼唯一、版本更新、FOR SHARE |
| `backend/.../shared/web/ApiException.java`＋`GlobalExceptionHandler#onApiException` | — | `{code, message, …附加欄位}` |
| `backend/src/main/resources/db/migration/V5__projects_and_timesheets.sql` | — | 兩張表、限制、索引 |
| `backend/src/main/resources/application.yml` | `spring.jackson.deserialization.accept-float-as-int: false` | 小數不再被截斷成整數 |
| `frontend/src/lib/hours.ts` | `parseHours`（18）、`minutesToHoursText`、`formatMinutes` | 小時 ↔ 整數分鐘 |
| `frontend/src/lib/requestId.ts` | `newRequestId`（6） | UUID（安全來源外也可用） |
| `frontend/src/pages/TimesheetPage.tsx` | `TimesheetPage`、`EditEntry`（234）、`EntryFields`（279；`requestId` 283、`submit` 301） | 工時頁 |
| `frontend/src/pages/ProjectsAdminPage.tsx` | `CreateProjectForm`、`EditProjectDialog`、`ToggleProjectDialog` | 專案管理頁 |

## 4. 完整資料流（新增一筆工時）

```
[畫面] EntryFields.submit
  ├ submitting ref（防連按）；前端驗證：日期 ≤ 今天、已選專案、內容非空、parseHours("1.5") → 90
  └ POST /api/timesheets/me  { requestId, workDate, projectId, description, minutes: 90 }  ＋ X-XSRF-TOKEN
[Security] CsrfFilter → Session → ActiveAccountFilter（帳號仍有效、角色未變）→ /api/** authenticated
[Controller] TimesheetController.create：@Valid（必填、15～1440、UUID 格式；90.5 → 400）；employeeId ← Session
[Service] TimesheetService.create（@Transactional 開始）
  ├ 15 的倍數？內容去空白 1～1000？日期 ≤ 台北今天？
  ├ SELECT id FROM employee WHERE id = ? FOR NO KEY UPDATE           ← 同一人的寫入從這裡開始排隊
  ├ SELECT … WHERE employee_id = ? AND request_id = ?                 ← 已建立 → 409 DUPLICATE_SUBMISSION（entryId）
  ├ SELECT * FROM project WHERE id = ? FOR SHARE                       ← 不存在 404、停用 409
  ├ SELECT coalesce(sum(minutes), 0) … WHERE employee_id = ? AND work_date = ? AND id <> -1
  │     其他 + 90 > 1440 → 409 DAILY_LIMIT_EXCEEDED（附 otherMinutes 等）
  ├ INSERT … ON CONFLICT (employee_id, request_id) DO NOTHING RETURNING id
  └ SELECT 剛寫入的列 → TimesheetViews.entry → DTO
[提交] 釋放鎖 → 201
[畫面] onDone → 通知、表單換新 key（新的 requestId）、清單 refetch（期間與每日合計以後端為準）
```

## 5. 核心程式解說

### 5.1 Entity、DTO、關聯與外鍵

- `TimesheetEntry` 只用 `employeeId`、`projectId` 兩個 **ID 欄位**表示關聯，不用 `@ManyToOne`。資料庫層仍有真正的外鍵（`REFERENCES … ON DELETE RESTRICT`），所以「工時一定屬於存在的員工與專案」由資料庫保證；Java 端不需要延遲載入代理物件，也不會在序列化或迴圈裡觸發 N+1 查詢。顯示專案名稱時，`TimesheetViews.entries` 先收集所有 `projectId` 再 `findAllById` **一次**取回。
- Entity 標 `@Immutable`：只讀不寫。所有寫入都是 Repository 的原生條件式 SQL，避免「改了 Entity 欄位就被自動寫回」而繞過版本或所有權條件。
- DTO（record）只有使用者可以決定的欄位：`CreateEntryRequest` 沒有 `employeeId`、`version`、`createdAt`。前端多送這些欄位，Jackson 會忽略（測試 W-08 證明夾帶 `employeeId` 無效）。

### 5.2 整數分鐘 vs 浮點數

```ts
// frontend/src/lib/hours.ts
const hundredths = whole * 100 + Number(fraction)   // "1.5" → 150（百分之一小時）
if (hundredths % 25 !== 0) return { ok: false, … }   // 0.25 小時＝25：不是倍數就不合法
const minutes = (hundredths / 25) * 15              // 150 → 90
```
- 浮點數無法精確表示 0.1、0.2 這類十進位小數：`0.1 + 0.2 === 0.30000000000000004`。工時要加總、要和 1440 比較，累積誤差會讓「剛好 24 小時」變成「超過一點點」。整數分鐘沒有這個問題。
- 前端只用整數運算換算，`0.1` 小時（6 分鐘）直接拒絕，**不四捨五入**成 0（或 15）。
- 後端也不信任前端：`@Min(15) @Max(1440)` 擋範圍，`requireValidMinutes` 擋倍數，資料庫 `ck_timesheet_minutes` 是最後一道。
- **測試抓到的真實問題**：Jackson 預設會把 `"minutes": 90.5` 默默截斷成 90 存進去（W-03 第一次執行失敗）。改為全域 `accept-float-as-int: false` 後回 400。這正是「不把不合法值變成合法值」的反例。

### 5.3 單筆驗證 vs 跨紀錄的每日合計

- **單筆規則**（15～1440、15 倍數、內容、日期）只看這一筆，可以用 Bean Validation、Service 檢查，甚至資料庫 CHECK 保證。
- **每日合計**要看「同一人同一天的所有列」。CHECK 只能看單一列，唯一限制也表達不了「加總 ≤ 1440」。所以只能在交易內查詢加總再判斷——而「先查再寫」在並行時會出事（見 5.4）。
- 修改時：`sumMinutesOfDay(employeeId, 新日期, 這筆的 id)` 排除自己的舊值。同日只改內容不會把舊的分鐘數算兩次；換日期就是加總目標日期；同日換專案不影響合計（W-10）。

### 5.4 交易、資料列鎖、樂觀版本與並行

**問題（寫入偏差）**：當日已有 900 分鐘，同一人兩個分頁同時各送 300。若沒有鎖，兩個交易都讀到 900、都算出 900 + 300 = 1200 ≤ 1440、都寫入 → 實際 1500，超過上限。兩筆是不同的列，誰也沒有覆蓋誰，所以唯一限制與版本比對都察覺不到。

**做法**：
```java
@Query(value = "select id from employee where id = :employeeId for no key update", nativeQuery = true)
Optional<Long> lockEmployee(Long employeeId);
```
第二個交易在這一行等第一個提交，之後才讀合計，讀到的是 1200，1200 + 300 > 1440 → 拒絕。
- **只鎖這位員工**：不同員工互不阻擋（K-07）。
- **為什麼是 NO KEY UPDATE**：打卡等其他寫入新增「引用這位員工」的列時，外鍵檢查要 FOR KEY SHARE；`FOR UPDATE` 會擋住它，`FOR NO KEY UPDATE` 不會。
- **專案**：工時交易 `FOR SHARE` 鎖住專案並讀 `active`；停用是 `UPDATE`（需要更強的鎖）→ 兩者互斥：新增先拿到 → 停用等它提交、紀錄保留（K-06）；停用先拿到 → 新增等它提交、讀到已停用 → 409（K-05）。
- **鎖定順序固定**：員工 → 專案 → 紀錄；管理員只鎖專案、不碰員工列 → 不會循環等待（死鎖）。

**樂觀版本**（兩個分頁改同一筆）：`UPDATE … WHERE id = ? AND employee_id = ? AND version = ?`，0 列 → 409。它解決「覆蓋別人剛改的內容」，不需要長時間持鎖；但它**不能**保證每日合計（兩筆不同紀錄各自版本都對），所以兩種機制並存。

### 5.5 requestId 與重複提交

```java
entryRepository.findByEmployeeIdAndRequestId(employeeId, body.requestId())
        .ifPresent(existing -> { throw duplicate(existing.getId()); });
...
Long id = entryRepository.insertIfRequestNew(…)   // ON CONFLICT (employee_id, request_id) DO NOTHING RETURNING id
        .orElseThrow(() -> duplicate(…));
```
- **前端**：`const [requestId] = useState(newRequestId)`——表單建立時產生一次。只有三種情況換新：成功、後端回 `DUPLICATE_SUBMISSION`（代表先前那次已成功）、使用者按「清空，開始新的一筆」（外層換 key 重建表單）。網路中斷或 5xx 時**保留**，畫面提示「無法確認，已重新查詢」，再按送出沿用同一個。
- **後端**：員工列鎖已讓同一人的請求排隊，所以「先查是否已建立」就足夠；`ON CONFLICT` 是最後防線，而且**不會讓交易失效**（PostgreSQL 在約束錯誤後整個交易不能再查詢，所以不用「捕捉唯一限制例外」）。
- **為什麼不用「日期＋專案＋內容」判斷重複**：同一天兩次相同的會議是合法的兩筆。
- **為什麼不是修改**：重送的內容即使不同，也只回報已建立的那筆，不覆蓋（W-12）。

### 5.6 角色權限與資料所有權

- **角色**回答「能做哪種操作」：只有 ADMIN 能管理專案（URL 規則＋`ProjectService.requireAdmin`＋`ActiveAccountFilter` 確認角色是最新的）。
- **資料範圍**回答「能操作誰的資料」：工時 API 一律以 Session 的員工為範圍。`findByIdAndEmployeeId`、`UPDATE … AND employee_id = ?`。別人的紀錄與不存在都回 404 `TIMESHEET_NOT_FOUND`，避免藉由 403／404 的差異探測別人的紀錄。
- MANAGER 名稱聽起來像主管，但本階段沒有「主管看部屬工時」的需求，所以沒有任何特權（W-08、E2E W9）。

### 5.7 停用 vs 刪除

| | 停用（本階段） | 刪除（不提供） |
|---|---|---|
| 專案列 | 保留，`active = false`，版本 +1 | 消失 |
| 既有工時 | 保留、可查、可依規則修改 | 會失去專案意義；外鍵 RESTRICT 也會擋下 |
| 新增工時 | 拒絕（409） | — |
| 可回復 | 重新啟用即可 | 不可 |

修改既有紀錄時，「維持原專案」走 `lockForTimesheet` 但不檢查 `active`；「換到別的專案」走 `lockActiveProject`。前端修改表單會把原本的停用專案加進下拉選單並標示「（已停用）」，避免因為選單只載入啟用專案而把值清空。

## 6. 設計理由與取捨

| 主題 | 選擇 | 替代與成本 |
|---|---|---|
| 每日上限 | 員工列鎖＋交易內加總 | SERIALIZABLE：要處理重試；全域鎖：所有人排隊；只靠前端：可被繞過 |
| 停用 vs 新增 | 專案列 FOR SHARE | 不鎖：讀到啟用後才被停用，先後不明 |
| 重複新增 | requestId＋唯一限制＋ON CONFLICT | 內容比對：誤判合法紀錄；捕捉例外：交易失效 |
| 關聯 | 外鍵 ID＋批次查詢 | `@ManyToOne`：延遲載入、N+1、序列化問題 |
| 錯誤格式 | 新增 `ApiException`（可附欄位），既有例外不動 | 改寫所有例外：範圍過大 |
| 沒有變更 | 400，不遞增版本 | 照常更新：其他分頁無故衝突 |

**學習假設**：沒有簽核、沒有專案成員、沒有關帳、24 小時只是資料合理性上限——都不是正式制度。

## 7. 資料模型與 SQL

見 `docs/renewflow/t04/04-data-model.md`。程式中的原生 SQL 即實際執行的 SQL；`findByEmployeeIdAndWorkDateBetweenOrderByWorkDateDescIdDesc` 由 Spring Data 產生，**推估**為：
```sql
select … from timesheet_entry where employee_id = ? and work_date between ? and ? order by work_date desc, id desc
```
（未擷取實際 SQL 日誌；對應索引 `ix_timesheet_employee_date (employee_id, work_date, id)`。）

## 8. 安全與錯誤

| 情況 | 結果 |
|---|---|
| 未登入 | 401 |
| 缺或偽造 CSRF | 403 |
| 停用、刪除或角色被改的帳號（舊 Session） | 401 |
| EMPLOYEE／MANAGER 呼叫專案管理 API | 403 |
| 他人或不存在的工時 | 404 `TIMESHEET_NOT_FOUND` |
| 格式、範圍、小數分鐘 | 400 `VALIDATION_FAILED`／`BAD_REQUEST` |
| 非 15 倍數、未來日期、內容過長、沒有變更 | 400 `INVALID_TIMESHEET` |
| 專案不存在／停用 | 404 `PROJECT_NOT_FOUND`／409 `PROJECT_INACTIVE` |
| 超過每日上限 | 409 `DAILY_LIMIT_EXCEEDED`（附分鐘數） |
| 重複提交 | 409 `DUPLICATE_SUBMISSION`（附本人紀錄 ID） |
| 版本不符 | 409 `TIMESHEET_CHANGED`／`PROJECT_CHANGED` |
| 代碼重複 | 409 `PROJECT_CODE_DUPLICATE` |
| 資料庫或內部故障 | 500 `INTERNAL_ERROR`（不偽裝成上限、重複或 401；K-08） |

前端：只有 401 走登入狀態處理；結果不明時重新查詢、不自動重送；409 不以新版本自動重送。

## 9. 驗證證據

**本次實際執行**

| 指令 | 結果 |
|---|---|
| `cd backend; .\mvnw.cmd test` | 239 個，0 失敗（新增 30：`ProjectIntegrationTest` 6、`TimesheetIntegrationTest` 14、`TimesheetConcurrencyIntegrationTest` 8、`TimesheetSchemaTest` 2） |
| `npm run build`、`npm run lint` | 通過 |
| `npm run e2e:timesheets`（隔離） | 49/49 |
| `e2e:races`、`e2e:geofence`、`e2e:attendance`、`e2e:corrections`（隔離） | 22/22、59/59、37/37、74/74 |
| `e2e:smoke`、`e2e:logout`、`e2e:password`（dev，唯讀） | 18/18、23/23、10/10 |
| 變異驗證（每次只改一處，跑對應測試，再還原） | 拿掉員工列鎖 → K-01、K-02 失敗（並行下合計超過上限）；拿掉版本比對 → W-11、K-04 失敗；拿掉所有權條件 → W-08 失敗；拿掉 requestId 防重複 → W-12、K-03 失敗。還原後全部 239 通過 |
| 開發庫套用 V5 | 員工、出勤、申請、歷程、定位設定與歷程、打卡事件的逐列雜湊前後相同；Flyway 4 → 5；專案與工時 0 筆 |

**測試過程中發現並修正的問題**
- `minutes: 90.5` 被 Jackson 截斷成 90 → 全域關閉 float→int。
- 送出後才發現專案已停用時，前端重新載入專案清單的方式會讓表單暫時卸載、錯誤訊息消失 → 改為背景 `refetch`，並同時刷新紀錄清單。

**沿用的證據**：T03.x 的行為（本次重跑的既有測試與 E2E 仍通過）。

**未驗證**：手機實機、正式環境的鎖等待與效能、大量資料下的查詢效能。

## 10. 面試重點

**Q1. 為什麼工時存整數分鐘而不是小數小時？** 浮點數不能精確表示十進位小數，加總與比較會出錯；整數分鐘精確、好驗證倍數。畫面輸入的小時只在前端以整數運算換算。

**Q2. 每日 24 小時上限為什麼不能只靠資料庫限制？** CHECK 只看單列，唯一限制表達不了加總。必須在交易內加總，而且要避免兩個交易同時「讀舊合計」——用員工列鎖讓同一人的寫入排隊。追問：為什麼不用 SERIALIZABLE？——可行，但衝突時要捕捉序列化失敗並重試，程式與測試都更複雜。

**Q3. 樂觀鎖（版本）和悲觀鎖（列鎖）在這裡各解決什麼？** 版本解決「同一筆被兩個分頁覆蓋」；列鎖解決「不同筆加總超過上限」。只有版本擋不住後者。

**Q4. 重送怎麼不重複建立？** 前端每次邏輯新增一個 requestId，結果不明時沿用；後端員工＋requestId 唯一，`ON CONFLICT DO NOTHING` 不讓交易失效；重複時回報已建立的本人紀錄 ID。

**Q5. 為什麼他人的紀錄回 404 而不是 403？** 403 等於承認「這筆存在」，可被用來探測。

**Q6. 停用與刪除差在哪？** 停用保留資料與關聯、可回復，只擋新增；刪除會破壞既有工時的意義，外鍵 RESTRICT 也會擋。

**Q7. 鎖的順序為什麼重要？** 兩個交易若以相反順序取得兩把鎖，可能互等形成死鎖；所有寫入都採「員工 → 專案 → 紀錄」，管理員只鎖專案。

**Q8. 測試如何證明並行安全？** 用閘門讓多個真實 HTTP 請求同時放行打真實資料庫，斷言不變條件（恰 4 筆成功、合計 1200）；再把鎖拿掉跑一次，確認測試會失敗。

## 練習（只有這一題）

**背景**：`TimesheetService.update` 會先 `lockEmployee`，再讀取紀錄、比對版本、檢查專案，最後以 `sumMinutesOfDay(employeeId, 新日期, 這筆的 id)` 排除自己的舊分鐘數後檢查上限。

**問題**：假設有人為了「讓修改更快」，把 `update` 裡的 `lockEmployee(employeeId)` 拿掉，理由是「修改有版本比對，已經是並行安全的」。請設計一個具體情境（同一位員工、兩個分頁、各自修改**不同**的兩筆紀錄，寫出每一步的時間順序與分鐘數），說明為什麼版本比對擋不住、最後當日合計會超過 1440；並指出現有哪一個測試會因此失敗。

請把你的回答帶回 ChatGPT 對話，由它批改並決定下一題。
