# T05 前置：最小組織與員工管理——學習文件

> **這是 T05「請假送出與主管審核」的前置子任務**，T01～T06 編號不變，本批沒有請假功能。
> 規格在 [`docs/renewflow/t05-foundation/`](../renewflow/t05-foundation/)。

## 1. 三分鐘看懂

- **做了什麼**：ADMIN 在「組織與員工」頁管理部門（建立、改名、停用、重新啟用）和員工（建立、改名、改角色、停用／啟用、設定部門與直屬主管），每次變更都留下只能新增的歷程。
- **最重要的設計**：組織規則跨越多筆資料（例如「A 的主管是 B」和「B 的主管是 A」分別改的是兩筆），**版本號擋不住**。所以每個組織寫入交易一開始都先取得同一把 PostgreSQL 交易級 advisory lock，讓這些寫入一個接一個地「讀取、檢查、寫入」。
- **守住的不變條件**：
  1. 至少一位啟用中的 ADMIN。
  2. 主管不循環。
  3. 主管一定是啟用中的 MANAGER 或 ADMIN。
  4. 停用的部門沒有啟用中的員工。
  5. 代碼、員工編號、帳號唯一。
  6. 歷程與修改同成同敗。
- **刻意沒做**：
  - 主管關係**不授與任何資料權限**，Iris 仍看不到 Leo 的出勤。
  - 沒有部門樹、多主管、刪除、密碼修改、邀請信。
- **資料安全**：V6 只新增欄位與表。開發庫升級前後，舊欄位逐列比對相同；STMAX 的部門與主管由 dev 專用、另一個開關的初始化程式補上，來源標示為「系統」，不是 Max。

## 2. 用到的技術

| 技術 | 用在哪裡 |
|---|---|
| PostgreSQL `pg_advisory_xact_lock` | `OrganizationLock`：交易級鎖，提交或回滾時自動釋放 |
| 自關聯外鍵（self-referencing FK） | `employee.manager_id → employee.id` |
| CHECK 限制 | 不可自己當主管、部門代碼格式、歷程來源與操作者一致 |
| `INSERT … ON CONFLICT DO NOTHING RETURNING id` | 唯一衝突時不讓交易壞掉，回傳空，再決定是哪一種重複 |
| 版本條件 UPDATE | `where id = ? and version = ?`，回傳 0＝過期表單 |
| 觸發器 | 歷程只新增（沿用 V4 的 `reject_append_only_change()`） |
| Spring `@Transactional`、`Propagation.MANDATORY` | 鎖只能在交易裡取 |
| Spring Data `Page`／`PageRequest` | 員工清單分頁與總數 |
| Jackson 3 `ObjectMapper` | 歷程的前後值存成 JSON 文字、讀回時解析 |
| `@MockitoSpyBean` | 讓歷程寫入失敗，驗證整筆回滾 |
| React（不新增套件） | 分頁、對話框、變更摘要、衝突時保留輸入 |

## 3. 實際檔案與方法

| 檔案 | 重點方法 |
|---|---|
| `backend/src/main/resources/db/migration/V6__organization_foundation.sql` | 新表 `department`、`organization_history`；`employee` 新欄位與 CHECK |
| `organization/OrganizationLock.java` | `acquire()` |
| `organization/EmployeeAdminService.java` | `create`、`update`、`requireValidManager`、`rejectCycle`、`search` |
| `organization/DepartmentService.java` | `create`、`update`（停用時檢查啟用員工數） |
| `organization/EmployeeAdminRepository.java` | `insertIfUnique`、`updateIfVersion`、`countSubordinates`、`countOtherActiveAdmins`、`search` |
| `organization/DepartmentRepository.java` | `insertIfCodeFree`、`updateIfVersion`、`countActiveEmployees` |
| `organization/OrganizationAuditLog.java` | `employee(...)`、`department(...)`：歷程唯一入口 |
| `organization/StmaxOrganizationSeedRunner.java` | `seed()`：核對 → 部門 → 逐人補欄位 |
| `identity/Employee.java` | 新增唯讀的 `departmentId`、`managerId`、`version` |
| `frontend/src/organization/EmployeeDialogs.tsx` | `CreateEmployeeDialog`、`EditEmployeeDialog`（變更摘要、`loadLatest`） |

## 4. 關鍵程式片段

**4.1 鎖：一行 SQL、範圍明確**
```java
@Transactional(propagation = Propagation.MANDATORY)   // 沒有交易就失敗：交易級鎖要「持有到交易結束」
public void acquire() {
    jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, KEY);
}
```

**4.2 修改員工：先鎖、再讀最新、再檢查**
```java
lock.acquire();
Employee current = employeeRepository.findById(id).orElseThrow(...);
if (current.getVersion() != body.expectedVersion()) throw changed();     // 過期表單
...
if (wasActiveAdmin && !staysActiveAdmin && employeeRepository.countOtherActiveAdmins(id) == 0) {
    throw conflict("LAST_ADMIN", ...);                                    // 鎖內計算，並行也正確
}
employeeRepository.updateIfVersion(...);                                  // version + 1
auditLog.employee(id, "EMPLOYEE_UPDATED", reason, changes, actor.getEmployeeId(), Source.ADMIN, now);
```

**4.3 循環檢查：從新主管往上走**
```java
Employee cursor = proposedManager;
while (cursor != null && visited.add(cursor.getId())) {
    if (cursor.getId().equals(employeeId)) throw conflict("MANAGER_CYCLE", ...);
    cursor = cursor.getManagerId() == null ? null : employeeRepository.findById(cursor.getManagerId()).orElse(null);
}
```
要設定「E 的主管是 M」，就從 M 開始沿著「M 的主管、M 的主管的主管……」往上走。如果會走回 E，代表 E 已經在 M 的上方，形成循環。

**4.4 JPA 唯讀欄位：避免被別的 save 覆寫**
```java
@Column(name = "manager_id", insertable = false, updatable = false)
private Long managerId;
```
開發帳號維護流程會 `save(employee)`，例如重設密碼。如果這個欄位可寫，就可能用「讀取時的舊值」蓋掉剛被管理員改的主管。設成唯讀後，只有組織管理的條件式 SQL 會寫入它。

## 5. 角色 vs 資料範圍；部門 vs 主管

| 概念 | 回答的問題 | 本批 |
|---|---|---|
| 角色（EMPLOYEE／MANAGER／ADMIN） | 能做**哪一類**操作 | 沿用；`/api/admin/**` 只有 ADMIN |
| 資料範圍 | 能操作**誰的**資料 | **不變**：員工只看自己的出勤與工時；主管關係不授權 |
| 部門 | 員工**屬於**哪個單位 | 平面清單，每人最多一個 |
| 直屬主管 | 員工的**匯報對象**是誰 | 每人最多一位，可跨部門 |

部門和主管是兩種獨立的關係：Zoe 屬於「行政營運」，主管是「經營管理」的 Max。T05 會用「直屬主管」決定請假送給誰審核，那時才會設計對應的資料範圍。

## 6. 交易與並行：為什麼版本號不夠

版本號保證「我改的**這一筆**還是我看到的那一版」。下面的情境各自修改的是**不同**的列，版本都對，合起來卻違反規則：

| 同時發生 | 沒有鎖的結果（變異驗證實測） |
|---|---|
| A 的主管設為 B、B 的主管設為 A | 兩個都成功，形成循環 |
| 兩位 ADMIN 互相降級 | 兩個都成功，沒有 ADMIN |
| 停用部門、把員工指派到該部門 | 停用部門裡有啟用員工 |
| 停用主管、把某人的主管設為他 | 主管已停用 |

鎖讓第二個交易**等第一個提交之後**才開始讀取。PostgreSQL 預設的 READ COMMITTED 隔離層級下，取得鎖之後的查詢會看到第一個交易的結果，所以第二個的檢查一定基於最新狀態。

鎖的設計重點：
- **範圍**：只有組織寫入取這把鎖，登入、打卡、工時、查詢都不取。
- **順序**：只有一把，所以不會因加鎖順序不同而死結。
- **釋放**：交易結束時自動釋放。

## 7. Migration 與歷程

- **V6 只新增**：`department` 與 `organization_history` 兩張表，以及 `employee` 的三個欄位（預設 NULL／NULL／0）。V1～V5 不修改，也不寫入任何部門或員工資料。
- **自關聯外鍵** `ON DELETE RESTRICT`：被引用為主管的員工不能被刪除（本來就沒有刪除 API）。
- **歷程**：
  - `changes` 欄位存 JSON 陣列，例如 `[{"field":"manager","before":{"id":1,"label":"STX-001 Max"},"after":{...}}]`。
  - 歷程與修改在同一個交易：Spy 讓歷程寫入失敗時，修改一起回滾（OA-01～OA-03）。
  - 觸發器讓 UPDATE／DELETE 失敗（O-16）。
  - `source` 區分 `ADMIN` 與 `SYSTEM_DEMO_SEED`，CHECK 規定 ADMIN 必須有操作者、系統來源不能掛操作者。

## 8. 測試證據

### 8.1 本次執行

| 項目 | 結果 |
|---|---|
| 後端全部測試 `mvnw test` | **282／282 通過**（246 → 282，新增 36）。第一次全套執行時，`E2eStackGuardTest` 出現 1 個錯誤：`PSQL FATAL: sorry, too many clients already`。原因是新增的測試 Context 讓常駐連線超過 `max_connections`（100）。調整測試連線池常駐數（D-69）後重跑全部通過 |
| 新增後端測試 | `OrganizationIntegrationTest` 17、`OrganizationConcurrencyIntegrationTest` 5（各 8 輪）、`OrganizationAuditRollbackIntegrationTest` 3、`OrganizationSchemaTest` 2、`StmaxOrganizationSeedRunnerTest` 9 |
| 變異驗證（改壞→跑→還原） | 移除組織鎖 → CC-01～CC-05 **5／5 失敗**（都在第 0 輪）；移除循環檢查 → O-10 失敗；移除最後 ADMIN 檢查 → O-09 失敗；移除 Service 的 ADMIN 檢查 → O-01b 失敗；移除部屬檢查 → O-11 失敗。還原後確認原始程式碼回到正確狀態，並重跑全套 |
| E2E `organization.mjs`（隔離堆疊） | **54／54 通過** |
| E2E 回歸（本次全部重跑） | smoke 18、logout 23、password 10、attendance 37、corrections 74、geofence 59、races 22、timesheets 49、timesheets-review 24、timesheets-retry 25、organization 54，全部通過 |
| 前端 build、lint | 通過（`tsc -b` 與 `vite build` 成功；oxlint 結束碼 0、沒有任何警告輸出） |
| 開發庫升級前後比對 | 見 8.3 |
| 開發環境 Max 唯讀檢查 | Max 看到 3 個部門、6 位 STMAX 員工；Leo 為 PROJECT 專案服務、主管 Iris 林怡安；Leo 的歷程操作者為「系統（開發環境示範初始化）」；全程沒有寫入請求（6／6） |

### 8.2 沿用的證據

- `ActiveAccountFilter` 的「停用／改角色後舊 Session 失效」是 T03 的既有機制。本批沒有修改它，只用 O-13 與 E2E G5 再驗證一次。

### 8.3 開發庫

開發庫的指紋使用 md5 逐列雜湊；`employee` 只比對舊欄位投影。

| 項目 | 升級前 | 升級後 |
|---|---|---|
| Flyway | 5 | 6 |
| 非 STMAX 員工（V5 欄位） | `ec5b84b1…` | 相同 |
| STMAX 員工（V5 欄位，不含 `updated_at`） | `da0d6169…` | 相同（ID、帳號、密碼雜湊、角色、啟用都沒變） |
| 全部員工（V5 欄位，含 `updated_at`） | `90081dbd…` | 不同：預期中，六個 STMAX 帳號的部門與主管被寫入，`updated_at` 隨之更新 |
| 出勤、申請、出勤歷程、定位設定與歷程、打卡事件、專案、工時 | — | 全部相同 |
| 員工數／出勤／專案／工時 | 10／5／0／0 | 10／5／0／0 |

套用結果：
- 3 個部門，6 人的部門與主管符合示範表，6 人的 version 都是 1。
- 9 筆歷程，來源都是 `SYSTEM_DEMO_SEED`、沒有操作者。
- 其他 4 個開發帳號仍是未指派、version 0。

**第二次重啟**：日誌顯示「已符合 6 人」。員工的組織欄位、版本與 `updated_at`、部門表、歷程筆數，前後比對完全相同。

### 8.4 沒有驗證的項目

- `LAST_ADMIN` 在 E2E 中沒有直接觸發。測試庫累積了其他執行留下的 ADMIN，畫面上也不允許降級自己。這條規則由後端 O-09（依舊身份呼叫）與 CC-02（兩位 ADMIN 同時互相降級，8 輪）驗證；E2E 只驗證「不能降級自己」的 403。
- 大量員工（數千人）時的分頁與搜尋效能沒有量測。
- 手機只用瀏覽器模擬尺寸（390×844），沒有實機。
- 上一輪記錄的偶發登入逾時：本輪全部 E2E 都沒有再出現，**原因仍未確定**。

## 9. 面試重點

1. **為什麼版本號（樂觀鎖）不夠？** 它只保護單筆。跨多筆的規則（循環、最後 ADMIN、部門人數）需要讓「讀取、檢查、寫入」整段不被插隊。
2. **advisory lock 和 `SELECT … FOR UPDATE` 差在哪？** advisory lock 鎖的是應用程式自訂的「鍵」，不是某一列，所以能保護「整個組織」這種沒有單一列可鎖的概念。`FOR UPDATE` 則要先知道所有相關列，還要固定加鎖順序。
3. **為什麼用交易級（`_xact_`）？** 不用手動解鎖，例外時也一定會釋放。session 級的鎖若忘記解鎖，會一直掛在連線池的連線上。
4. **循環怎麼檢查？** 從新主管沿鏈往上走，看會不會走到自己。資料庫用 CHECK 擋「直接自指」，但擋不了間接循環。
5. **Check-then-act 是什麼？** 先查、再依結果寫入；兩個請求同時查到「可以」就都寫了。解法是讓查與寫成為不可分割的一段（鎖或條件式寫入）。
6. **`ON CONFLICT DO NOTHING` 和捕捉唯一限制例外有什麼不同？** PostgreSQL 在約束錯誤後，整個交易會進入失敗狀態，之後不能再查詢。`ON CONFLICT` 讓交易保持健康，還能接著判斷是哪一個欄位重複。
7. **角色和資料範圍為什麼要分開？** MANAGER 是「能審核」，但「能審核誰」是另一個問題。混在一起，就會變成所有主管都能看所有人。

## 10. 練習（不附答案）

如果之後要支援「部門主管」，也就是每個部門可以設定一位主管，新進員工沒指定直屬主管時，自動以部門主管為直屬主管：

- 需要新增哪些欄位與限制？
- 「停用部門主管」時要守住哪些新的不變條件？
- 原本的組織鎖還夠不夠？為什麼？
- 「自動」決定主管這件事，與本批「不自動改派」的原則有什麼衝突？你會怎麼取捨？

---

> **T05 後續變更（2026-10-06）**：
> - `OrganizationLock` 新增 `acquireFor(actor)`：取得鎖之後會重新確認操作者仍存在、仍啟用、角色與 Session 快照相同。部門與員工的寫入改用它。
> - 因此 O-09 改為驗證「依舊身分送出 → 401」。`LAST_ADMIN` 退為第二道防線，由 `OrganizationLastAdminDefenseTest`（O-09b，以 Spy 模擬操作者檢查失效）驗證。
> - 這把鎖的範圍也擴大到請假寫入。
> - 詳見 `docs/learning/T05-leave-and-approval.md`。
