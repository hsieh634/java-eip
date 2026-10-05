# Tasks：T05 前置——最小組織與員工管理

> **T05 前置子任務**（T01～T06 編號不變；不包含請假）。

狀態：**已實作／作者自測完成／等待 ChatGPT 複審**。證據見 `docs/learning/T05-foundation-organization-and-employees.md` 第 8 節。

| # | 工作 | 依賴 | 完成條件 | 狀態 |
|---|---|---|---|---|
| F-1 | 規格（需求、領域、假設、架構、資料、API、安全、proposal、design、specification、tasks） | — | 本資料夾 | ☑ |
| F-2 | V6 migration；既有 schema 測試的版本清單加入 6；`TestData.reset` 清空新表 | 1 | `OrganizationSchemaTest`（乾淨庫＋V5 升級） | ☑ |
| F-3 | 後端部門管理 | 2 | O-02～O-04 | ☑ |
| F-4 | 後端員工管理（建立、修改、角色、啟用、部門、主管、自己、最後 ADMIN、部屬） | 2 | O-05～O-15 | ☑ |
| F-5 | 組織鎖、歷程（同交易、只新增） | 3、4 | CC-01～CC-05、OA-01～OA-03、O-16 | ☑ |
| F-6 | STMAX 示範組織初始化（dev、另一個開關、冪等、不覆寫） | 4、5 | SO-01～SO-09 | ☑ |
| F-7 | 前端「組織與員工」頁 | 3、4 | build、lint | ☑ |
| F-8 | E2E `organization.mjs`（隔離堆疊） | 7 | 全部通過 | ☑ |
| F-9 | 針對性變異驗證（組織鎖、循環、最後 ADMIN、Service 權限、部屬） | 5 | 相應測試失敗、還原後通過 | ☑ |
| F-10 | 全部後端測試；全部 E2E 回歸 | 全部 | 全部通過 | ☑ |
| F-11 | 開發庫套用 V6 與 STMAX 組織（先說明、前後比對、重啟冪等） | 2、6 | 比對結果符合預期 | ☑ |
| F-12 | README、共用文件、STMAX 文件、roadmap、version-log、學習文件 | 全部 | 文件完成 | ☑ |
| — | ChatGPT 複審 | — | — | ☐ 等待中 |
