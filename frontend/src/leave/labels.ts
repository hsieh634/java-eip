import type { ApproverSource, HistoryAction, LeaveRequest, LeaveStatus, LeaveType } from '../api/leave'
import { formatMinutes } from '../lib/hours'

/** STMAX 示範假別（沒有額度；不代表正式人事制度）。 */
export const LEAVE_TYPE_LABEL: Record<LeaveType, string> = {
  PERSONAL: '事假',
  SICK: '病假',
  ANNUAL: '特休',
}

export const LEAVE_STATUS_LABEL: Record<LeaveStatus, string> = {
  PENDING: '待審',
  APPROVED: '已核准',
  REJECTED: '已退回',
  WITHDRAWN: '已撤回',
}

export const LEAVE_STATUS_CHIP: Record<LeaveStatus, string> = {
  PENDING: 'status-chip status-chip--req-pending',
  APPROVED: 'status-chip status-chip--req-approved',
  REJECTED: 'status-chip status-chip--req-rejected',
  WITHDRAWN: 'status-chip status-chip--req-cancelled',
}

export const APPROVER_SOURCE_LABEL: Record<ApproverSource | 'NONE', string> = {
  DESIGNATED: '指定請假審核人',
  MANAGER: '直屬主管',
  REASSIGNED: '管理員改派',
  NONE: '沒有合格的審核人',
}

export const ACTION_LABEL: Record<HistoryAction, string> = {
  SUBMITTED: '送出',
  APPROVED: '核准',
  REJECTED: '退回',
  WITHDRAWN: '撤回',
  REASSIGNED: '改派',
  APPROVER_SETTING_CHANGED: '修改指定審核人',
}

/** 半小時刻度的時間選項：開始 00:00～23:00、結束 00:30～23:30（不跨午夜，A-82）。 */
export const START_TIMES = halfHours(0, 46)
export const END_TIMES = halfHours(1, 47)

function halfHours(fromIndex: number, toIndex: number): string[] {
  const out: string[] = []
  for (let i = fromIndex; i <= toIndex; i++) {
    out.push(`${String(Math.floor(i / 2)).padStart(2, '0')}:${i % 2 === 0 ? '00' : '30'}`)
  }
  return out
}

/** 'HH:mm' → 分鐘數；給畫面預覽用（最終分鐘數以後端計算為準）。 */
export function minutesOfDay(hhmm: string): number {
  const [h, m] = hhmm.split(':').map(Number)
  return h * 60 + m
}

/** 申請時段長度的文字：明示未扣除休息時間，也不是計薪工時或額度。 */
export function durationText(minutes: number): string {
  return `${formatMinutes(minutes)}（申請時段長度，未自動扣除休息時間；不是計薪工時，也沒有扣抵任何額度）`
}

export function describe(r: Pick<LeaveRequest, 'leaveType' | 'workDate' | 'startTime' | 'endTime'>): string {
  return `${LEAVE_TYPE_LABEL[r.leaveType]} ${r.workDate} ${r.startTime}～${r.endTime}`
}

export const REVISION_LABEL = {
  CREATE: '新增請假', REVISE: '修改／退回重送', CHANGE: '核准後變更',
  CANCEL_ALL: '整段銷假', CANCEL_PART: '部分銷假',
} as const
