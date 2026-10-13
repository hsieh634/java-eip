# 作者 Review 與 Archive

> 2026-10-13 狀態更新（以下保留前輪歷史）：使用者帶回 T03.3 R1 程式複審通過，ChatGPT實際表單邏輯20项受控检查基準11／20→修正20／20；BUG-TP-001前輪已通過。前輪作者29／29是另一組檢查，不能合併為ChatGPT重跑全部整合／E2E。真人仍未完成。本輪本機啟用及待真人教材見 [T05.1](../t05-1/verification.md)。

2026-10-07，Codex 作者審查；等待 ChatGPT 複審。基準 `af596813c4a8c1d89afcedce1d14cc0ee0db5508`，完成實作 SHA 見共用交接；本文件不宣稱外部複審通過。

已檢查 Controller → Service → Repository 呼叫、指定 ID／版本、所有出勤寫入的鎖順序、重疊與唯一未完成防線、原事件保留、定位快照、原成功回應重放、出勤／事件／去重／歷程回滾，以及 UI unknown 的原本文與明確放棄規則。工時修補維持共用 HTTP 與請假實作；新增的上層表單鎖避免未確認時切換編輯而丟失原提交。

所有實作、規格及測試由本輪 Codex 新增或修改；接手時工作區乾淨，未發現 Claude 未提交成果。V1～V7 未修改；V8 首次在測試庫套用後未改寫。未新增套件，未變更環境密碼或認證設定，未代真實使用者操作。一般 commit／push main 是本輪附件第 11 節明確授權；不部署。

規格階段的文件依序保存需求／領域、限制、架構、資料、API、安全、Proposal、Design、Specification、Tasks；實作結果、逐案可重跑證據及未驗證範圍在 verification.md，操作與問答在學習文件。手機截圖經作者檢視，真人操作及實機尚未完成。

待辦只有 ChatGPT 複審與另外授權的後續階段。效能／鎖等待負載未驗證，跨重新整理的快照不持久化；重新進頁必須查真實狀態。開發服務尚未重啟，新功能尚未載入開發服務，開發庫仍 V7。升級時先依既有方式保留備份，再由使用者重啟後端套 V8；禁止以 clean／repair 解決問題。

Archive 保留原 log 在 backend/target 與 frontend/e2e/output（被 Git 忽略、不提交 Session／CSRF／測試執行日誌）；提交規格、證據摘要及合成資料的逐表雜湊。完整變更檔案清單見 changed-files.md，完成版本見 development-handoff.md。


## 第 1 輪 R1 追加

基準 d3d2d52761f9cb8e958828022d6b2f67d633ef5f，前端指定目標失效 fallback 修正已完成作者自測；新規格、原版失敗／修補通過、逐案資料比較、讀截圖與限制統一保存於 [review-round-1](review-round-1.md)。本文件以上為前批歷史證據。本輪後端／API／migration／共用元件／套件未改，開發庫仍 V7、開發服務未動。等待 ChatGPT 複審；T03.3 不結案，T05.1 尚未開始。
