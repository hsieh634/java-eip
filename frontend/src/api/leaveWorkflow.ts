import { api } from './http'
import type { PersonRef } from './leave'

export interface WorkflowValues { name: string; description: string; source: 'MANAGER' | 'DESIGNATED'; designatedApproverId: number | null }
export interface WorkflowVersion {
  id: number; number: number; status: 'DRAFT' | 'PUBLISHED'; rowVersion: number; basePublishedId: number | null
  values: WorkflowValues; createdBy: number; createdAt: string; publishedBy: number | null; publishedAt: string | null; publishReason: string | null
}
export interface WorkflowOperation { requestId: string; action: string; version: WorkflowVersion; replayed: boolean }
export interface WorkflowHistory {
  requestId: string; action: string; before: WorkflowVersion | null; after: WorkflowVersion
  beforePublishedId: number | null; afterPublishedId: number | null; actor: PersonRef; reason: string; occurredAt: string
}
export interface WorkflowSettings { current: WorkflowVersion | null; draft: WorkflowVersion | null; versions: WorkflowVersion[]; history: WorkflowHistory[] }
export interface WorkflowPreview {
  employee: PersonRef; source: string; workflowVersionId: number | null; workflowVersionNumber: number | null; workflowName: string | null
  stageName: string; rule: string; approver: PersonRef | null; personalOverride: boolean; problem: string | null
}
export const WORKFLOW_URL = '/api/admin/leave/workflow'
export const fetchWorkflow = () => api<WorkflowSettings>(WORKFLOW_URL)
export const fetchWorkflowOperation = (id: string) => api<unknown>(`${WORKFLOW_URL}/operations/${encodeURIComponent(id)}`)
export const previewWorkflow = (employeeId: number, draftId: number | null) =>
  api<WorkflowPreview>(`${WORKFLOW_URL}/preview?employeeId=${employeeId}${draftId === null ? '' : '&draftId=' + draftId}`)

export interface WorkflowIntent {
  requestId: string; action: 'DRAFT_CREATED' | 'DRAFT_SAVED' | 'PUBLISHED'; targetId: number | null; expectedRowVersion: number | null
  basePublishedId: number | null; values: WorkflowValues; url: string; method: 'POST' | 'PUT'; body: unknown
}
/** 寫入及查證共用執行期檢查；異常2xx不能清除原意圖。 */
export function checkWorkflowOperation(value: unknown, intent: WorkflowIntent): WorkflowOperation | null {
  if (!value || typeof value !== 'object') return null
  const r = value as Partial<WorkflowOperation>, v = r.version
  const expectedRow = intent.action === 'DRAFT_CREATED' ? 0 : (intent.expectedRowVersion ?? -2) + 1
  if (r.requestId !== intent.requestId || r.action !== intent.action || typeof r.replayed !== 'boolean' || !v ||
      !Number.isSafeInteger(v.id) || v.id <= 0 || !Number.isSafeInteger(v.number) || v.number <= 0 ||
      v.rowVersion !== expectedRow || v.basePublishedId !== intent.basePublishedId ||
      v.status !== (intent.action === 'PUBLISHED' ? 'PUBLISHED' : 'DRAFT') ||
      (intent.targetId !== null && v.id !== intent.targetId) || !sameWorkflowValues(v.values, intent.values)) return null
  if (intent.action === 'PUBLISHED' && (!Number.isSafeInteger(v.publishedBy) || !v.publishedBy ||
      typeof v.publishedAt !== 'string' || !Number.isFinite(Date.parse(v.publishedAt)) ||
      v.publishReason !== (intent.body as { reason: string }).reason)) return null
  return r as WorkflowOperation
}
export function sameWorkflowValues(a: WorkflowValues | undefined, b: WorkflowValues) {
  return !!a && a.name === b.name && a.description === b.description && a.source === b.source && a.designatedApproverId === b.designatedApproverId
}
