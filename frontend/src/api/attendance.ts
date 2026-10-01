import { api } from './http'

export type AttendanceStatus = 'NOT_STARTED' | 'WORKING' | 'COMPLETED' | 'INCOMPLETE'

/** 對應後端 AttendanceRecordResponse。時間是 ISO 8601（UTC）。 */
export interface AttendanceRecord {
  workDate: string
  clockInAt: string
  clockOutAt: string | null
  status: AttendanceStatus
}

/** 對應後端 TodayAttendanceResponse；沒有紀錄時 status 是 NOT_STARTED（不是錯誤）。 */
export interface TodayAttendance {
  status: AttendanceStatus
  workDate: string
  timeZone: string
  clockInAt: string | null
  clockOutAt: string | null
  unfinishedBefore: string | null
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
