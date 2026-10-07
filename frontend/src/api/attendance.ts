import { api } from './http'
import { ApiError } from './http'

export type AttendanceStatus = 'NOT_STARTED' | 'WORKING' | 'COMPLETED' | 'INCOMPLETE' | 'VOIDED'

/** 對應後端 AttendanceRecordResponse。時間是 ISO 8601（UTC）。 */
export interface AttendanceRecord {
  /** 同一日期可能有多組有效或作廢紀錄，列表的 key 使用 id。 */
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

/**
 * 對應後端 LocationPolicyResponse：本次上班／下班是否需要定位。不含公司中心座標。
 * 只用來決定「要不要向瀏覽器要位置」與顯示說明；是否通過一律由後端判斷。
 */
export interface LocationPolicy {
  enabled: boolean
  clockInRequired: boolean
  clockOutRequired: boolean
  radiusMeters: number
  maxAccuracyMeters: number
  version: number
}

/** 打卡時附帶的原始定位資料（瀏覽器提供）。沒有距離、是否在範圍內等欄位——那些由後端計算。 */
export interface ClockLocation {
  latitude: number
  longitude: number
  accuracy: number
  /** 瀏覽器取得位置的時間（position.timestamp），ISO 8601 */
  capturedAt: string
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
  locationPolicy: LocationPolicy
  records: AttendanceRecord[]
  completedSeconds: number
}

export interface AttendanceList {
  from: string
  to: string
  timeZone: string
  records: AttendanceRecord[]
}

// 所有請求都不帶員工 ID、日期或時間：身份來自 Session，時間由後端決定。
export const fetchToday = () => api<TodayAttendance>('/api/attendance/me/today')
export const fetchLocationPolicy = () => api<LocationPolicy>('/api/attendance/location-policy')
export type ClockSubmission = Partial<ClockLocation> & { requestId: string; expectedAttendanceId?: number; expectedVersion?: number }
async function clock(action: 'in' | 'out', body: ClockSubmission): Promise<AttendanceRecord> {
  const raw = await api<unknown>(`/api/attendance/clock-${action}`, { method: 'POST', body })
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) throw new ApiError(0, 'UNCONFIRMED_RESULT', '無法確認打卡成功回應')
  const r = raw as Record<string, unknown>
  if (r.requestId !== body.requestId || typeof r.id !== 'number' || !Number.isSafeInteger(r.id) || r.id <= 0 ||
      typeof r.version !== 'number' || !Number.isSafeInteger(r.version) || r.version < 0 ||
      typeof r.workDate !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(r.workDate) ||
      typeof r.clockInAt !== 'string' || !Number.isFinite(Date.parse(r.clockInAt)) ||
      (action === 'in' ? r.status !== 'WORKING' || r.clockOutAt !== null :
        r.status !== 'COMPLETED' || typeof r.clockOutAt !== 'string' || !Number.isFinite(Date.parse(r.clockOutAt)) || r.id !== body.expectedAttendanceId) ||
      !['CLOCK', 'MANUAL'].includes(String(r.source)) || typeof r.corrected !== 'boolean' || r.voidedAt !== null)
    throw new ApiError(0, 'UNCONFIRMED_RESULT', '無法確認打卡成功回應')
  return raw as AttendanceRecord
}
export const clockIn = (body: ClockSubmission) => clock('in', body)
export const clockOut = (body: ClockSubmission) => clock('out', body)

/** 不給範圍＝由後端決定預設（本月）。 */
export const fetchRecords = (range?: { from: string; to: string }) =>
  api<AttendanceList>(
    range ? `/api/attendance/me?from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}` : '/api/attendance/me',
  )
