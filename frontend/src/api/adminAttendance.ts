import type { AttendanceRecord } from './attendance'
import type { AttendanceRequest, EmployeeSummary, HistoryEntry } from './attendanceRequests'
import { api } from './http'

const BASE = '/api/admin/attendance'

export interface AdminRecords {
  employee: EmployeeSummary
  from: string
  to: string
  timeZone: string
  records: AttendanceRecord[]
}

export interface DirectCorrectionBody {
  employeeId: number
  workDate: string
  /** 省略（null）＝建立；有值＝更正既有紀錄（版本不符會被拒絕） */
  expectedVersion: number | null
  clockInTime: string
  clockOutTime: string | null
  reason: string
}

export const fetchRequests = (status = 'PENDING') =>
  api<{ requests: AttendanceRequest[] }>(`${BASE}/requests?status=${encodeURIComponent(status)}`).then((r) => r.requests)
export const approveRequest = (id: number, comment: string) =>
  api<AttendanceRequest>(`${BASE}/requests/${id}/approve`, { method: 'POST', body: { comment: comment || null } })
export const rejectRequest = (id: number, reason: string) =>
  api<AttendanceRequest>(`${BASE}/requests/${id}/reject`, { method: 'POST', body: { reason } })
export const fetchEmployees = () => api<EmployeeSummary[]>(`${BASE}/employees`)
export const fetchEmployeeRecords = (employeeId: number, from: string, to: string) =>
  api<AdminRecords>(`${BASE}/records?employeeId=${employeeId}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`)
export const correctDirectly = (body: DirectCorrectionBody) => api<AttendanceRecord>(`${BASE}/records/correct`, { method: 'POST', body })
export const voidToday = (body: { employeeId: number; attendanceId: number; expectedVersion: number; reason: string }) =>
  api<AttendanceRecord>(`${BASE}/records/void`, { method: 'POST', body })
export const fetchEmployeeHistory = (employeeId: number, from: string, to: string) =>
  api<{ entries: HistoryEntry[] }>(`${BASE}/history?employeeId=${employeeId}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`).then((r) => r.entries)
