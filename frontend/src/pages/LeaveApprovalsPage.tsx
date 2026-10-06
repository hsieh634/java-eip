import { useState } from 'react'
import type { CurrentUser } from '../api/auth'
import { ApiError } from '../api/http'
import { approveLeave, fetchApproval, fetchApprovals, rejectLeave, type LeaveDetail, type LeaveRequest } from '../api/leave'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'
import { LeaveDetailDialog } from '../leave/LeaveDetailDialog'
import { LeaveList } from '../leave/LeaveList'
import { describe } from '../leave/labels'
import { useLifetime } from '../lib/useLifetime'

/** 主管「請假審核」：只列出指派給自己的申請；核准、退回（原因必填）。 */
export function LeaveApprovalsPage({ user }: Readonly<{ user: CurrentUser }>) {
  const [seq, setSeq] = useState(0)
  const [open, setOpen] = useState<LeaveRequest | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  if (user.role === 'EMPLOYEE') {
    return (
      <>
        <h1>請假審核</h1>
        <p className="form-error" role="alert">
          只有主管或管理員可以審核請假。
        </p>
      </>
    )
  }
  const refresh = () => setSeq((s) => s + 1)
  return (
    <>
      <h1>請假審核</h1>
      <p className="muted">
        這裡只會列出<strong>指派給你</strong>的請假申請。被設為主管不會讓你看到其他人的出勤或工時；若申請被管理員改派給別人，你就不再能查看或處理。
      </p>
      {notice && (
        <p className="notice notice--success" role="status" data-testid="approval-notice">
          {notice}
        </p>
      )}
      <section className="card card--records" aria-labelledby="approvals-title">
        <div className="card__title-row">
          <h2 id="approvals-title">指派給我的申請</h2>
          <button type="button" className="btn btn--small" onClick={refresh}>
            重新整理
          </button>
        </div>
        <LeaveList
          loadKey="approvals"
          load={fetchApprovals}
          initialStatus="PENDING"
          refreshSeq={seq}
          showApplicant
          emptyText="目前沒有符合條件的申請。"
          onOpen={setOpen}
          testId="approvals"
        />
      </section>
      {open && (
        <LeaveDetailDialog
          title={`審核請假 #${open.id}`}
          loadKey={`approval:${open.id}:${seq}`}
          loader={() => fetchApproval(open.id)}
          onClose={() => setOpen(null)}
          actions={(detail, reload) =>
            detail.request.status === 'PENDING' ? (
              <DecisionActions
                detail={detail}
                onReload={reload}
                onDone={(message) => {
                  setNotice(message)
                  refresh()
                }}
              />
            ) : (
              <p className="muted small">這筆申請已結案。</p>
            )
          }
        />
      )}
    </>
  )
}

type Mode = 'idle' | 'approve' | 'reject'

/** 核准／退回：確認畫面列出申請人、假別、日期、時間、原因；狀態或版本已變更時不假裝成功。 */
function DecisionActions({ detail, onReload, onDone }: Readonly<{ detail: LeaveDetail; onReload: () => void; onDone: (message: string) => void }>) {
  const messageOf = useApiErrorMessage()
  const lifetime = useLifetime()
  const [mode, setMode] = useState<Mode>('idle')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [stale, setStale] = useState(false)
  const r = detail.request

  async function confirm() {
    if (busy) return
    if (mode === 'reject' && !reason.trim()) return setError('退回原因必填。')
    setBusy(true)
    setError(null)
    const life = lifetime.current
    try {
      if (mode === 'approve') await approveLeave(r.id, r.version)
      else await rejectLeave(r.id, r.version, reason.trim())
      if (!life.alive) return
      setBusy(false)
      setMode('idle')
      onDone(`${mode === 'approve' ? '已核准' : '已退回'} ${r.applicant.displayName} 的請假申請 #${r.id}。`)
      onReload()
    } catch (e) {
      if (!life.alive) return
      setBusy(false)
      const changed = e instanceof ApiError && (e.code === 'LEAVE_CHANGED' || e.code === 'LEAVE_NOT_PENDING' || e.code === 'LEAVE_NOT_FOUND')
      setStale(changed)
      setError(
        e instanceof ApiError && e.code === 'LEAVE_NOT_FOUND'
          ? '這筆申請已不再指派給你（可能已被管理員改派），沒有做任何處理。'
          : messageOf(e),
      )
    }
  }

  if (mode === 'idle') {
    return (
      <div className="actions">
        <button type="button" className="btn btn--primary" onClick={() => setMode('approve')}>
          核准
        </button>
        <button type="button" className="btn btn--danger" onClick={() => setMode('reject')}>
          退回
        </button>
      </div>
    )
  }
  return (
    <div className="banner banner--warn" data-testid="decision-confirm" data-mode={mode}>
      <p>
        確認{mode === 'approve' ? '核准' : '退回'}：{r.applicant.displayName}（{r.applicant.employeeNo}）{describe(r)}
      </p>
      <p className="small">原因：{r.reason}</p>
      {mode === 'reject' && (
        <>
          <label htmlFor="reject-reason">退回原因（必填，申請人看得到）</label>
          <textarea id="reject-reason" rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
        </>
      )}
      <p className="form-error" role="alert">
        {error}
      </p>
      <div className="actions">
        {stale ? (
          <button type="button" className="btn" onClick={onReload}>
            重新載入
          </button>
        ) : (
          <button type="button" className={mode === 'approve' ? 'btn btn--primary' : 'btn btn--danger'} onClick={() => void confirm()} disabled={busy}>
            {busy ? '處理中…' : mode === 'approve' ? '確認核准' : '確認退回'}
          </button>
        )}
        <button type="button" className="btn" onClick={() => setMode('idle')} disabled={busy}>
          取消
        </button>
      </div>
    </div>
  )
}
