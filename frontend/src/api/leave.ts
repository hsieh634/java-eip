import type { Role } from './auth'
import { api } from './http'

export type LeaveType = 'PERSONAL' | 'SICK' | 'ANNUAL'
export type LeaveStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'
export type StatusFilter = 'ALL' | LeaveStatus
export type ApproverSource = 'DESIGNATED' | 'MANAGER' | 'REASSIGNED'
export type HistoryAction = 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'REASSIGNED' | 'APPROVER_SETTING_CHANGED'

export interface PersonRef {
  id: number
  employeeNo: string
  displayName: string
}

/** valid：審核人「目前」是否仍是啟用中的 MANAGER／ADMIN。 */
export interface ApproverRef extends PersonRef {
  valid: boolean
}

/** 對應後端 LeaveRequestResponse。分鐘數、狀態、審核人都由後端決定。 */
export interface LeaveRequest {
  id: number
  requestId: string
  leaveType: LeaveType
  workDate: string
  startTime: string
  endTime: string
  minutes: number
  reason: string
  status: LeaveStatus
  applicant: PersonRef
  approver: ApproverRef
  approverSource: ApproverSource
  decidedBy: PersonRef | null
  decidedAt: string | null
  decisionReason: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface LeaveHistoryEntry {
  id: number
  action: HistoryAction
  fromStatus: LeaveStatus | null
  toStatus: LeaveStatus | null
  beforeApprover: PersonRef | null
  afterApprover: PersonRef | null
  reason: string | null
  actor: PersonRef
  occurredAt: string
}

export interface LeavePage {
  items: LeaveRequest[]
  page: number
  size: number
  total: number
}

export interface LeaveDetail {
  request: LeaveRequest
  history: LeaveHistoryEntry[]
}

export interface ApproverPreview {
  approver: PersonRef | null
  source: 'DESIGNATED' | 'MANAGER' | 'NONE'
  problem: string | null
}

export interface ListQuery {
  status: StatusFilter
  /** 同時提供或都不提供（空字串＝不限日期） */
  from: string
  to: string
  page: number
  size?: number
}

function listParams(q: ListQuery, extra: Record<string, string> = {}) {
  const p = new URLSearchParams({ status: q.status, page: String(q.page), size: String(q.size ?? 20), ...extra })
  if (q.from && q.to) {
    p.set('from', q.from)
    p.set('to', q.to)
  }
  return p.toString()
}

// ---------------------------------------------------------------- 本人

export const fetchMyLeaves = (q: ListQuery) => api<LeavePage>(`/api/leave/me?${listParams(q)}`)
export const fetchMyLeave = (id: number) => api<LeaveDetail>(`/api/leave/me/${id}`)
export const fetchApproverPreview = () => api<ApproverPreview>('/api/leave/me/approver-preview')

export interface LeaveSubmission {
  leaveType: LeaveType
  workDate: string
  startTime: string
  endTime: string
  reason: string
}

/** 201＝新建立；200 且 replayed＝相同 requestId＋相同內容的重送（回傳原申請，沒有新增）。 */
export const submitLeave = (body: LeaveSubmission & { requestId: string }) =>
  api<{ replayed: boolean; request: LeaveRequest }>('/api/leave/me', { method: 'POST', body })
export const withdrawLeave = (id: number, expectedVersion: number, reason: string | null) =>
  api<LeaveDetail>(`/api/leave/me/${id}/withdraw`, { method: 'POST', body: { expectedVersion, reason } })

// ---------------------------------------------------------------- 審核人

export const fetchApprovals = (q: ListQuery) => api<LeavePage>(`/api/leave/approvals?${listParams(q)}`)
export const fetchApproval = (id: number) => api<LeaveDetail>(`/api/leave/approvals/${id}`)
export const approveLeave = (id: number, expectedVersion: number) =>
  api<LeaveDetail>(`/api/leave/approvals/${id}/approve`, { method: 'POST', body: { expectedVersion } })
export const rejectLeave = (id: number, expectedVersion: number, reason: string) =>
  api<LeaveDetail>(`/api/leave/approvals/${id}/reject`, { method: 'POST', body: { expectedVersion, reason } })

// ---------------------------------------------------------------- ADMIN

export const fetchAdminLeaves = (q: ListQuery, employeeId: number | null) =>
  api<LeavePage>(`/api/admin/leave/requests?${listParams(q, employeeId ? { employeeId: String(employeeId) } : {})}`)
export const fetchAdminLeave = (id: number) => api<LeaveDetail>(`/api/admin/leave/requests/${id}`)
export const reassignLeave = (id: number, body: { approverId: number; expectedVersion: number; reason: string }) =>
  api<LeaveDetail>(`/api/admin/leave/requests/${id}/reassign`, { method: 'POST', body })

export interface ApproverSetting {
  employee: { id: number; employeeNo: string; displayName: string; role: Role; enabled: boolean }
  designatedApprover: ApproverRef | null
  manager: ApproverRef | null
  effective: ApproverPreview
  version: number
  updatedAt: string | null
}

export const fetchApproverSettings = () => api<{ settings: ApproverSetting[] }>('/api/admin/leave/approver-settings').then((r) => r.settings)
export const fetchApproverSetting = (employeeId: number) => api<ApproverSetting>(`/api/admin/leave/approver-settings/${employeeId}`)
/** approverId 為 null＝移除指定，改回採用直屬主管。 */
export const updateApproverSetting = (employeeId: number, body: { approverId: number | null; expectedVersion: number; reason: string }) =>
  api<ApproverSetting>(`/api/admin/leave/approver-settings/${employeeId}`, { method: 'PUT', body })
export const fetchApproverSettingHistory = (employeeId: number) =>
  api<{ history: LeaveHistoryEntry[] }>(`/api/admin/leave/approver-settings/${employeeId}/history`).then((r) => r.history)
