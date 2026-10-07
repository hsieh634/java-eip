# V7 → V8 資料保留證據

2026-10-07，隔離資料庫 renewflow_test，專用 schema migration_probe_v8。測試先以 V1～V7 建立結構，加入各表代表資料，記錄筆數與逐列 JSON 排序後的 MD5，再套 V8；14 表皆相同。測試資料包含原 ID、微秒時間、MANUAL／更正、版本、作廢與各模組外鍵。此比較使用合成資料，不是開發庫升級證據。

| 表 | 筆數 | 升級前 MD5 | 升級後 MD5 | 比較 |
|---|---|---|---|---|
| employee | 3 | 9439bb068c394fbd291af34d3703e7fd | 9439bb068c394fbd291af34d3703e7fd | 相同 |
| department | 1 | 524891e1554362e839546391f79fd8fb | 524891e1554362e839546391f79fd8fb | 相同 |
| organization_history | 1 | 12405aea427abd70b72d39d81d358d30 | 12405aea427abd70b72d39d81d358d30 | 相同 |
| attendance | 2 | 92804f999983cb4aec8706a63d0fe0bc | 92804f999983cb4aec8706a63d0fe0bc | 相同 |
| attendance_request | 1 | 2a301b8b4fc4db38d5c16d99e62401f6 | 2a301b8b4fc4db38d5c16d99e62401f6 | 相同 |
| attendance_history | 1 | 43ed3056015197701f77a678cdcbb26b | 43ed3056015197701f77a678cdcbb26b | 相同 |
| geofence_setting | 1 | 717f38686fdab511263f6a5590bdb281 | 717f38686fdab511263f6a5590bdb281 | 相同 |
| geofence_setting_history | 1 | f4e3d963c89b27dd2f0a9e8977eaa6a2 | f4e3d963c89b27dd2f0a9e8977eaa6a2 | 相同 |
| attendance_clock_event | 1 | 45c140a75a1a50cb4d3373d7e547ace4 | 45c140a75a1a50cb4d3373d7e547ace4 | 相同 |
| project | 1 | c9b7137a89cdcde96e37ec7f90267571 | c9b7137a89cdcde96e37ec7f90267571 | 相同 |
| timesheet_entry | 1 | 5ea8251b77d0623f58a6753960a21ee8 | 5ea8251b77d0623f58a6753960a21ee8 | 相同 |
| leave_request | 1 | 6f4657745a2a67b87eb0928d6efd546a | 6f4657745a2a67b87eb0928d6efd546a | 相同 |
| leave_history | 1 | b73a2211e1e6ccbcf6818423259c0b4f | b73a2211e1e6ccbcf6818423259c0b4f | 相同 |
| leave_approver_setting | 1 | 4e79b9b01429d624f51cfa2146946164 | 4e79b9b01429d624f51cfa2146946164 | 相同 |

新 attendance_clock_submission 為 0 筆。V8 僅更換出勤部分唯一索引並建立去重表；原表不改、不刪資料。開發庫 renewflow 本輪唯讀確認仍為 V7；未執行 repair／clean／重新建庫。獨立測試 schema 在 finally 清理，其餘 schema 不受影響。原始證據：backend/target/t03-3-migration-evidence.txt；可重跑：MultipleAttendanceSchemaTest。
