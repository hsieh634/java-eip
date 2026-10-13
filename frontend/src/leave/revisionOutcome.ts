import type { LeaveDetail, RevisionOperation, RevisionSubmission } from '../api/leave'
import { checkSubmitResult, UnconfirmedResultError } from './submitOutcome'
import { minutesOfDay } from './labels'
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const positive = (v: unknown) => typeof v === 'number' && Number.isSafeInteger(v) && v > 0
const version = (v: unknown) => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0
const stamp = (v: unknown) => typeof v === 'string' && v.includes('T') && Number.isFinite(Date.parse(v))

/** 驗證 UUID、操作、來源快照與完整內容；編譯期型別不能確認成功。 */
export function checkRevisionResult(raw: unknown, sourceId: number, sent: RevisionSubmission, documentId: number) {
  const common = checkSubmitResult(raw, sent.requestId, sent)
  if (!object(raw) || !object(raw.request) || !object(raw.request.revision)) throw new UnconfirmedResultError()
  const r = raw.request, v = raw.request.revision
  if (raw.requestId !== sent.requestId || raw.operation !== sent.operation || raw.sourceRequestId !== sourceId ||
      raw.sourceVersion !== sent.expectedSourceVersion || raw.baseEffectiveRequestId !== sent.expectedEffectiveRequestId ||
      raw.baseEffectiveVersion !== sent.expectedEffectiveVersion || v.operation !== sent.operation || v.sourceRequestId !== sourceId ||
      v.sourceVersion !== sent.expectedSourceVersion || v.baseEffectiveRequestId !== sent.expectedEffectiveRequestId ||
      v.baseEffectiveVersion !== sent.expectedEffectiveVersion || v.documentId !== documentId || !positive(v.documentId) || !positive(v.number) ||
      Number(v.number) < 2 || !version(v.effectiveVersion) || typeof v.currentlyEffective !== 'boolean' ||
      typeof v.canceled !== 'boolean' || !positive(r.id) || !version(r.version) || !stamp(r.createdAt) || !stamp(r.updatedAt) ||
      r.minutes !== minutesOfDay(sent.endTime) - minutesOfDay(sent.startTime) ||
      !object(r.applicant) || !positive(r.applicant.id) || typeof r.applicant.displayName !== 'string' ||
      !object(r.approver) || !positive(r.approver.id) || typeof r.approver.valid !== 'boolean')
    throw new UnconfirmedResultError()
  return common
}

export function canRevise(detail: LeaveDetail, operation: RevisionOperation) {
  const r = detail.request, d = detail.document, v = r.revision
  if (!d || !v || d.canceled) return false
  if (r.status === 'APPROVED') return operation !== 'REVISE' && v.currentlyEffective && !d.pending
  if (r.status !== 'PENDING' && r.status !== 'REJECTED') return false
  const expected = v.operation === 'CREATE' ? 'REVISE' : v.operation
  return expected === operation && d.versions.at(-1)?.id === r.id && (!d.pending || d.pending.id === r.id) &&
    v.baseEffectiveRequestId === (d.effective?.id ?? null) && v.baseEffectiveVersion === d.effectiveVersion
}
