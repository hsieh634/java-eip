import { api } from './http'

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
export const createEntry = (body: CreateEntryBody) => api<TimesheetEntry>(BASE, { method: 'POST', body })
export const updateEntry = (id: number, body: UpdateEntryBody) => api<TimesheetEntry>(`${BASE}/${id}`, { method: 'PUT', body })
