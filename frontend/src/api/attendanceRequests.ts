import { api } from './http'

export type RequestType = 'RESET_TODAY' | 'CORRECT_TIME'
/**
 * 給人看的申請種類（由後端依內容判斷）：撤銷下班在資料上是 CORRECT_TIME（上班不變、下班清空），
 * 但畫面與歷程要和「整組作廢」「一般更正」清楚區分。
 */
export type RequestKind = 'RESET_TODAY' | 'REVOKE_CLOCK_OUT' | 'CORRECT_TIME'
export type RequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'

export interface TimePair {
  clockInAt: string | null
  clockOutAt: string | null
}

export interface EmployeeSummary {
  id: number
  employeeNo: string
  displayName: string
}

/** 對應後端 AttendanceRequestResponse。original＝申請當時的原值；requested＝希望值；current＝目前的有效紀錄（待審時）。 */
export interface AttendanceRequest {
  id: number
  type: RequestType
  kind: RequestKind
  status: RequestStatus
  workDate: string
  reason: string
  employee: EmployeeSummary
  original: TimePair | null
  requested: TimePair | null
  current: TimePair | null
  /** 待審但目標資料已變動，核准會被拒絕；員工可撤回後重新提出 */
  stale: boolean
  createdAt: string
  reviewedAt: string | null
  reviewerName: string | null
  reviewComment: string | null
}

export type HistoryOperation =
  | 'REQUEST_SUBMITTED'
  | 'REQUEST_CANCELLED'
  | 'REQUEST_APPROVED'
  | 'REQUEST_REJECTED'
  | 'DIRECT_CORRECTION'
  | 'DIRECT_VOID'

export interface HistoryEntry {
  id: number
  operation: HistoryOperation
  workDate: string
  requestId: number | null
  requestType: RequestType | null
  requestKind: RequestKind | null
  reason: string | null
  actorName: string
  employeeName: string
  original: TimePair
  requested: TimePair | null
  after: { clockInAt: string | null; clockOutAt: string | null; voided: boolean } | null
  result: string | null
  occurredAt: string
}

/**
 * 表單快照：開啟表單時看到的那一筆有效紀錄（ID＋版本）。兩者同時有值或同時為 null（＝看到「沒有紀錄」）。
 * 後端會比對，紀錄已被更正、作廢或替換就以 409 RECORD_CHANGED 拒絕，不會用舊畫面的時間提出申請。
 */
export interface RecordSnapshot {
  expectedAttendanceId: number | null
  expectedVersion: number | null
}

/** 員工提出的申請內容。時間是「台北當地時間」HH:mm:ss（可含小數秒）；沒有任何身份、審核者或時間戳欄位。 */
export type CreateRequestBody = RecordSnapshot &
  (
    | { type: 'RESET_TODAY'; reason: string }
    | { type: 'CORRECT_TIME'; reason: string; workDate: string; clockInTime: string; clockOutTime: string | null }
  )

export const submitRequest = (body: CreateRequestBody) => api<AttendanceRequest>('/api/attendance/requests', { method: 'POST', body })
export const fetchMyRequests = () => api<{ requests: AttendanceRequest[] }>('/api/attendance/requests').then((r) => r.requests)
export const cancelRequest = (id: number) => api<AttendanceRequest>(`/api/attendance/requests/${id}/cancel`, { method: 'POST' })
export const fetchMyHistory = () => api<{ entries: HistoryEntry[] }>('/api/attendance/me/history').then((r) => r.entries)
