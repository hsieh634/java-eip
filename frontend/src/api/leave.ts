import type { Role } from './auth'
import { api, ApiError } from './http'
import type { AttendanceRecord } from './attendance'

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
  revision?: RevisionInfo
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
  leaveRequestId?: number | null
  occurredAt: string
  decision?: DecisionContext | null
  decisionRestricted?: boolean
}

export interface LeavePage {
  items: LeaveRequest[]
  page: number
  size: number
  total: number
  effectiveTotalMinutes?: number | null
}

export interface LeaveDetail {
  request: LeaveRequest
  history: LeaveHistoryEntry[]
  document?: LeaveDocument
  evidence?: AttendanceEvidence
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

/**
 * 201＝新建立；200 且 replayed＝相同 requestId＋相同內容的重送（回傳原申請，沒有新增）。
 * 回傳 unknown：網路資料沒有經過驗證前不能當成 LeaveRequest 使用，呼叫端必須以 leave/submitOutcome.checkSubmitResult 驗證（T05 複審 R1）。
 */
export const submitLeave = (body: LeaveSubmission & { requestId: string }) => api<unknown>('/api/leave/me', { method: 'POST', body })
export const withdrawLeave = (id: number, expectedVersion: number, reason: string | null) =>
  api<LeaveDetail>(`/api/leave/me/${id}/withdraw`, { method: 'POST', body: { expectedVersion, reason } })

// ---------------------------------------------------------------- 審核人

export const fetchApprovals = (q: ListQuery) => api<LeavePage>(`/api/leave/approvals?${listParams(q)}`)
export const fetchApproval = (id: number) => api<LeaveDetail>(`/api/leave/approvals/${id}`)
export const approveLeave = (id: number, expectedVersion: number, evidenceToken?: string) =>
  api<LeaveDetail>(`/api/leave/approvals/${id}/approve`, { method: 'POST', body: { expectedVersion, evidenceToken } })
export const rejectLeave = (id: number, expectedVersion: number, reason: string, evidenceToken?: string) =>
  api<LeaveDetail>(`/api/leave/approvals/${id}/reject`, { method: 'POST', body: { expectedVersion, reason, evidenceToken } })

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

// ---------------------------------------------------------------- T05.1 業務單、版本、出勤摘要
export type RevisionOperation = 'REVISE' | 'CHANGE' | 'CANCEL_ALL' | 'CANCEL_PART'
export interface RevisionInfo {
  documentId: number; number: number; operation: 'CREATE' | RevisionOperation
  sourceRequestId: number | null; sourceVersion: number | null
  baseEffectiveRequestId: number | null; baseEffectiveVersion: number
  effectiveVersion: number; currentlyEffective: boolean; canceled: boolean
  submittedApprover: PersonRef; submittedApproverSource: string
  viewMode?: 'FULL' | 'REVIEW_HISTORY'
}
export interface LeaveDocument {
  id: number; effectiveVersion: number; canceled: boolean
  origin: LeaveRequest; originalApproved: LeaveRequest | null; effective: LeaveRequest | null
  pending: LeaveRequest | null; versions: LeaveRequest[]
  viewMode?: 'FULL' | 'REVIEW_HISTORY'; throughRevision?: number
}
export interface AttendanceEvidence {
  dates: string[]; records: AttendanceRecord[]
  events: { id: number; attendanceId: number; workDate: string; action: 'CLOCK_IN' | 'CLOCK_OUT'; acceptedAt: string }[]
  capturedAt: string; token: string
}
export interface DecisionContext {
  previewVerified: boolean
  beforeEffectiveRequestId: number | null; beforeEffectiveVersion: number
  afterEffectiveRequestId: number | null; afterEffectiveVersion: number
  attendance: AttendanceEvidence
}
export interface RevisionSubmission extends LeaveSubmission {
  requestId: string; operation: RevisionOperation; expectedSourceVersion: number
  expectedEffectiveRequestId: number | null; expectedEffectiveVersion: number
}
export const submitRevision = (sourceId: number, body: RevisionSubmission) =>
  api<unknown>(`/api/leave/me/${sourceId}/revisions`, { method: 'POST', body })
/** 此表單只讀明確 ID，不採用別單或新增模式。其餘成功契約在表單提交邊界檢查。 */
export async function fetchRevisionTarget(id: number): Promise<LeaveDetail> {
  const detail = await fetchMyLeave(id)
  const r = detail?.request, d = detail?.document
  const validContent = (v: LeaveRequest | null | undefined) => !!v && Number.isSafeInteger(v.id) && v.id > 0 &&
    Number.isSafeInteger(v.version) && v.version >= 0 && ['PERSONAL', 'SICK', 'ANNUAL'].includes(v.leaveType) &&
    ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'].includes(v.status) && typeof v.reason === 'string' &&
    typeof v.workDate === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v.workDate) &&
    typeof v.startTime === 'string' && /^\d{2}:\d{2}$/.test(v.startTime) && typeof v.endTime === 'string' && /^\d{2}:\d{2}$/.test(v.endTime)
  if (!validContent(r) || r.id !== id || !r.revision || !d || !Number.isSafeInteger(d.id) || d.id <= 0 ||
      d.id !== r.revision.documentId || !Number.isSafeInteger(d.effectiveVersion) || d.effectiveVersion < 0 ||
      typeof d.canceled !== 'boolean' || !validContent(d.origin) || d.origin.id !== d.id ||
      !Array.isArray(d.versions) || !d.versions.every(v => validContent(v) && v.revision?.documentId === d.id) ||
      !d.versions.some(v => v.id === id) || (d.effective !== null && !validContent(d.effective)) ||
      (d.pending !== null && !validContent(d.pending)) ||
      (d.effective && d.effective.revision?.documentId !== d.id) || (d.pending && d.pending.revision?.documentId !== d.id))
    throw new ApiError(0, 'INVALID_REVISION_TARGET', '無法確認原選擇假單的最新資料，不能送出；請重新明確開啟該單')
  return detail
}
