# T05.1 第1輪 R1／R2與STMAX客戶展示

2026-10-13，基準47f7fdfec33513bde50396e2864575f4c3f6e054，前批實作1ea6c625894d4645e07e5cafd70ee454d6956eb3。HEAD／遠端一致、初始工作區乾淨。ChatGPT帶回34項前端受控函式檢查通過，R1實際Service＋組裝替身重現、R2提示矛盾；前批345後端／E2E／備份是作者證據，T05.1尚未通過複審。

## Requirement → Domain Analysis

R1讀取授權必須隨資料穿過巢狀回應。本人與通過管理入口的ADMIN能看完整業务單；審核入口（包含ADMIN）只能看所選提交及必要前序，不取得後續未指派流程。R2撤回只結案當次PENDING／預留，CHANGE／CANCEL原有效不變。展示資料為獨立demo_*、獨立密碼、首次D與穩定批次；不碰原stmax_*或其他資料。

## Architecture／Data／API／Security／Proposal／Design

保留Controller→Service→Repository與既有交易。由Service依入口呼叫完整或審核歷史組裝，不接受前端scope參數。審核歷史以selected revision為邊界：所有版本、history與snapshot、有效／pending／原核准及即時出勤使用相同可見集合。重建可見前序的有效狀態，不引用文件全局有效指標。即使ADMIN，審核入口仍受限；同版本改派後舊人404。

DocumentResponse與RevisionInfo增加viewMode（FULL／REVIEW_HISTORY），throughRevision標所選內容版本；審核畫面明示截至所選版本的歷史／審核基礎，不能把未提供的現在狀態當作無假單或已銷假。審核列表不提供全局有效合計。writer核准前證據仍依當前合法待審的基礎版本產生，交易與有效版本規則不變。歷程快照指標／日期若超出可見集合，整個摘要不提供並標decisionRestricted，不修改儲存的原快照。

無migration，V1～V9不改。R2只改確認文案與列出的內容，不改撤回Service。

展示採本機Node原生CLI＋既有合法HTTP API，不新增公開seed API／框架／套件。不掛普通啟動；預設關閉，須明確enable、loopback URL、核對本專案dev行程與actual catalog。隔離測試只接受明確e2e行程＋本批新庫。先整批碰撞盤點；外部manifest保存首次D、穩定批次與步驟意圖／完成ID，不存密碼。每步先保存意圖再POST，使用UUID／唯一代碼／稽核展示標記確認已提交；重跑及人工已改過的步驟略過，不還原。必要時停止並回報，不能把碰撞當本批接管。

展示帳號與專案由合法ADMIN API，工時與假單由對應展示員工Session，審核由demo_iris／max。歷史出勤走合法MANUAL補登，有展示原因，不製造CLOCK／GPS。TEAMPORTAL_DEMO_PASSWORD由獨立本機.env變數取得，無預設、無日誌；本輪產生隨機值只追加此變數，保留其他設定。載入開發庫前備份、比較所有舊列；只有展示新增。

## Specification／Tasks（實作前）

| 案例 | 前置／步驟 | 預期／資料副作用 |
|---|---|---|
| R1-01 | Bob舊PENDING→改版Erin，Bob讀舊／新ID | 舊完整JSON不含新pending／history；新404；GET零寫入 |
| R1-02 | Bob已核准，Erin後續CHANGE換日期／核准 | 舊入口不含新日期出勤／token／快照／有效指標；Erin可看必要前序 |
| R1-03 | 同PENDING版本改派 | 舊審核人GET／處理404，新指派可用 |
| R1-04 | ADMIN曾審核舊版，其後改指派 | ADMIN審核仍受限；管理入口與本人完整 |
| R2-01 | CREATE／REVISE／CHANGE／ALL／PART確認撤回 | 列ID、操作、時段、結案與原有效；後3種原ID／版／時間不變 |
| D-01 | 關閉／非dev／缺展示密碼 | 不建立、沒有秘密輸出 |
| D-02 | 首次建demo六人／三專案／10工時／8MANUAL時段／10業務假單 | 正常約束、稽核、角色、有效與pending正確，CLOCK／GPS0 |
| D-03 | 同批重跑、故障中斷再續 | 筆數不增加、已成功步驟確認，D不變 |
| D-04 | 帳號／員編／專案代碼碰撞 | 初始化停止，不接管、不改已有列 |
| D-05 | 人工操作展示資料後重跑 | 保留人工狀態／角色／主管／密碼，不恢復初始 |

- [x] 接手規格／程式及基準核對。
- [x] 上述需求、領域、架構、資料、API、安全、Proposal、Design、Specification與Tasks。
- [x] 同核心R1舊版失敗、修正通過。
- [x] R2真畫面／資料E2E。
- [x] CLI展示建立、重跑／故障續跑／碰撞／人工保護隔離測試。
- [x] 後端全套、build／lint、指定回歸。
- [x] 開發備份、正常重啟、展示載入／重跑與既有資料比對。
- [x] 客戶Demo文件、學習與交接。
- [x] 最後敏感／差異檢查與一般commit／push：實作233e6bb7184569d7a69e305c3b48856a045c7257已推main；後續僅補交接文件。

真人／客戶Demo待Max執行；可設定簽核流程及拖拉設計器未實作。實際結果、來源版本與限制見[verification](review-round-1-verification.md)，客戶操作見[指南](../demo-stmax-customer.md)。
