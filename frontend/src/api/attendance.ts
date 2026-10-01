import { api } from './http'

export type AttendanceStatus = 'NOT_STARTED' | 'WORKING' | 'COMPLETED' | 'INCOMPLETE' | 'VOIDED'

/** 對應後端 AttendanceRecordResponse。時間是 ISO 8601（UTC）。 */
export interface AttendanceRecord {
  /** 同一個日期可能有多筆（已作廢的舊紀錄＋有效紀錄），所以列表的 key 要用 id，不能只用日期。 */
  id: number
  workDate: string
  clockInAt: string
  clockOutAt: string | null
  status: AttendanceStatus
  /** CLOCK＝員工打卡；MANUAL＝補登 */
  source: 'CLOCK' | 'MANUAL'
  /** 時間是否被更正過 */
  corrected: boolean
  voidedAt: string | null
  /** 管理員直接更正時用來偵測「資料已被改過」 */
  version: number
}

/** 對應後端 TodayAttendanceResponse；沒有紀錄時 status 是 NOT_STARTED（不是錯誤）。 */
export interface TodayAttendance {
  status: AttendanceStatus
  workDate: string
  timeZone: string
  clockInAt: string | null
  clockOutAt: string | null
  unfinishedBefore: string | null
  /** 今日有效紀錄的 ID 與版本（沒有紀錄時為 null）。提出申請時原樣送回，後端用來確認「你看到的仍是目前那一筆」。 */
  recordId: number | null
  version: number | null
}

export interface AttendanceList {
  from: string
  to: string
  timeZone: string
  records: AttendanceRecord[]
}

// 所有請求都不帶員工 ID、日期或時間：身份來自 Session，時間由後端決定。
export const fetchToday = () => api<TodayAttendance>('/api/attendance/me/today')
export const clockIn = () => api<AttendanceRecord>('/api/attendance/clock-in', { method: 'POST' })
export const clockOut = () => api<AttendanceRecord>('/api/attendance/clock-out', { method: 'POST' })

/** 不給範圍＝由後端決定預設（本月）。 */
export const fetchRecords = (range?: { from: string; to: string }) =>
  api<AttendanceList>(
    range ? `/api/attendance/me?from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}` : '/api/attendance/me',
  )
