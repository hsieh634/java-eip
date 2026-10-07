import { api } from './http'
import { ApiError } from './http'
import { checkCreateResult, confirmsDuplicate } from '../timesheet/submitOutcome'

/** 對應後端 EntryResponse。minutes 一律是整數分鐘。 */
export interface TimesheetEntry {
  id: number
  workDate: string
  project: { id: number; code: string; name: string; active: boolean }
  description: string
  minutes: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface TimesheetList {
  from: string
  to: string
  timeZone: string
  entries: TimesheetEntry[]
  totalMinutes: number
  daily: { date: string; minutes: number }[]
}

/** 新增：requestId 是這一次「邏輯上的新增」的識別碼；結果不明而重送時必須沿用同一個。沒有 employeeId——員工來自登入身分。 */
export interface CreateEntryBody {
  requestId: string
  workDate: string
  projectId: number
  description: string
  minutes: number
}

export interface UpdateEntryBody {
  workDate: string
  projectId: number
  description: string
  minutes: number
  expectedVersion: number
}

const BASE = '/api/timesheets/me'

/** 不給範圍＝由後端決定預設（本月）。 */
export const fetchTimesheets = (range?: { from: string; to: string }) =>
  api<TimesheetList>(range ? `${BASE}?from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}` : BASE)
export const fetchEntry = (id: number) => api<TimesheetEntry>(`${BASE}/${id}`)
export async function createEntry(body: CreateEntryBody): Promise<TimesheetEntry> {
  try {
    return checkCreateResult(await api<unknown>(BASE, { method: 'POST', body }), body)
  } catch (error) {
    if (error instanceof ApiError && error.code === 'DUPLICATE_SUBMISSION' && !confirmsDuplicate(error))
      throw new ApiError(0, 'UNCONFIRMED_RESULT', '原提交已存在的回應不完整，請重送原內容確認')
    if (error instanceof ApiError) throw error
    // 成功狀態後的本文讀取／JSON 解析失敗，無法證明後端沒有建立。
    throw new ApiError(0, 'UNCONFIRMED_RESULT', '成功回應無法讀取，請重送原內容確認結果')
  }
}
export const updateEntry = (id: number, body: UpdateEntryBody) => api<TimesheetEntry>(`${BASE}/${id}`, { method: 'PUT', body })
