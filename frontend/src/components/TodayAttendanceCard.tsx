import { useState } from 'react'
import { cancelRequest, submitRequest } from '../api/attendanceRequests'
import { RECORD_STATUS_LABEL } from '../attendance/labels'
import { useMyRequests } from '../attendance/useMyRequests'
import { useTodayAttendance } from '../attendance/useTodayAttendance'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'
import { formatTime, formatWorkDate } from '../lib/time'
import { ReasonForm } from './ReasonForm'

/**
 * 今日打卡卡片（首頁與出勤頁共用）。
 * 已有今日紀錄時可以「申請重新打卡」：申請不會立即更動紀錄，也不會開放打卡按鈕，要等管理員核准；
 * 核准後（畫面在視窗回到前景或每 30 秒的輪詢時）會重新向後端查詢真實狀態。
 * onChanged：打卡或申請狀態改變後通知外層（例如重新載入紀錄列表）。
 */
export function TodayAttendanceCard({ onChanged }: Readonly<{ onChanged?: () => void }>) {
  const messageOf = useApiErrorMessage()
  const { state, busy, notice, retry, reload, clockIn, clockOut } = useTodayAttendance(onChanged)
  const requests = useMyRequests(() => {
    void reload() // 申請有結果（被核准或退回）→ 重新查詢今日狀態
    onChanged?.()
  })
  const [showReset, setShowReset] = useState(false)
  const [requestError, setRequestError] = useState<string | null>(null)
  const [cancelling, setCancelling] = useState(false)

  const data = state.kind === 'ready' ? state.data : null
  const todayRequests = data && requests.state.kind === 'ready' ? requests.state.requests.filter((r) => r.workDate === data.workDate) : []
  const pending = todayRequests.find((r) => r.status === 'PENDING' && r.type === 'RESET_TODAY') ?? null
  const anyPending = todayRequests.some((r) => r.status === 'PENDING')
  const lastRejected = todayRequests.find((r) => r.status === 'REJECTED') ?? null
  const canRequestReset = data !== null && data.status !== 'NOT_STARTED' && !anyPending

  async function submitReset(reason: string) {
    await submitRequest({ type: 'RESET_TODAY', reason })
    setShowReset(false)
    await requests.reload()
    onChanged?.()
  }

  async function cancelPending() {
    if (!pending || cancelling) return
    setCancelling(true)
    setRequestError(null)
    try {
      await cancelRequest(pending.id)
    } catch (e) {
      setRequestError(messageOf(e))
    }
    await requests.reload()
    setCancelling(false)
    onChanged?.()
  }

  function refreshAll() {
    void reload()
    void requests.reload()
    onChanged?.()
  }

  return (
    <section className="card card--attendance" aria-labelledby="card-today">
      <div className="card__title-row">
        <h2 id="card-today">今日打卡</h2>
        <button type="button" className="btn btn--small" onClick={refreshAll} aria-label="重新整理今日狀態">
          重新整理
        </button>
      </div>

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

      {data && (
        <>
          <p className={`status status--${data.status.toLowerCase()}`} data-testid="today-status">
            {data.status === 'COMPLETED' ? '今日已完成' : RECORD_STATUS_LABEL[data.status]}
          </p>
          <dl className="info">
            <dt>日期</dt>
            <dd data-testid="today-date">{formatWorkDate(data.workDate)}</dd>
            <dt>上班時間</dt>
            <dd data-testid="clock-in-time">{formatTime(data.clockInAt)}</dd>
            <dt>下班時間</dt>
            <dd data-testid="clock-out-time">{formatTime(data.clockOutAt)}</dd>
          </dl>

          {data.unfinishedBefore && (
            <p className="banner banner--warn" role="status">
              你在 {formatWorkDate(data.unfinishedBefore)} 的出勤紀錄尚未下班。系統不會自動補下班；可到「出勤打卡」頁申請更正／補登。
            </p>
          )}

          <div className="actions">
            <button
              type="button"
              className="btn btn--primary btn--large"
              onClick={() => void clockIn()}
              disabled={busy !== null || data.status !== 'NOT_STARTED'}
            >
              {busy === 'in' ? '打卡中…' : '上班打卡'}
            </button>
            <button
              type="button"
              className="btn btn--large"
              onClick={() => void clockOut()}
              disabled={busy !== null || data.status !== 'WORKING'}
            >
              {busy === 'out' ? '打卡中…' : '下班打卡'}
            </button>
          </div>

          {pending && (
            <div className="banner banner--info" role="status" data-testid="reset-pending">
              <p>
                已提出「重新打卡」申請，等待管理員審核{pending.stale ? '（資料已有變動，這筆申請已過時，核准會被拒絕，請撤回後重新提出）' : ''}。
                在核准之前，打卡紀錄與按鈕不會改變。
              </p>
              <p className="small">原因：{pending.reason}</p>
              <button type="button" className="btn btn--small" onClick={() => void cancelPending()} disabled={cancelling}>
                {cancelling ? '撤回中…' : '撤回申請'}
              </button>
            </div>
          )}

          {!pending && anyPending && (
            <p className="banner banner--info" role="status">
              這一天已有待審的更正申請，處理完成前無法再申請重新打卡。
            </p>
          )}

          {lastRejected && !anyPending && (
            <p className="notice notice--error" data-testid="reset-rejected">
              先前的申請已被退回：{lastRejected.reviewComment}
            </p>
          )}

          {canRequestReset && !showReset && (
            <button type="button" className="btn" onClick={() => setShowReset(true)}>
              申請重新打卡
            </button>
          )}
          {canRequestReset && showReset && (
            <ReasonForm
              label="申請重新打卡的原因（必填）"
              placeholder="例如：測試功能時誤按上下班，希望重新打卡"
              submitLabel="送出申請"
              onSubmit={submitReset}
              onCancel={() => setShowReset(false)}
            />
          )}
          <p className="form-error" role="alert">
            {requestError}
          </p>
        </>
      )}

      {notice && (
        <p className={notice.kind === 'success' ? 'notice notice--success' : 'notice notice--error'} role="status">
          {notice.text}
        </p>
      )}

      <p className="muted small">本版本採固定白班、每日一組上下班，不支援跨日班。誤按或漏打請提出申請，由管理員審核。時間以台北時區顯示。</p>
    </section>
  )
}
