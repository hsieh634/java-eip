import { useRef, useState, type FormEvent } from 'react'
import { fetchRevisionTarget, submitRevision, type LeaveDetail, type RevisionOperation, type RevisionSubmission } from '../api/leave'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'
import { useLoad } from '../lib/useLoad'
import { useLifetime } from '../lib/useLifetime'
import { newRequestId } from '../lib/requestId'
import { canRevise, checkRevisionResult } from './revisionOutcome'
import { isUnconfirmed } from './submitOutcome'
import { END_TIMES, LEAVE_STATUS_LABEL, LEAVE_TYPE_LABEL, minutesOfDay, REVISION_LABEL, START_TIMES } from './labels'
import { LeaveDocumentSummary } from './LeaveDocumentSummary'


interface Props {
  sourceId: number; operation: RevisionOperation; locked: boolean
  onLock: (value: boolean) => void; onDone: (message: string) => void
  onCancel: () => void; onStale: () => void
}
export function LeaveRevisionDialog(props: Readonly<Props>) {
  const [seq, setSeq] = useState(0)
  const load = useLoad(() => fetchRevisionTarget(props.sourceId), 'revision-target:' + props.sourceId + ':' + props.operation + ':' + seq)
  return <div className="modal-backdrop"><div className="modal modal--wide" role="dialog" aria-modal="true" data-testid="leave-revision-dialog">
    <h2>{REVISION_LABEL[props.operation]}／來源申請 #{props.sourceId}</h2>
    {load.state.kind === 'loading' && <p>讀取這筆原單最新資料中…</p>}
    {load.state.kind === 'error' && <><p className="form-error" role="alert">{load.state.message}</p><button type="button" className="btn" onClick={load.reload}>重新讀取原單</button></>}
    {load.state.kind === 'ready' && <>
      <LeaveDocumentSummary detail={load.state.data} />
      {canRevise(load.state.data, props.operation)
        ? <RevisionFields key={props.sourceId + ':' + props.operation + ':' + seq} detail={load.state.data} {...props}
            onReload={() => { if (!props.locked) setSeq(n => n + 1) }} />
        : <p className="form-error" role="alert" data-testid="revision-target-invalid">原選擇的來源 #{props.sourceId} 已失效或已有其他待審流程，不能送出；請明確重新選擇目前版本，不會改成另一單或新增請假。</p>}
    </>}
    <p className="muted small">重新整理或切頁不保存這份追蹤；回來後先查真實紀錄。本表單不會自動重送。</p>
    <button type="button" className="btn" disabled={props.locked} onClick={props.onCancel}>取消修改</button>
  </div></div>
}
function RevisionFields({ detail, onReload, ...props }: Readonly<Props & { detail: LeaveDetail; onReload: () => void }>) {
  const messageOf = useApiErrorMessage(), lifetime = useLifetime()
  const r = detail.request, d = detail.document!
  const [requestId] = useState(newRequestId)
  const [leaveType, setType] = useState(r.leaveType)
  const [workDate, setDate] = useState(r.workDate)
  const [startTime, setStart] = useState(r.startTime), [endTime, setEnd] = useState(r.endTime)
  const [reason, setReason] = useState('')
  const [phase, setPhase] = useState<'editing' | 'submitting' | 'unknown'>('editing')
  const [pending, setPending] = useState<RevisionSubmission | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [warning, setWarning] = useState(false)
  const sending = useRef(false)
  const shown = pending ?? { leaveType, workDate, startTime, endTime, reason }
  const locked = phase !== 'editing', cancellation = props.operation === 'CANCEL_ALL' || props.operation === 'CANCEL_PART'
  async function send(body: RevisionSubmission, retrying: boolean) {
    if (sending.current) return
    sending.current = true; props.onLock(true); setPhase('submitting'); setError(null); setWarning(false)
    const life = lifetime.current
    try {
      const result = checkRevisionResult(await submitRevision(props.sourceId, body), props.sourceId, body, d.id)
      if (!life.alive) return
      props.onLock(false)
      props.onDone(result.replayed ? '原提交已建立：申請 #' + result.id + '，' + LEAVE_STATUS_LABEL[result.status] + '；沒有重複建立。'
        : '已送出' + REVISION_LABEL[props.operation] + '申請 #' + result.id + '，等待審核；原有效內容在核准前不變。')
    } catch (e) {
      if (!life.alive) return
      sending.current = false
      if (retrying || isUnconfirmed(e)) {
        setPending(body); setPhase('unknown')
        setError('原提交結果仍未確認，欄位與來源保持鎖定。' + (isUnconfirmed(e) ? '回應無法確認。' : messageOf(e)) +
          '請重送原內容確認；本次被拒絕不能證明原提交不存在。')
        props.onStale()
      } else {
        props.onLock(false); setPhase('editing'); setError(messageOf(e))
      }
    }
  }
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (sending.current) return
    if (pending) return send(pending, true)
    if (phase !== 'editing' || !canRevise(detail, props.operation)) return
    if (!reason.trim()) return setError('請填寫本次修改／銷假的原因。')
    if (minutesOfDay(endTime) <= minutesOfDay(startTime)) return setError('結束時間必須晚於開始時間。整段銷假請保留原時間。')
    await send({ requestId, operation: props.operation, expectedSourceVersion: r.version,
      expectedEffectiveRequestId: d.effective?.id ?? null, expectedEffectiveVersion: d.effectiveVersion,
      leaveType, workDate, startTime, endTime, reason: reason.trim() }, false)
  }
  return <form className="correction-form" noValidate onSubmit={submit} data-testid="leave-revision-form" data-phase={phase} data-source-id={r.id} data-unconfirmed={pending !== null || undefined}>
    <p className="small">來源 #{r.id}／狀態版本 {r.version}；有效版本 {d.effectiveVersion}。送出後建立新內容版本，舊提交與退回原因保留。</p>
    {cancellation && <p className="banner banner--warn">{props.operation === 'CANCEL_ALL'
      ? '整段銷假保留原時間與分鐘資料，核准後才停止有效請假。'
      : '以下填「銷假後保留的有效時段」；只能縮短開始或結束，不能同時改兩側、增加時數、換日期或假別。'}</p>}
    <fieldset className="form-fieldset" disabled={locked}>
      <div className="form-grid">
        <label htmlFor="rev-type">假別</label><select id="rev-type" value={shown.leaveType} disabled={cancellation} onChange={e => { if (!locked && !cancellation) setType(e.target.value as typeof leaveType) }}>
          {Object.entries(LEAVE_TYPE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select>
        <label htmlFor="rev-date">日期</label><input id="rev-date" type="date" value={shown.workDate} disabled={cancellation} onChange={e => { if (!locked && !cancellation) setDate(e.target.value) }} />
        <label htmlFor="rev-start">{cancellation ? '保留時段開始' : '開始'}</label><select id="rev-start" value={shown.startTime} disabled={props.operation === 'CANCEL_ALL'} onChange={e => { if (!locked && props.operation !== 'CANCEL_ALL') setStart(e.target.value) }}>
          {START_TIMES.map(t => <option key={t} value={t}>{t}</option>)}</select>
        <label htmlFor="rev-end">{cancellation ? '保留時段結束' : '結束'}</label><select id="rev-end" value={shown.endTime} disabled={props.operation === 'CANCEL_ALL'} onChange={e => { if (!locked && props.operation !== 'CANCEL_ALL') setEnd(e.target.value) }}>
          {END_TIMES.map(t => <option key={t} value={t}>{t}</option>)}</select>
      </div>
      <label htmlFor="rev-reason">本次原因（必填）</label><textarea id="rev-reason" rows={3} maxLength={500} value={shown.reason} onChange={e => { if (!locked) setReason(e.target.value) }} />
    </fieldset>
    <p className="form-error" role="alert" data-testid="revision-error">{error}</p>
    {warning && <div className="banner banner--warn" data-testid="revision-abandon-warning">
      <p>原提交可能已建立，放棄追蹤不會撤回申請；請先查真實清單，避免重複提出。</p>
      <button type="button" className="btn btn--danger" onClick={() => { props.onLock(false); props.onStale(); props.onCancel() }}>確定放棄追蹤</button>
      <button type="button" className="btn" onClick={() => setWarning(false)}>繼續確認原提交</button>
    </div>}
    <div className="actions">
      <button type="submit" className="btn btn--primary" disabled={phase === 'submitting'}>{phase === 'submitting' ? '送出中…' : phase === 'unknown' ? '重新送出原內容' : '送出新版本申請'}</button>
      {phase === 'unknown' && !warning && <button type="button" className="btn" onClick={() => setWarning(true)}>放棄追蹤</button>}
      {phase === 'editing' && error && <button type="button" className="btn" onClick={onReload}>重新讀取此來源單</button>}
    </div>
  </form>
}
