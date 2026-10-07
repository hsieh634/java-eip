# T03.3 架構

沿用 Controller → Service → Repository、DTO、建構子注入與 Flyway。

AttendanceWriteRules 集中所有出勤寫入的鎖順序與跨列合法性。AttendanceClockSubmissions 是 JDBC Repository，保存不可重複的員工＋requestId 與原成功快照。沒有新增套件或共用 HTTP 抽象。詳細呼叫流程、必要性與取捨見 [design.md](design.md) 與 [教材](../../learning/T03-3-multiple-attendance-and-submit-results.md)。
