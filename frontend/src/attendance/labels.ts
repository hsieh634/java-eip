import type { AttendanceStatus } from '../api/attendance'
import type { HistoryOperation, RequestStatus, RequestType, TimePair } from '../api/attendanceRequests'
import { formatTime } from '../lib/time'

export const RECORD_STATUS_LABEL: Record<AttendanceStatus, string> = {
  NOT_STARTED: '尚未上班',
  WORKING: '工作中',
  COMPLETED: '已完成',
  INCOMPLETE: '未完成（未下班）',
  VOIDED: '已作廢（重新打卡）',
}

export const REQUEST_TYPE_LABEL: Record<RequestType, string> = {
  RESET_TODAY: '申請重新打卡',
  CORRECT_TIME: '申請更正／補登',
}

export const REQUEST_STATUS_LABEL: Record<RequestStatus, string> = {
  PENDING: '待審核',
  APPROVED: '已核准',
  REJECTED: '已退回',
  CANCELLED: '已撤回',
}

export const OPERATION_LABEL: Record<HistoryOperation, string> = {
  REQUEST_SUBMITTED: '提出申請',
  REQUEST_CANCELLED: '撤回申請',
  REQUEST_APPROVED: '核准申請',
  REQUEST_REJECTED: '退回申請',
  DIRECT_CORRECTION: '管理員直接更正',
  DIRECT_VOID: '管理員作廢（開放重新打卡）',
}

/** 「09:00:00 ～ 18:00:00」；沒有紀錄顯示「無紀錄」。 */
export function describeTimes(pair: TimePair | null): string {
  if (!pair) return '無紀錄'
  return `${formatTime(pair.clockInAt)} ～ ${formatTime(pair.clockOutAt)}`
}
