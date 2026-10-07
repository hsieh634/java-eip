import { useEffect, useState, type FormEvent } from 'react'
import { fetchRecords, type AttendanceRecord } from '../api/attendance'
import { submitRequest } from '../api/attendanceRequests'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'
import { formatTime, submittedTime, taipeiToday, toTimeInputValue } from '../lib/time'

interface CorrectionFormProps {
  initialDate?: string
  initialRecordId?: number
  onSubmitted: () => void
  onCancel: () => void
}

type Target = { kind: 'choose'; invalidId?: number } | { kind: 'new' } | { kind: 'record'; record: AttendanceRecord }
type Original = { kind: 'loading' } | { kind: 'ready'; target: Target; records: AttendanceRecord[] } | { kind: 'error'; message: string }

/**
 * 申請補登／更正時間。
 * 每次選擇日期都向後端查詢「該日目前的有效紀錄」來預填，並清楚區分「原時間」與「申請時間」——
 * 不使用畫面上可能已過期的列表資料。沒有紀錄的日期也能提出（漏打卡）。
 * 送出時附上查到的紀錄 ID＋版本（快照）；查詢之後資料被更正、作廢或替換，後端會拒絕，請重新選擇日期再提出。
 * 今日誤按下班建議用今日卡片的「申請撤銷下班打卡」。員工只是提出「希望值」，核准前不會改動正式出勤。
 */
export function CorrectionForm({ initialDate, initialRecordId, onSubmitted, onCancel }: Readonly<CorrectionFormProps>) {
  const messageOf = useApiErrorMessage()
  const today = taipeiToday()
  const [workDate, setWorkDate] = useState(initialDate ?? today)
  const [original, setOriginal] = useState<Original>({ kind: 'loading' })
  const [clockIn, setClockIn] = useState('')
  const [clockOut, setClockOut] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [dateChanged, setDateChanged] = useState(false)

  useEffect(() => {
    if (!workDate) return
    let active = true
    fetchRecords({ from: workDate, to: workDate }).then(
      (list) => {
        if (!active) return
        const records = list.records.filter(r => r.status !== 'VOIDED')
        // 指定入口不能因為查不到而換目標；換過日期後也必須由使用者明選。
        const record = initialRecordId !== undefined
          ? (!dateChanged ? records.find(r => r.id === initialRecordId) : null)
          : (records.length === 1 ? records[0] : null)
        const target: Target = record ? { kind: 'record', record }
          : initialRecordId !== undefined
            ? { kind: 'choose', invalidId: dateChanged ? undefined : initialRecordId }
            : records.length ? { kind: 'choose' } : { kind: 'new' }
        setOriginal({ kind: 'ready', target, records })
        setClockIn(toTimeInputValue(record?.clockInAt ?? null)) // 依該日目前的資料預填
        setClockOut(toTimeInputValue(record?.clockOutAt ?? null))
      },
      (e: unknown) => active && setOriginal({ kind: 'error', message: messageOf(e) }),
    )
    return () => {
      active = false
    }
  }, [workDate, messageOf, initialRecordId, dateChanged])

  function changeDate(date: string) {
    if (date === workDate || submitting) return
    setOriginal({ kind: 'loading' })
    setClockIn('')
    setClockOut('')
    setError(null)
    setDateChanged(true)
    setWorkDate(date)
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (submitting || original.kind === 'loading') return
    if (original.kind !== 'ready') return setError('無法取得這一天目前的資料，請重新選擇日期後再試。')
    if (original.target.kind === 'choose') return setError('請明確選擇要更正的時段，或選擇補登新時段。')
    // 前端先擋常見錯誤；後端仍會完整驗證（含「不得是未來的時間」）
    if (!workDate || workDate > today) return setError('請選擇今天或以前的日期。')
    if (!clockIn) return setError('請填寫上班時間。')
    if (clockOut && clockOut < clockIn) return setError('下班時間不能早於上班時間。')
    if (!reason.trim()) return setError('請填寫原因。')
    setError(null)
    setSubmitting(true)
    const record = original.target.kind === 'record' ? original.target.record : null
    try {
      await submitRequest({
        type: 'CORRECT_TIME',
        workDate,
        // 沒改動的預填值送出完整精度的原值（避免小數秒被截斷而變成「有改」）
        clockInTime: submittedTime(clockIn, record?.clockInAt ?? null),
        clockOutTime: clockOut ? submittedTime(clockOut, record?.clockOutAt ?? null) : null,
        reason: reason.trim(),
        // 快照：明選的紀錄；新補登不帶目標。資料在這之後變動 → 後端 409 RECORD_CHANGED
        expectedAttendanceId: record?.id ?? null,
        expectedVersion: record?.version ?? null,
      })
      onSubmitted()
    } catch (e) {
      setError(messageOf(e))
      setSubmitting(false)
    }
  }

  let originalText = '載入目前資料中…'
  if (original.kind === 'error') originalText = `無法取得目前資料：${original.message}`
  if (original.kind === 'ready') {
    const target = original.target
    originalText = target.kind === 'record'
      ? `${formatTime(target.record.clockInAt)} ～ ${formatTime(target.record.clockOutAt)}`
      : target.kind === 'choose'
        ? target.invalidId !== undefined
          ? `原選擇的時段 #${target.invalidId} 已失效，請重新選擇要更正的時段或補登。`
          : '（請選擇要更正的時段，或明確選擇補登缺少的新時段）'
        : original.records.length
          ? `（補登缺少的新時段；這一天另有 ${original.records.length} 組有效出勤，不修改其他組）`
          : '（這一天沒有出勤紀錄；補登缺少的新時段）'
  }

  return (
    <form className="correction-form" onSubmit={handleSubmit} noValidate>
      <h3>申請更正／補登時間</h3>
      {original.kind === 'ready' && <label>目標時段
        <select id="corr-target" disabled={submitting} value={original.target.kind === 'record' ? String(original.target.record.id) : original.target.kind} onChange={e => {
          if (submitting) return
          const value = e.target.value
          const record = original.records.find(r => String(r.id) === value) ?? null
          const target: Target = record ? { kind: 'record', record } : value === 'new' ? { kind: 'new' } : { kind: 'choose' }
          setOriginal({ ...original, target }); setError(null)
          setClockIn(toTimeInputValue(record?.clockInAt ?? null)); setClockOut(toTimeInputValue(record?.clockOutAt ?? null))
        }}>
          <option value="choose" disabled>請選擇目標…</option>
          <option value="new">補登缺少的新時段（不修改其他組）</option>
          {original.records.map(r => <option key={r.id} value={r.id}>#{r.id} {formatTime(r.clockInAt)} ～ {formatTime(r.clockOutAt)}（版本 {r.version}）</option>)}
        </select>
      </label>}
      <div className="form-grid">
        <label htmlFor="corr-date">業務日期</label>
        <input id="corr-date" type="date" max={today} value={workDate} disabled={submitting} onChange={(e) => changeDate(e.target.value)} />
        <label htmlFor="corr-in">上班時間</label>
        <input id="corr-in" type="time" step={1} value={clockIn} onChange={(e) => setClockIn(e.target.value)} />
        <label htmlFor="corr-out">下班時間（可留空）</label>
        <input id="corr-out" type="time" step={1} value={clockOut} onChange={(e) => setClockOut(e.target.value)} />
      </div>

      <dl className="compare">
        <dt>原時間</dt>
        <dd data-testid="corr-original" data-state={original.kind}>
          {originalText}
        </dd>
        <dt>申請時間</dt>
        <dd data-testid="corr-requested">
          {clockIn || '—'} ～ {clockOut || '—（無下班時間）'}
        </dd>
      </dl>
      <p className="muted small">時間以台北時區輸入。同日可有多組但不得重疊，每天最多一筆待審更正；不支援跨日與未來時間，核准前正式出勤不會改變。</p>

      <label htmlFor="corr-reason">原因（必填）</label>
      <textarea id="corr-reason" rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="例如：忘記打卡，實際 09:00 到班" />
      <p className="form-error" role="alert">
        {error}
      </p>
      <div className="actions">
        <button type="submit" className="btn btn--primary" disabled={submitting || original.kind !== 'ready' || original.target.kind === 'choose'}>
          {submitting ? '送出中…' : '送出申請'}
        </button>
        <button type="button" className="btn" onClick={onCancel} disabled={submitting}>
          取消
        </button>
      </div>
    </form>
  )
}
