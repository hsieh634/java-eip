import { ApiError } from '../api/http'
import type { LeaveStatus, LeaveSubmission } from '../api/leave'

/**
 * 請假送出的結果判定（T05 第 1 輪複審 R1）。
 *
 * 「伺服器已提交」與「前端收到可用的回應」是兩件事：HTTP 201 之後，讀取本文可能中斷（TypeError）、
 * JSON 可能損壞（SyntaxError）、內容也可能缺欄位或對不上這次提交。這些情況都不能證明「後端沒有建立」，
 * 所以一律視為「無法確認」，由呼叫端保存原快照與 requestId、鎖定表單。
 * TypeScript 的型別只在編譯時存在，不會檢查網路傳來的資料，因此這裡在執行期逐欄驗證實際用到的欄位。
 */
export class UnconfirmedResultError extends Error {
  constructor(message = 'submit result could not be confirmed') {
    super(message)
    this.name = 'UnconfirmedResultError'
  }
}

/** 驗證通過後，畫面實際使用的欄位。 */
export interface ConfirmedSubmit {
  replayed: boolean
  id: number
  status: LeaveStatus
  approverName: string
}

const STATUSES: readonly string[] = ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN']

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * 檢查成功回應是否「就是這一次提交」：結構、合法狀態、requestId 與內容一致。
 * 新建（replayed=false）一定是 PENDING；重送（replayed=true）可能已是任何狀態（例如已被撤回），符合契約即確認原申請。
 * 任何不符 → UnconfirmedResultError（不能提示成功，也不能清掉原提交）。
 */
export function checkSubmitResult(raw: unknown, requestId: string, sent: LeaveSubmission): ConfirmedSubmit {
  if (!isObject(raw) || typeof raw.replayed !== 'boolean' || !isObject(raw.request)) throw new UnconfirmedResultError()
  const r = raw.request
  const approver = r.approver
  const valid =
    typeof r.id === 'number' &&
    Number.isInteger(r.id) &&
    r.id > 0 &&
    r.requestId === requestId &&
    typeof r.status === 'string' &&
    STATUSES.includes(r.status) &&
    (raw.replayed || r.status === 'PENDING') &&
    r.leaveType === sent.leaveType &&
    r.workDate === sent.workDate &&
    r.startTime === sent.startTime &&
    r.endTime === sent.endTime &&
    r.reason === sent.reason &&
    isObject(approver) &&
    typeof approver.displayName === 'string' &&
    approver.displayName.length > 0
  if (!valid) throw new UnconfirmedResultError()
  return { replayed: raw.replayed, id: r.id as number, status: r.status as LeaveStatus, approverName: (approver as { displayName: string }).displayName }
}

/**
 * POST 已送出之後的錯誤，哪些代表「結果無法確認」：
 * - 網路錯誤（ApiError status 0）、5xx；
 * - 任何不是 ApiError 的錯誤：成功狀態下本文讀取中斷、JSON 解析失敗、結構驗證失敗（UnconfirmedResultError）。
 * 只有取得可確認的拒絕回應（4xx 的 ApiError，含 401）才不是「無法確認」。
 */
export function isUnconfirmed(error: unknown): boolean {
  if (error instanceof ApiError) return error.status === 0 || error.status >= 500
  return true
}
