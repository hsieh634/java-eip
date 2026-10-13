# T05.1 作者 Review／Archive

2026-10-13，Codex作者審查，基準72ba3265037a7cd09c49f3f018b0ee408170f87e。本文件不代替ChatGPT複審或Max真人驗收。實作版本與遠端SHA見共用交接。

## 規格與實作核對

Requirement／Domain／Architecture／Data／API／Security／Proposal／Design先保存於design／proposal，再列specification與tasks後實作。Controller只解析DTO、交給Service；新增RevisionService依既有組織共同鎖與交易控制版本，Repository做有條件SQL。沒有新套件、共用引擎或無關重寫。

已核對提交不可覆寫、來源同文件／員工FK、提交版號唯一、單一待審索引、正分鐘整段銷假、部分單側真子集、舊APPROVED有效初始化與舊歷程不製造決定。V1～V8不變；V9首次套隔離庫後未改。現在有效由leave_document指標判定，歷史核准不占用或重複合計。

寫入先共同鎖、等待後重驗actor，UUID確認先於現在的狀態／審核人解析；新意圖才重新解析。指定失效不fallback，不自審，ADMIN不自動取得所有審核權。來源狀態版本與基礎有效指標／版本皆核對；新提交、舊待審失效、指紋與歷程同交易；核准決定、有效切換與出勤摘要歷程同交易。真寫入後注入故障完整回滾。

重疊包含其他文件有效內容與PENDING預留；同文件排除自身；待審變更保留原有效占用並預留新區間；銷假不提前釋放。核准再查一次。無關出勤不改，只透過出勤Service取得DTO。

查詢授權以URL指向的提交為判定，提供同業務單原核准／目前有效／提交歷程作審核上下文；新決定只可針對現在指派自己的PENDING版本。沒有任意員工／日期的MANAGER出勤API，相關日期由後端決定且不含GPS。原始事件顯示原acceptedAt完整日期時間，與現在紀錄分開；歷程凍結當时摘要，舊決定無snapshot不冒充無紀錄。

前端每次開啟修改GET明確來源ID，失效或錯配不改成另一單／新增。unknown成功本文檢查、同UUID／本文手動重送、403／5xx不清追蹤、警告後明确放棄、送出防連按、另一修改入口鎖定、401及卸載後回應保護均有受控真表單檢查。共用HTTP／認證／useLoad未修改。

## 證據與Archive

逐案規格與結果見specification／verification，五個變異見mutation-evidence。執行log留backend/target與frontend/e2e/output，備份留外部`.codex/local-backups/java-eip/`，都不提交。提交只包含實作、合成測試、規格、非敏感摘要與教材；changed-files列完整範圍。

本輪T03.3 R1及BUG-TP-001已收到使用者帶回的ChatGPT通過結論；ChatGPT20項受控表單檢查（11／20→20／20）與前輪作者29項不同。本輪整合／E2E仍是作者證據。Max真人M33／M51全部未執行，不宣稱舊138案都完成。

本輪授權一般commit／push main，不force push、不部署。所有本輪變更由Codex新增，最初工作區乾淨；沒覆蓋其他人的未提交成果。停止在本批，等待ChatGPT複審與真人結果。

未實作：可設定／發布流程、職稱／職位解析、流程版本、拖拉式設計器、條件分支、代理與進階簽核；跨日／多日／中段拆成兩段、薪資、排班、雲端。全域組織鎖吞吐、實機、負載與正式環境未驗證；草稿只在當頁記憶體，刷新先查實際資料。
