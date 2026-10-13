# V8→V9 資料保留證據

隔離合成資料與開發實際資料分開。LeaveRevisionSchemaTest建立自己的migration_probe_leave_v9，V8造四種請假狀態、最早SUBMITTED／改派／決定及其餘15表代表資料；保存舊欄位投影後正常升V9，逐表雜湊一致、舊APPROVED可繼續變更、不可覆寫內容／pending唯一生效。只清自己的probe。最終全套345／345通過。

V8→V9：隔離庫／獨立 schema，舊欄位投影逐表雜湊（不含明文資料）
employee | rows=3 | before=e8d19265998343d916fc26c426d5a6d7 | after=e8d19265998343d916fc26c426d5a6d7
department | rows=1 | before=f7dbe4fe1968c427936da2f86a4cf452 | after=f7dbe4fe1968c427936da2f86a4cf452
organization_history | rows=1 | before=b70a879b4fe327270bab830f05a41b9f | after=b70a879b4fe327270bab830f05a41b9f
attendance | rows=2 | before=24092e6b79f3bb49974921ea1f1fbdcb | after=24092e6b79f3bb49974921ea1f1fbdcb
attendance_request | rows=1 | before=27f7e2ac37e0f30630aa46ecbfff7afb | after=27f7e2ac37e0f30630aa46ecbfff7afb
attendance_history | rows=1 | before=b20ca23cf89e232cc699d5e2817ce3a3 | after=b20ca23cf89e232cc699d5e2817ce3a3
geofence_setting | rows=1 | before=717f38686fdab511263f6a5590bdb281 | after=717f38686fdab511263f6a5590bdb281
geofence_setting_history | rows=1 | before=b3f34403f46625c149db52427162e572 | after=b3f34403f46625c149db52427162e572
attendance_clock_event | rows=1 | before=edc5106b66d141aaeb0410809627d875 | after=edc5106b66d141aaeb0410809627d875
project | rows=1 | before=ee1be02f06bfa858afd79f620fcb369f | after=ee1be02f06bfa858afd79f620fcb369f
timesheet_entry | rows=1 | before=338a663f5e65807840d06459efca20fb | after=338a663f5e65807840d06459efca20fb
leave_request | rows=4 | before=3048bff4b81cea1f3332183735e2fdcb | after=3048bff4b81cea1f3332183735e2fdcb
leave_history | rows=8 | before=c84297379c964b85e19b7570ab03ae83 | after=c84297379c964b85e19b7570ab03ae83
leave_approver_setting | rows=1 | before=596ecd6b1fa991ad0631ff4c1da7d3d2 | after=596ecd6b1fa991ad0631ff4c1da7d3d2
attendance_clock_submission | rows=1 | before=c4ac5b46af08c9d2224c1ed171e3b24a | after=c4ac5b46af08c9d2224c1ed171e3b24a

開發實際備份／還原、15表筆數／舊欄位相同、V9原檔CRC32、初始化2業務單與0錯誤，以及登入後資料不變見[verification](verification.md)。原V1～V8沒有改。V9首次套測試庫後未改，後續修正需追加版本，不repair。

V9原SQL SHA256：`52a32e75f4ed65726816d90c3f4999cf3af27d8b46f0d7ed3bdba9d4bc42c1cc`。
E2E腳本SHA256：`9fc916271311db2ab0d99f541d4421618feb9bdb767dac1c180280f2bda85331`。
