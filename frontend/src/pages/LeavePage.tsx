import { useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../api/http'
import {
  fetchApproverPreview,
  fetchMyLeave,
  fetchMyLeaves,
  submitLeave,
  withdrawLeave,
  type LeaveDetail,
  type LeaveRequest,
  type LeaveSubmission,
  type LeaveType,
} from '../api/leave'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'
import { LeaveDetailDialog } from '../leave/LeaveDetailDialog'
import { LeaveList } from '../leave/LeaveList'
import { APPROVER_SOURCE_LABEL, durationText, END_TIMES, LEAVE_TYPE_LABEL, minutesOfDay, START_TIMES } from '../leave/labels'
import { newRequestId } from '../lib/requestId'
import { taipeiToday } from '../lib/time'
import { useLifetime } from '../lib/useLifetime'
import { useLoad } from '../lib/useLoad'

/** 員工「請假」：送出、預計審核人、本人清單、詳情與歷程、撤回待審申請。 */
export function LeavePage() {
  const [seq, setSeq] = useState(0)
  const [formKey, setFormKey] = useState(0)
  const [notice, setNotice] = useState<string | null>(null)
  const [open, setOpen] = useState<LeaveRequest | null>(null)
  const refreshList = () => setSeq((s) => s + 1)

  return (
    <>
      <h1>請假</h1>
      <p className="muted">
        送出後由審核人核准或退回。假別與規則是 STMAX 示範規則（沒有額度、年資或法規計算）。每筆申請是一天內的一段時間，以半小時為單位；
        跨日請分開申請。<strong>已核准的申請不能修改或撤回</strong>（本階段沒有取消流程）。
      </p>
      {notice && (
        <p className="notice notice--success" role="status" data-testid="leave-notice">
          {notice}
        </p>
      )}
      <div className="stack">
        <section className="card" aria-labelledby="leave-form-title">
          <h2 id="leave-form-title">送出請假</h2>
          <ApproverPreviewBox refreshSeq={seq} />
          <LeaveForm
            key={formKey}
            onDone={(message) => {
              setNotice(message)
              setFormKey((k) => k + 1) // 新表單、新的 requestId
              refreshList()
            }}
            onListStale={refreshList}
            onStart={() => setNotice(null)}
            onReset={() => setFormKey((k) => k + 1)}
          />
        </section>
        <section className="card card--records" aria-labelledby="my-leave-title">
          <div className="card__title-row">
            <h2 id="my-leave-title">我的請假</h2>
            <button type="button" className="btn btn--small" onClick={refreshList}>
              重新整理
            </button>
          </div>
          <LeaveList
            loadKey="my-leaves"
            load={fetchMyLeaves}
            refreshSeq={seq}
            emptyText="沒有請假申請。"
            onOpen={setOpen}
            testId="my-leaves"
          />
        </section>
      </div>
      {open && (
        <LeaveDetailDialog
          title={`請假詳情 #${open.id}`}
          loadKey={`my-leave:${open.id}:${seq}`}
          loader={() => fetchMyLeave(open.id)}
          onClose={() => setOpen(null)}
          actions={(detail, reload) =>
            detail.request.status === 'PENDING' ? (
              <WithdrawAction
                detail={detail}
                onDone={() => {
                  setNotice(`已撤回請假申請 #${detail.request.id}。`)
                  refreshList()
                }}
                onReload={reload}
              />
            ) : (
              <p className="muted small">這筆申請已結案，不能再撤回或修改。</p>
            )
          }
        />
      )}
    </>
  )
}

function ApproverPreviewBox({ refreshSeq }: Readonly<{ refreshSeq: number }>) {
  const preview = useLoad(() => fetchApproverPreview(), `approver-preview:${refreshSeq}`)
  if (preview.state.kind === 'loading') return <p className="muted small">確認預計審核人中…</p>
  if (preview.state.kind === 'error') {
    return (
      <p className="form-error small" role="alert">
        無法取得預計審核人：{preview.state.message}{' '}
        <button type="button" className="btn btn--small" onClick={preview.reload}>
          重試
        </button>
      </p>
    )
  }
  const p = preview.state.data
  return (
    <div className={p.approver ? 'banner banner--info' : 'banner banner--warn'} data-testid="approver-preview" data-source={p.source}>
      {p.approver ? (
        <p>
          預計審核人：<strong>{p.approver.displayName}</strong>（{APPROVER_SOURCE_LABEL[p.source]}）。這只是提示，送出時系統會重新確認。
        </p>
      ) : (
        <p>{p.problem}</p>
      )}
    </div>
  )
}

/**
 * 表單的三個階段（沿用工時表單 T04 第 2 輪的規則）：
 * - editing：可以編輯。
 * - submitting：請求進行中，表單鎖定。
 * - unknown：結果不明（網路中斷、回應遺失、5xx）。表單鎖定在剛才的內容，requestId 與它綁在一起；
 *   只能「重新送出原內容」，或看過警告後「放棄追蹤」。
 * 「原提交是否尚未確認」由未確認快照（pending）是否存在決定；重試被拒絕（403、409…）不能證明原提交不存在，快照保留。
 * 第一次送出就被明確拒絕（400／409）代表沒有建立任何東西 → 回到 editing 讓使用者修改。
 * 401 走既有的登入失效流程（回登入頁、表單卸載），不會以別的帳號重送。不跨重新整理保存。
 */
type Phase = 'editing' | 'submitting' | 'unknown'

interface LeaveFormProps {
  onDone: (message: string) => void
  onListStale: () => void
  onStart: () => void
  /** 明確放棄追蹤：外層換 key → 新表單、新的 requestId */
  onReset: () => void
}

function LeaveForm({ onDone, onListStale, onStart, onReset }: Readonly<LeaveFormProps>) {
  const messageOf = useApiErrorMessage()
  const lifetime = useLifetime()
  const [requestId] = useState(newRequestId)
  const [leaveType, setLeaveType] = useState<LeaveType>('PERSONAL')
  const [workDate, setWorkDate] = useState(taipeiToday)
  const [startTime, setStartTime] = useState('09:00')
  const [endTime, setEndTime] = useState('12:00')
  const [reason, setReason] = useState('')
  const [phase, setPhase] = useState<Phase>('editing')
  const [pending, setPending] = useState<LeaveSubmission | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirmAbandon, setConfirmAbandon] = useState(false)
  const submitting = useRef(false)
  const locked = phase !== 'editing'
  const shown = pending ?? { leaveType, workDate, startTime, endTime, reason }
  const minutes = minutesOfDay(shown.endTime) - minutesOfDay(shown.startTime)

  const edit = (apply: () => void) => {
    if (!locked) apply()
  }

  async function send(values: LeaveSubmission, retrying: boolean) {
    submitting.current = true
    setPhase('submitting')
    setError(null)
    setConfirmAbandon(false)
    onStart()
    const life = lifetime.current
    try {
      const result = await submitLeave({ ...values, requestId })
      if (!life.alive) return // 已切頁：不更新畫面（POST 已送出，無法撤回）
      onDone(
        result.replayed
          ? `原提交已經建立（申請 #${result.request.id}，${statusText(result.request)}），沒有重複新增。`
          : `已送出請假申請 #${result.request.id}，等待 ${result.request.approver.displayName} 審核。`,
      )
    } catch (e) {
      if (!life.alive) return
      submitting.current = false
      const uncertain = e instanceof ApiError && (e.status === 0 || e.status >= 500)
      if (retrying) {
        const why = uncertain ? '仍無法連線或伺服器發生錯誤' : messageOf(e)
        setPhase('unknown')
        setError(`本次重新送出沒有成功：${why}。原提交的結果仍未確認，表單維持鎖定為原內容：可以再按「重新送出原內容」，或按「放棄追蹤，開始新的一筆」。`)
        onListStale()
        return
      }
      if (uncertain) {
        setPending(values)
        setPhase('unknown')
        setError(
          '無法確認原提交是否已建立（網路或伺服器問題），已重新查詢清單。表單已鎖定為剛才送出的內容：按「重新送出原內容」會用同一個識別碼確認，不會重複建立。',
        )
        onListStale()
        return
      }
      // 第一次送出就被明確拒絕：沒有建立任何東西，保留輸入讓使用者修改
      setPhase('editing')
      setError(messageOf(e))
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (submitting.current) return
    if (pending) {
      await send(pending, true) // 只送未確認快照，不依目前欄位重組
      return
    }
    if (!workDate) return setError('請選擇日期。')
    if (minutes <= 0) return setError('結束時間必須晚於開始時間；不支援跨午夜，跨日請分開申請。')
    if (!reason.trim()) return setError('請填寫請假原因。')
    if (reason.trim().length > 500) return setError('原因最多 500 個字。')
    await send({ leaveType, workDate, startTime, endTime, reason: reason.trim() }, false)
  }

  let submitLabel = '送出申請'
  if (phase === 'submitting') submitLabel = '送出中…'
  else if (phase === 'unknown') submitLabel = '重新送出原內容'
  return (
    <form className="correction-form" onSubmit={submit} noValidate data-testid="leave-form" data-phase={phase} data-unconfirmed={pending !== null || undefined}>
      <fieldset className="form-fieldset" disabled={locked}>
        <div className="form-grid">
          <label htmlFor="lv-type">假別</label>
          <select id="lv-type" value={shown.leaveType} onChange={(e) => edit(() => setLeaveType(e.target.value as LeaveType))}>
            {(Object.keys(LEAVE_TYPE_LABEL) as LeaveType[]).map((t) => (
              <option key={t} value={t}>
                {LEAVE_TYPE_LABEL[t]}
              </option>
            ))}
          </select>
          <label htmlFor="lv-date">日期</label>
          <input id="lv-date" type="date" value={shown.workDate} onChange={(e) => edit(() => setWorkDate(e.target.value))} />
          <label htmlFor="lv-start">開始</label>
          <select id="lv-start" value={shown.startTime} onChange={(e) => edit(() => setStartTime(e.target.value))}>
            {START_TIMES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
          <label htmlFor="lv-end">結束</label>
          <select id="lv-end" value={shown.endTime} onChange={(e) => edit(() => setEndTime(e.target.value))}>
            {END_TIMES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </div>
        <p className="muted small" data-testid="leave-duration">
          {minutes > 0 ? durationText(minutes) : '結束時間必須晚於開始時間（不支援跨午夜，跨日請分開申請）。'}
        </p>
        <label htmlFor="lv-reason">原因（必填，最多 500 字；不需要填寫診斷或醫療資訊）</label>
        <textarea id="lv-reason" rows={2} maxLength={500} value={shown.reason} onChange={(e) => edit(() => setReason(e.target.value))} />
      </fieldset>
      <p className="form-error" role="alert" data-testid="leave-error">
        {error}
      </p>
      {phase === 'unknown' && <p className="muted small">提醒：重新整理頁面後這個保護就會消失；請先查看下方「我的請假」確認上一筆是否已建立。</p>}
      {confirmAbandon && (
        <div className="banner banner--warn" role="alert" data-testid="abandon-warning">
          <p>原提交可能已經建立（只是沒有收到回應）。放棄追蹤後，請先在「我的請假」確認是否已有這筆，避免重複申請。</p>
          <div className="actions">
            <button type="button" className="btn btn--danger" onClick={onReset}>
              確定放棄並清空
            </button>
            <button type="button" className="btn" onClick={() => setConfirmAbandon(false)}>
              繼續確認原提交
            </button>
          </div>
        </div>
      )}
      <div className="actions">
        <button type="submit" className="btn btn--primary" disabled={phase === 'submitting'}>
          {submitLabel}
        </button>
        {phase === 'unknown' && !confirmAbandon && (
          <button type="button" className="btn" onClick={() => setConfirmAbandon(true)}>
            放棄追蹤，開始新的一筆
          </button>
        )}
      </div>
    </form>
  )
}

function statusText(r: LeaveRequest): string {
  return { PENDING: '待審', APPROVED: '已核准', REJECTED: '已退回', WITHDRAWN: '已撤回' }[r.status]
}

/** 撤回待審申請：先確認；版本或狀態已變更時不假裝成功，提示重新載入。 */
function WithdrawAction({ detail, onDone, onReload }: Readonly<{ detail: LeaveDetail; onDone: () => void; onReload: () => void }>) {
  const messageOf = useApiErrorMessage()
  const lifetime = useLifetime()
  const [confirming, setConfirming] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [stale, setStale] = useState(false)

  async function confirm() {
    if (busy) return
    setBusy(true)
    setError(null)
    const life = lifetime.current
    try {
      await withdrawLeave(detail.request.id, detail.request.version, reason.trim() || null)
      if (!life.alive) return
      setBusy(false)
      setConfirming(false)
      onDone()
      onReload()
    } catch (e) {
      if (!life.alive) return
      setBusy(false)
      setStale(e instanceof ApiError && (e.code === 'LEAVE_CHANGED' || e.code === 'LEAVE_NOT_PENDING'))
      setError(messageOf(e))
    }
  }

  if (!confirming) {
    return (
      <div className="actions">
        <button type="button" className="btn btn--danger" onClick={() => setConfirming(true)}>
          撤回這筆申請
        </button>
      </div>
    )
  }
  return (
    <div className="banner banner--warn" data-testid="withdraw-confirm">
      <p>撤回後這筆申請結案，不能再恢復；需要時請重新送出新的申請。撤回後時段即釋放。</p>
      <label htmlFor="withdraw-reason">撤回原因（選填）</label>
      <textarea id="withdraw-reason" rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      <p className="form-error" role="alert">
        {error}
      </p>
      <div className="actions">
        {stale ? (
          <button type="button" className="btn" onClick={onReload}>
            重新載入
          </button>
        ) : (
          <button type="button" className="btn btn--danger" onClick={() => void confirm()} disabled={busy}>
            {busy ? '處理中…' : '確認撤回'}
          </button>
        )}
        <button type="button" className="btn" onClick={() => setConfirming(false)} disabled={busy}>
          取消
        </button>
      </div>
    </div>
  )
}
