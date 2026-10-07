import { ApiError } from '../api/http'
import type { CreateEntryBody, TimesheetEntry } from '../api/timesheets'

const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const positive = (v: unknown) => typeof v === 'number' && Number.isSafeInteger(v) && v > 0
const timestamp = (v: unknown) => typeof v === 'string' && v.includes('T') && Number.isFinite(Date.parse(v))

/** EntryResponse 不含 requestId；只確認後端實際成功契約及本次提交的內容。 */
export function checkCreateResult(raw: unknown, sent: CreateEntryBody): TimesheetEntry {
  if (!object(raw) || !object(raw.project) || !positive(raw.id) || !positive(raw.project.id) ||
      typeof raw.version !== 'number' || !Number.isSafeInteger(raw.version) || raw.version < 0 ||
      typeof raw.project.code !== 'string' || !raw.project.code.trim() ||
      typeof raw.project.name !== 'string' || !raw.project.name.trim() || typeof raw.project.active !== 'boolean' ||
      raw.workDate !== sent.workDate || raw.project.id !== sent.projectId || raw.description !== sent.description ||
      raw.minutes !== sent.minutes || !timestamp(raw.createdAt) || !timestamp(raw.updatedAt)) {
    throw new ApiError(0, 'UNCONFIRMED_RESULT', '成功回應無法確認，請重送原內容確認結果')
  }
  return raw as unknown as TimesheetEntry
}

export const confirmsDuplicate = (error: unknown) => error instanceof ApiError && error.status === 409 &&
  error.code === 'DUPLICATE_SUBMISSION' && positive(error.details.entryId)
