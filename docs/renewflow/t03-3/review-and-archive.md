# 作者 Review 與 Archive

2026-10-07，Codex 作者審查；等待 ChatGPT 複審。基準 `af596813c4a8c1d89afcedce1d14cc0ee0db5508`，完成實作 SHA 見共用交接；本文件不宣稱外部複審通過。

已檢查 Controller → Service → Repository 呼叫、指定 ID／版本、所有出勤寫入的鎖順序、重疊與唯一未完成防線、原事件保留、定位快照、原成功回應重放、出勤／事件／去重／歷程回滾，以及 UI unknown 的原本文與明確放棄規則。工時修補維持共用 HTTP 與請假實作；新增的上層表單鎖避免未確認時切換編輯而丟失原提交。

所有實作、規格及測試由本輪 Codex 新增或修改；接手時工作區乾淨，未發現 Claude 未提交成果。V1～V7 未修改；V8 首次在測試庫套用後未改寫。未新增套件，未變更環境密碼或認證設定，未代真實使用者操作。一般 commit／push main 是本輪附件第 11 節明確授權；不部署。

規格階段的文件依序保存需求／領域、限制、架構、資料、API、安全、Proposal、Design、Specification、Tasks；實作結果、逐案可重跑證據及未驗證範圍在 verification.md，操作與問答在學習文件。手機截圖經作者檢視，真人操作及實機尚未完成。

待辦只有 ChatGPT 複審與另外授權的後續階段。效能／鎖等待負載未驗證，跨重新整理的快照不持久化；重新進頁必須查真實狀態。開發服務尚未重啟，新功能尚未載入開發服務，開發庫仍 V7。升級時先依既有方式保留備份，再由使用者重啟後端套 V8；禁止以 clean／repair 解決問題。

Archive 保留原 log 在 backend/target 與 frontend/e2e/output（被 Git 忽略、不提交 Session／CSRF／測試執行日誌）；提交規格、證據摘要及合成資料的逐表雜湊。完整變更檔案清單見 changed-files.md，完成版本見 development-handoff.md。
