# T05.2 第一批作者 Review／Archive

2026-10-15。依使用者附件完成本批，停止於請假單關卡設定及版本；作者自審不代替ChatGPT複審或Max真人驗收。

Requirement／Domain／Architecture／Data／API／Security／Proposal／Design先保存於design及specification／tasks後實作；後續proposal／api整理成可單獨閱讀的契約。Controller保持HTTP邊界，Service交易與規則，Repository負責條件SQL，沒有新依賴或通用BPM抽象。

核對優先來源存在但失效不fallback、ADMIN設定不取得他人審核權、發布版與歷程不可變、單草稿／單current、同UUID先確認凍結原結果、原提交快照不可變與改派只改目前指派。發布與組織／請假採同一鎖順序；快照、假單、指標與歷程原子交易。沒有發布時保留原個人／主管預設，V9舊資料不回填。

前端核對未存提示、版本衝突保留、完整原意圖／UUID查證、未知後403／404／409不誤解、舊GET／連按／手機；原個人指定頁文案同步共用流程優先關係。R1範圍內才附本次快照，沒有把全局流程或新版本洩漏給舊審核人。

作者實際證據見[verification](verification.md)、完整案例見[specification](specification.md)、變更列表見[changed-files](changed-files.md)、操作見[manual](manual-verification.md)、[教材](../../learning/T05-2-approval-settings-and-versions.md)含一題不附答案。備份／執行證據只在本機外部目錄與ignored輸出，提交僅程式、合成測試、規格、非敏感摘要與教材。

狀態：T05.2作者自測完成，等待ChatGPT複審；真人驗收待Max執行。不自行開始下一批或宣稱全面商用品質通過。
