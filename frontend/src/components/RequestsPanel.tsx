import { useState } from 'react'
import type { AttendanceRequest } from '../api/attendanceRequests'
import { cancelRequest } from '../api/attendanceRequests'
import { REQUEST_KIND_LABEL, REQUEST_STATUS_LABEL, describeTimes } from '../attendance/labels'
import type { RequestsState } from '../attendance/useMyRequests'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'
import { formatWorkDate } from '../lib/time'

interface RequestsPanelProps {
  state: RequestsState
  reload: () => Promise<void>
  /** 撤回成功後通知外層（例如重新查詢出勤） */
  onChanged: () => void
}

/** 我的申請：待審、已處理的結果（含退回原因），以及撤回待審申請。 */
export function RequestsPanel({ state, reload, onChanged }: Readonly<RequestsPanelProps>) {
  const messageOf = useApiErrorMessage()
  const [busyId, setBusyId] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function cancel(id: number) {
    if (busyId !== null) return
    setBusyId(id)
    setError(null)
    try {
      await cancelRequest(id)
    } catch (e) {
      setError(messageOf(e))
    }
    await reload() // 不論結果如何都重新取得真實狀態
    onChanged()
    setBusyId(null)
  }

  return (
    <section className="card" aria-labelledby="my-requests-title">
      <h2 id="my-requests-title">我的申請</h2>
      <p className="form-error" role="alert">
        {error}
      </p>
      {state.kind === 'loading' && <p className="muted">載入中…</p>}
      {state.kind === 'error' && (
        <div>
          <p className="form-error" role="alert">
            無法載入申請：{state.message}
          </p>
          <button type="button" className="btn" onClick={() => void reload()}>
            重試
          </button>
        </div>
      )}
      {state.kind === 'ready' && state.requests.length === 0 && <p className="muted">目前沒有申請。</p>}
      {state.kind === 'ready' && state.requests.length > 0 && (
        <ul className="request-list" data-testid="my-requests">
          {state.requests.map((r) => (
            <RequestItem key={r.id} request={r} busy={busyId === r.id} onCancel={() => void cancel(r.id)} />
          ))}
        </ul>
      )}
    </section>
  )
}

function RequestItem({ request, busy, onCancel }: Readonly<{ request: AttendanceRequest; busy: boolean; onCancel: () => void }>) {
  return (
    <li className="request-item" data-status={request.status} data-kind={request.kind}>
      <div className="request-item__head">
        <strong>{REQUEST_KIND_LABEL[request.kind]}</strong>
        <span>{formatWorkDate(request.workDate)}</span>
        <span className={`status-chip status-chip--req-${request.status.toLowerCase()}`}>{REQUEST_STATUS_LABEL[request.status]}</span>
      </div>
      <p className="request-item__reason">原因：{request.reason}</p>
      {request.type === 'CORRECT_TIME' && (
        <p className="small">
          原時間：{describeTimes(request.original)}　→　申請時間：{describeTimes(request.requested)}
        </p>
      )}
      {request.status === 'PENDING' && request.stale && (
        <p className="banner banner--warn" role="status">
          這筆申請已過時：出勤資料在你提出申請之後有變動，核准時會被拒絕。請撤回後依目前資料重新提出。
        </p>
      )}
      {request.status === 'REJECTED' && <p className="notice notice--error">已退回（{request.reviewerName}）：{request.reviewComment}</p>}
      {request.status === 'APPROVED' && <p className="notice notice--success">已核准（{request.reviewerName}）{request.reviewComment ? `：${request.reviewComment}` : ''}</p>}
      {request.status === 'PENDING' && (
        <button type="button" className="btn" onClick={onCancel} disabled={busy}>
          {busy ? '撤回中…' : '撤回申請'}
        </button>
      )}
    </li>
  )
}
