import type { AttendanceStatus } from '../api/attendance'
import { useTodayAttendance } from '../attendance/useTodayAttendance'
import { formatTime, formatWorkDate } from '../lib/time'

const STATUS_LABEL: Record<AttendanceStatus, string> = {
  NOT_STARTED: '尚未上班',
  WORKING: '工作中',
  COMPLETED: '今日已完成',
  INCOMPLETE: '未完成',
}

/** 今日打卡卡片（首頁與出勤頁共用）。onChanged：打卡動作結束後通知外層（例如重新載入紀錄列表）。 */
export function TodayAttendanceCard({ onChanged }: Readonly<{ onChanged?: () => void }>) {
  const { state, busy, notice, retry, clockIn, clockOut } = useTodayAttendance(onChanged)

  return (
    <section className="card card--attendance" aria-labelledby="card-today">
      <h2 id="card-today">今日打卡</h2>

      {state.kind === 'loading' && <p className="muted">載入中…</p>}

      {state.kind === 'error' && (
        <div>
          <p className="form-error" role="alert">
            無法取得今日打卡狀態：{state.message}
          </p>
          <button type="button" className="btn" onClick={retry}>
            重試
          </button>
        </div>
      )}

      {state.kind === 'ready' && (
        <>
          <p className={`status status--${state.data.status.toLowerCase()}`} data-testid="today-status">
            {STATUS_LABEL[state.data.status]}
          </p>
          <dl className="info">
            <dt>日期</dt>
            <dd>{formatWorkDate(state.data.workDate)}</dd>
            <dt>上班時間</dt>
            <dd data-testid="clock-in-time">{formatTime(state.data.clockInAt)}</dd>
            <dt>下班時間</dt>
            <dd data-testid="clock-out-time">{formatTime(state.data.clockOutAt)}</dd>
          </dl>

          {state.data.unfinishedBefore && (
            <p className="banner banner--warn" role="status">
              你在 {formatWorkDate(state.data.unfinishedBefore)} 的出勤紀錄尚未下班。系統不會自動補下班，目前版本也不支援補登。
            </p>
          )}

          <div className="actions">
            <button
              type="button"
              className="btn btn--primary btn--large"
              onClick={() => void clockIn()}
              disabled={busy !== null || state.data.status !== 'NOT_STARTED'}
            >
              {busy === 'in' ? '打卡中…' : '上班打卡'}
            </button>
            <button
              type="button"
              className="btn btn--large"
              onClick={() => void clockOut()}
              disabled={busy !== null || state.data.status !== 'WORKING'}
            >
              {busy === 'out' ? '打卡中…' : '下班打卡'}
            </button>
          </div>
        </>
      )}

      {notice && (
        <p className={notice.kind === 'success' ? 'notice notice--success' : 'notice notice--error'} role="status">
          {notice.text}
        </p>
      )}

      <p className="muted small">本版本採固定白班、每日一組上下班，不支援跨日班與補登。時間以台北時區顯示。</p>
    </section>
  )
}
