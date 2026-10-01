import { useEffect, useState, type FormEvent } from 'react'
import { fetchRecords, type AttendanceRecord } from '../api/attendance'
import { submitRequest } from '../api/attendanceRequests'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'
import { formatTime, taipeiToday, toTimeInputValue } from '../lib/time'

interface CorrectionFormProps {
  initialDate?: string
  onSubmitted: () => void
  onCancel: () => void
}

type Original = { kind: 'loading' } | { kind: 'ready'; record: AttendanceRecord | null } | { kind: 'error'; message: string }

/**
 * 申請補登／更正時間。
 * 每次選擇日期都向後端查詢「該日目前的有效紀錄」來預填，並清楚區分「原時間」與「申請時間」——
 * 不使用畫面上可能已過期的列表資料。沒有紀錄的日期也能提出（漏打卡）。
 * 清空下班時間＝申請撤銷下班（誤按下班）。員工只是提出「希望值」，核准前不會改動正式出勤。
 */
export function CorrectionForm({ initialDate, onSubmitted, onCancel }: Readonly<CorrectionFormProps>) {
  const messageOf = useApiErrorMessage()
  const today = taipeiToday()
  const [workDate, setWorkDate] = useState(initialDate ?? today)
  const [original, setOriginal] = useState<Original>({ kind: 'loading' })
  const [clockIn, setClockIn] = useState('')
  const [clockOut, setClockOut] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!workDate) return
    let active = true
    fetchRecords({ from: workDate, to: workDate }).then(
      (list) => {
        if (!active) return
        const record = list.records.find((r) => r.status !== 'VOIDED') ?? null
        setOriginal({ kind: 'ready', record })
        setClockIn(toTimeInputValue(record?.clockInAt ?? null)) // 依該日目前的資料預填
        setClockOut(toTimeInputValue(record?.clockOutAt ?? null))
      },
      (e: unknown) => active && setOriginal({ kind: 'error', message: messageOf(e) }),
    )
    return () => {
      active = false
    }
  }, [workDate, messageOf])

  function changeDate(date: string) {
    setOriginal({ kind: 'loading' })
    setWorkDate(date)
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (submitting || original.kind === 'loading') return
    // 前端先擋常見錯誤；後端仍會完整驗證（含「不得是未來的時間」）
    if (!workDate || workDate > today) return setError('請選擇今天或以前的日期。')
    if (!clockIn) return setError('請填寫上班時間。')
    if (clockOut && clockOut < clockIn) return setError('下班時間不能早於上班時間。')
    if (!reason.trim()) return setError('請填寫原因。')
    setError(null)
    setSubmitting(true)
    try {
      await submitRequest({ type: 'CORRECT_TIME', workDate, clockInTime: clockIn, clockOutTime: clockOut || null, reason: reason.trim() })
      onSubmitted()
    } catch (e) {
      setError(messageOf(e))
      setSubmitting(false)
    }
  }

  let originalText = '載入目前資料中…'
  if (original.kind === 'error') originalText = `無法取得目前資料：${original.message}`
  if (original.kind === 'ready') {
    originalText = original.record
      ? `${formatTime(original.record.clockInAt)} ～ ${formatTime(original.record.clockOutAt)}`
      : '（這一天沒有出勤紀錄）'
  }

  return (
    <form className="correction-form" onSubmit={handleSubmit} noValidate>
      <h3>申請更正／補登</h3>
      <div className="form-grid">
        <label htmlFor="corr-date">業務日期</label>
        <input id="corr-date" type="date" max={today} value={workDate} onChange={(e) => changeDate(e.target.value)} />
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
      <p className="muted small">時間以台北時區輸入。本版本僅支援同一天的白班，不支援未來時間；核准前正式出勤不會改變。</p>

      <label htmlFor="corr-reason">原因（必填）</label>
      <textarea id="corr-reason" rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="例如：忘記打卡，實際 09:00 到班" />
      <p className="form-error" role="alert">
        {error}
      </p>
      <div className="actions">
        <button type="submit" className="btn btn--primary" disabled={submitting || original.kind === 'loading'}>
          {submitting ? '送出中…' : '送出申請'}
        </button>
        <button type="button" className="btn" onClick={onCancel} disabled={submitting}>
          取消
        </button>
      </div>
    </form>
  )
}
