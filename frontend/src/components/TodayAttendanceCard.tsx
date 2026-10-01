import { useState } from 'react'
import type { TodayAttendance } from '../api/attendance'
import { cancelRequest, submitRequest } from '../api/attendanceRequests'
import { RECORD_STATUS_LABEL, REQUEST_KIND_LABEL } from '../attendance/labels'
import { useMyRequests } from '../attendance/useMyRequests'
import { useTodayAttendance } from '../attendance/useTodayAttendance'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'
import { useAuth } from '../auth/useAuth'
import { formatTime, formatWorkDate, toPreciseTimeValue } from '../lib/time'
import { ReasonForm } from './ReasonForm'

/** 開啟中的申請表單，以及開啟當下看到的今日紀錄（快照：送出時原樣附上 ID＋版本）。 */
type TodayForm = { kind: 'revoke' | 'reset'; seen: TodayAttendance }

/**
 * 今日打卡卡片（首頁與出勤頁共用）。
 * 今日已有紀錄時提供兩種申請，兩者對資料的影響不同：
 * - 申請撤銷下班打卡：同一筆紀錄保留上班時間、只清掉下班時間（只在已下班時出現）。
 * - 申請整組作廢並重新打卡：整筆紀錄作廢，今日回到尚未上班（只有上班時間也可以）。
 * 申請不會立即更動紀錄，也不會開放打卡按鈕，要等管理員核准；核准後（視窗回到前景或輪詢時）重新向後端查詢真實狀態。
 * onChanged：打卡或申請狀態改變後通知外層（例如重新載入紀錄列表）。
 */
export function TodayAttendanceCard({ onChanged }: Readonly<{ onChanged?: () => void }>) {
  const messageOf = useApiErrorMessage()
  const auth = useAuth()
  const { state, busy, notice, retry, reload, clockIn, clockOut } = useTodayAttendance(onChanged)
  const requests = useMyRequests(() => {
    void reload() // 申請有結果（被核准或退回）→ 重新查詢今日狀態
    onChanged?.()
  })
  const [form, setForm] = useState<TodayForm | null>(null)
  const [requestError, setRequestError] = useState<string | null>(null)
  const [cancelling, setCancelling] = useState(false)

  const employeeName = auth.state.status === 'authenticated' ? `${auth.state.user.displayName}（${auth.state.user.employeeNo}）` : ''
  const data = state.kind === 'ready' ? state.data : null
  const todayRequests = data && requests.state.kind === 'ready' ? requests.state.requests.filter((r) => r.workDate === data.workDate) : []
  const pending = todayRequests.find((r) => r.status === 'PENDING') ?? null
  const lastRejected = todayRequests.find((r) => r.status === 'REJECTED') ?? null
  const requestable = data?.recordId != null && data.version != null && !pending
  const canRevoke = requestable && data?.status === 'COMPLETED' && data.clockOutAt !== null
  const canReset = requestable && (data?.status === 'WORKING' || data?.status === 'COMPLETED')

  async function submit(reason: string) {
    if (!form) return
    const { seen } = form
    const snapshot = { expectedAttendanceId: seen.recordId, expectedVersion: seen.version }
    try {
      if (form.kind === 'revoke') {
        // 上班時間以完整精度原樣送出（例如 12:26:23.631575），核准後才會一字不差地保留
        await submitRequest({ type: 'CORRECT_TIME', workDate: seen.workDate, clockInTime: toPreciseTimeValue(seen.clockInAt ?? ''), clockOutTime: null, reason, ...snapshot })
      } else {
        await submitRequest({ type: 'RESET_TODAY', reason, ...snapshot })
      }
    } catch (e) {
      void reload() // 例如 409 RECORD_CHANGED：顯示最新的紀錄，表單會提示已過期
      throw e
    }
    setForm(null)
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
              你在 {formatWorkDate(data.unfinishedBefore)} 的出勤紀錄尚未下班。系統不會自動補下班；可到「出勤打卡」頁申請更正／補登時間。
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
            <div className="banner banner--info" role="status" data-testid="today-pending" data-kind={pending.kind}>
              <p>
                已提出「{REQUEST_KIND_LABEL[pending.kind]}」申請，等待管理員審核
                {pending.stale ? '（資料已有變動，這筆申請已過時，核准會被拒絕，請撤回後重新提出）' : ''}。
                在核准之前，打卡紀錄與按鈕不會改變。
              </p>
              <p className="small">原因：{pending.reason}</p>
              <button type="button" className="btn btn--small" onClick={() => void cancelPending()} disabled={cancelling}>
                {cancelling ? '撤回中…' : '撤回申請'}
              </button>
            </div>
          )}

          {lastRejected && !pending && (
            <p className="notice notice--error" data-testid="today-rejected">
              先前的「{REQUEST_KIND_LABEL[lastRejected.kind]}」申請已被退回：{lastRejected.reviewComment}
            </p>
          )}

          {!form && (canRevoke || canReset) && (
            <div className="actions">
              {canRevoke && (
                <button type="button" className="btn" onClick={() => setForm({ kind: 'revoke', seen: data })}>
                  申請撤銷下班打卡
                </button>
              )}
              {canReset && (
                <button type="button" className="btn" onClick={() => setForm({ kind: 'reset', seen: data })}>
                  申請整組作廢並重新打卡
                </button>
              )}
            </div>
          )}

          {form && <TodayRequestForm form={form} current={data} employeeName={employeeName} onSubmit={submit} onCancel={() => setForm(null)} />}
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

      <p className="muted small">
        本版本採固定白班、每日一組上下班，不支援跨日班。誤按或漏打請提出申請，由管理員審核；要修改時間或補登其他日期，請到「出勤打卡」頁的「申請更正／補登時間」。時間以台北時區顯示。
      </p>
    </section>
  )
}

interface TodayRequestFormProps {
  form: TodayForm
  /** 目前畫面上的今日資料（自動重新查詢會更新）；和 form.seen 不同代表表單已過期 */
  current: TodayAttendance
  employeeName: string
  onSubmit: (reason: string) => Promise<void>
  onCancel: () => void
}

/** 撤銷下班／整組作廢的申請表單：列出員工、日期、目前時間，並明確寫出核准後的結果。 */
function TodayRequestForm({ form, current, employeeName, onSubmit, onCancel }: Readonly<TodayRequestFormProps>) {
  const { kind, seen } = form
  const revoke = kind === 'revoke'
  const outdated = current.recordId !== seen.recordId || current.version !== seen.version
  return (
    <ReasonForm
      key={kind}
      testId={revoke ? 'revoke-form' : 'reset-form'}
      label={revoke ? '申請撤銷下班打卡的原因（必填）' : '申請整組作廢的原因（必填）'}
      placeholder={revoke ? '例如：還在工作，誤按了下班' : '例如：測試功能時誤按上下班，希望整組重新打卡'}
      submitLabel="送出申請"
      onSubmit={onSubmit}
      onCancel={onCancel}
    >
      <h3>{revoke ? '申請撤銷下班打卡' : '申請整組作廢並重新打卡'}</h3>
      <dl className="compare">
        <dt>員工</dt>
        <dd>{employeeName}</dd>
        <dt>日期</dt>
        <dd>{formatWorkDate(seen.workDate)}</dd>
        <dt>目前上班</dt>
        <dd>{formatTime(seen.clockInAt)}</dd>
        <dt>目前下班</dt>
        <dd>{formatTime(seen.clockOutAt)}</dd>
      </dl>
      {revoke ? <RevokeOutcome seen={seen} /> : <ResetOutcome seen={seen} />}
      {outdated && (
        <p className="banner banner--warn" role="status" data-testid="form-outdated">
          今日紀錄在你開啟表單後已經變動，這張表單根據的是舊資料，送出會被拒絕。請按「取消」後重新開啟。
        </p>
      )}
    </ReasonForm>
  )
}

function RevokeOutcome({ seen }: Readonly<{ seen: TodayAttendance }>) {
  return (
    <>
      <label htmlFor="revoke-in">上班時間（保留，不可修改）</label>
      <input id="revoke-in" type="text" readOnly value={formatTime(seen.clockInAt)} />
      <p className="banner banner--info" data-testid="form-outcome">
        核准後：保留上班時間、撤銷下班時間，恢復工作中，可再次下班打卡
      </p>
      <p className="muted small">不會作廢紀錄，也不會改動上班時間。</p>
    </>
  )
}

function ResetOutcome({ seen }: Readonly<{ seen: TodayAttendance }>) {
  const hasOut = seen.clockOutAt !== null
  return (
    <>
      <p className="banner banner--warn" data-testid="form-outcome">
        核准後：今日這一整組打卡（上班{hasOut ? '與下班' : ''}）都會標為作廢，原時間只保留在歷程中；今日回到「尚未上班」，必須重新打上班卡
        {hasOut ? '與下班卡' : ''}。
      </p>
      {hasOut && <p className="small">如果只是誤按下班，請改用「申請撤銷下班打卡」，可以保留上班時間。</p>}
      <p className="muted small">整組作廢只適用於今日；過了今天（台北時間）就無法核准，請改用更正／補登時間。</p>
    </>
  )
}
