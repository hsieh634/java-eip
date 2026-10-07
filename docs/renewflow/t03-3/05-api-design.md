# T03.3 API 契約

POST `/api/attendance/clock-in` 本文 `{ "requestId": "UUID" }`，需要定位時同時附 latitude、longitude、accuracy、capturedAt。POST clock-out 除上述欄位，必須附 expectedAttendanceId＋expectedVersion，指向按下時看到的未完成組。身份、業務日期與打卡時間由後端決定。

成功為 200 AttendanceRecordResponse，打卡回應額外帶相同 requestId；一般紀錄查詢此欄位為 null。同員工同鍵同有效本文回原快照；異操作／本文為 409 CLOCK_REQUEST_MISMATCH，不新增事件。原請求沒有成功且下班目標已變更時為 409 RECORD_CHANGED，不關閉新組。

GET `/api/attendance/me/today` 新增 records（含作廢）、completedSeconds（完成有效時段合計）。status=WORKING 才能下班；NOT_STARTED／COMPLETED 都可上班。recordId／version 等舊欄位表示未完成組，若沒有則最後有效組。

更正與作廢沿用指定 ID＋版本契約；不指定 ID 的更正明確表示補登新時段。重疊或非最後未完成組拒絕；核准衝突仍回 REQUEST_STALE 並回滾。每天最多一筆待審更正不變。

工時成功 EntryResponse 無 requestId，以實際欄位與原提交內容驗證；未知結果及有效 DUPLICATE_SUBMISSION 判定見 design.md。
