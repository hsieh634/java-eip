# T03.3 資料模型

V8 保留 attendance 全欄位與關聯，將有效每日唯一改成有效未完成每日唯一。去重表 attendance_clock_submission：employee_id＋request_id 主鍵、action、SHA-256 fingerprint、attendance_id 外鍵與 response_json 原快照。不保存原始定位座標；定位證據仍由既有 attendance_clock_event 保存。

出勤、版本、事件、去重同交易；申請狀態、更正、歷程同交易。多組的重疊規則在員工鎖內驗證，唯一索引另保證最多一筆未完成。V1～V7 不改；V8 套用後亦不改 SQL。本輪逐表保留證據與實際套用範圍見 verification.md。
