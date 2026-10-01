import type { HistoryEntry, TimePair } from '../api/attendanceRequests'
import { OPERATION_LABEL, REQUEST_KIND_LABEL } from '../attendance/labels'
import { formatDateTime, formatTime, formatWorkDate } from '../lib/time'

function pair(value: TimePair | null | undefined): string {
  if (!value || (!value.clockInAt && !value.clockOutAt)) return '—'
  return `${formatTime(value.clockInAt)} ～ ${formatTime(value.clockOutAt)}`
}

/** 更正歷程列表（員工看自己的、管理員看指定員工的）。顯示操作者、原因、原值、申請值、實際修改後的值與伺服器時間。 */
export function HistoryList({ entries, showEmployee = false }: Readonly<{ entries: HistoryEntry[]; showEmployee?: boolean }>) {
  if (entries.length === 0) return <p className="muted">沒有歷程紀錄。</p>
  return (
    <ul className="history-list" data-testid="history-list">
      {entries.map((e) => (
        <li key={e.id} className="history-item" data-operation={e.operation} data-kind={e.requestKind ?? undefined}>
          <div className="request-item__head">
            <strong>{OPERATION_LABEL[e.operation]}</strong>
            {e.requestKind && <span className="status-chip">{REQUEST_KIND_LABEL[e.requestKind]}</span>}
            <span>{formatWorkDate(e.workDate)}</span>
            {showEmployee && <span>員工：{e.employeeName}</span>}
            <span className="muted small">{formatDateTime(e.occurredAt)}</span>
          </div>
          <p className="small">操作者：{e.actorName}{e.result ? `　結果：${e.result === 'APPROVED' ? '核准' : '退回'}` : ''}</p>
          {e.reason && <p className="small">原因／意見：{e.reason}</p>}
          <p className="small">
            原值：{pair(e.original)}　申請值：{pair(e.requested)}
            {e.after && <>　修改後：{e.after.voided ? '整組作廢（原時間保留）' : pair(e.after)}</>}
          </p>
        </li>
      ))}
    </ul>
  )
}
