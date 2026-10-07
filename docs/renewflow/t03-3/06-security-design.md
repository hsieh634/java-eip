# T03.3 安全設計

Session、CSRF、ActiveAccountFilter 與既有資料範圍保持。取組織鎖後以 acquireFor 重新驗證 HTTP 操作者是否停用或角色變更，避免排隊期間沿用舊權限。角色檢查、自審與直接改自己出勤的禁止規則保留；沒有新增他人定位查詢。

所有出勤寫入順序：組織 advisory lock → 員工 FOR NO KEY UPDATE → 定位設定 FOR SHARE（僅新打卡）→ 申請／出勤／事件／去重／歷程。NO KEY UPDATE 與工時 UPDATE 互斥、與定位歷程外鍵 KEY SHARE 相容。

前端未知結果不自動換 requestId 或動作，不自動重送；切頁後不繼續操作。跨重新整理不保存提交追蹤與座標。放棄須先顯示可能已成功的警告，再明確確認。測試資料庫先查實際 catalog 與 allowlist，再 migration／seed／測試重置；絕不清空開發庫。
