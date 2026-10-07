import { useState } from 'react'
import type { LocationPolicy, TodayAttendance } from '../api/attendance'
import { cancelRequest, submitRequest } from '../api/attendanceRequests'
import { RECORD_STATUS_LABEL, REQUEST_KIND_LABEL } from '../attendance/labels'
import { useMyRequests } from '../attendance/useMyRequests'
import { useTodayAttendance, type Action, type Phase } from '../attendance/useTodayAttendance'
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
 * - 申請整組作廢並重新打卡：只作廢所選時段；其他有效時段仍決定今日狀態（只有上班時間也可以）。
 * 申請不會立即更動紀錄，也不會開放打卡按鈕，要等管理員核准；核准後（視窗回到前景或輪詢時）重新向後端查詢真實狀態。
 * onChanged：打卡或申請狀態改變後通知外層（例如重新載入紀錄列表）。
 */
export function TodayAttendanceCard({ onChanged }: Readonly<{ onChanged?: () => void }>) {
  const messageOf = useApiErrorMessage()
  const auth = useAuth()
  const { state, busy, phase, notice, retry, reload, perform, pending: unconfirmed, confirmPending, abandonPending } = useTodayAttendance(onChanged)
  const requests = useMyRequests(() => {
    void reload() // 申請有結果（被核准或退回）→ 重新查詢今日狀態
    onChanged?.()
  })
  const [form, setForm] = useState<TodayForm | null>(null)
  const [requestError, setRequestError] = useState<string | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [abandonWarning, setAbandonWarning] = useState(false)

  const employeeName = auth.state.status === 'authenticated' ? `${auth.state.user.displayName}（${auth.state.user.employeeNo}）` : ''
  const data = state.kind === 'ready' ? state.data : null
  const todayRequests = data && requests.state.kind === 'ready' ? requests.state.requests.filter((r) => r.workDate === data.workDate) : []
  const pending = todayRequests.find((r) => r.status === 'PENDING') ?? null
  const lastRejected = todayRequests.find((r) => r.status === 'REJECTED') ?? null
  const selected = data?.records.find(r => r.id === selectedId && r.status !== 'VOIDED') ?? data?.records.find(r => r.id === data.recordId)
  const requestable = selected != null && !pending
  const canRevoke = requestable && selected?.clockOutAt !== null
  const canReset = requestable
  const seenSelected = data && selected ? { ...data, recordId: selected.id, version: selected.version, clockInAt: selected.clockInAt, clockOutAt: selected.clockOutAt, status: selected.status } : null

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
            {data.status === 'COMPLETED' ? '目前已下班，可再次上班' : RECORD_STATUS_LABEL[data.status]}
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

          <p data-testid="attendance-total" data-seconds={data.completedSeconds}>完成時段合計：{Math.floor(data.completedSeconds / 3600)} 小時 {Math.floor(data.completedSeconds % 3600 / 60)} 分 {data.completedSeconds % 60} 秒</p>
          <p className="muted small">未完成時段尚未計入；作廢時段不計。這是出勤時長，不是專案工時或計薪工時，沒有自動扣午休。</p>
          <ul data-testid="today-sessions">
            {data.records.map(r => <li key={r.id} data-record-id={r.id} data-status={r.status}>#{r.id}：{formatTime(r.clockInAt)} ～ {formatTime(r.clockOutAt)}（{RECORD_STATUS_LABEL[r.status]}；版本 {r.version}；{r.source === 'MANUAL' ? '補登' : '打卡'}）</li>)}
          </ul>
          <ClockActions data={data} busy={busy ?? (unconfirmed ? unconfirmed.action : null)} phase={phase} onClock={(action) => void perform(action)} />
          {unconfirmed && <div className="banner banner--warn" data-testid="clock-unconfirmed">
            <p>原{unconfirmed.action === 'in' ? '上班' : '下班'}打卡尚未確認。只會重送原內容與原識別碼，不會取得新的定位或改成另一個打卡。</p>
            <button type="button" className="btn" disabled={busy !== null} onClick={() => void confirmPending()}>重送原打卡確認</button>
            {!abandonWarning ? <button type="button" className="btn" disabled={busy !== null} onClick={() => setAbandonWarning(true)}>放棄追蹤打卡</button> : <>
              <p role="alert">原打卡可能已成功。放棄後先查真實時段；再次操作會使用新的識別碼，請先確認沒有重複意圖。</p>
              <button type="button" className="btn" disabled={busy !== null} onClick={() => { abandonPending(); setAbandonWarning(false) }}>確認放棄追蹤</button>
              <button type="button" className="btn" onClick={() => setAbandonWarning(false)}>繼續追蹤</button>
            </>}
          </div>}
          {data.records.some(r => r.status !== 'VOIDED') && <label>申請目標時段
            <select data-testid="today-target" value={selected?.id ?? ''} onChange={e => setSelectedId(Number(e.target.value))}>
              {data.records.filter(r => r.status !== 'VOIDED').map(r => <option key={r.id} value={r.id}>#{r.id} {formatTime(r.clockInAt)} ～ {formatTime(r.clockOutAt)}</option>)}
            </select>
          </label>}
          <p className="muted small">每位員工每天最多一筆待審出勤更正；作廢或撤銷只針對所選時段。</p>

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
                <button type="button" className="btn" onClick={() => seenSelected && setForm({ kind: 'revoke', seen: seenSelected })}>
                  申請撤銷下班打卡
                </button>
              )}
              {canReset && (
                <button type="button" className="btn" onClick={() => seenSelected && setForm({ kind: 'reset', seen: seenSelected })}>
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
        <div className={notice.kind === 'success' ? 'notice notice--success' : 'notice notice--error'} role="status" data-testid="clock-notice">
          <p>{notice.text}</p>
          {notice.retry && <RetryButton action={notice.retry} disabled={busy !== null} onRetry={(action) => void perform(action)} />}
        </div>
      )}

      <p className="muted small">
        本版本支援同日多組上下班，不支援跨日班。誤按或漏打請提出申請，由管理員審核。時間以台北時區顯示。未確認追蹤只保留在本頁掛載期間；切頁或重新整理後只查詢真實資料，不自動重送。
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
  const target = current.records.find(r => r.id === seen.recordId && r.status !== 'VOIDED')
  const outdated = !target || target.version !== seen.version
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
        <dt>目標時段</dt><dd>#{seen.recordId}（版本 {seen.version}）</dd>
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
        核准後：所選 #{seen.recordId} 這一組打卡（上班{hasOut ? '與下班' : ''}）標為作廢，保留原時間與事件，其他時段不變；目前沒有有效未完成時段時，可重新上班。
      </p>
      {hasOut && <p className="small">如果只是誤按下班，請改用「申請撤銷下班打卡」，可以保留上班時間。</p>}
      <p className="muted small">整組作廢只適用於今日；過了今天（台北時間）就無法核准，請改用更正／補登時間。</p>
    </>
  )
}

const ACTION_LABEL: Record<Action, string> = { in: '上班打卡', out: '下班打卡' }
const PHASE_LABEL: Record<Phase, string> = { checking: '確認定位設定中…', locating: '正在取得位置…', submitting: '正在送出打卡…' }

/** 上下班按鈕＋本次是否需要定位的說明。按鈕文字隨階段改變（確認設定 → 取得位置 → 送出打卡）。 */
function ClockActions({ data, busy, phase, onClock }: Readonly<{ data: TodayAttendance; busy: Action | null; phase: Phase | null; onClock: (action: Action) => void }>) {
  const label = (action: Action) => (busy === action && phase ? PHASE_LABEL[phase] : ACTION_LABEL[action])
  return (
    <>
      <div className="actions">
        <button type="button" className="btn btn--primary btn--large" onClick={() => onClock('in')} disabled={busy !== null || data.status === 'WORKING'}>
          {label('in')}
        </button>
        <button type="button" className="btn btn--large" onClick={() => onClock('out')} disabled={busy !== null || data.status !== 'WORKING'}>
          {label('out')}
        </button>
      </div>
      {busy && phase && (
        <p className="muted small" role="status" aria-live="polite" data-testid="clock-phase" data-phase={phase}>
          {PHASE_LABEL[phase]}
        </p>
      )}
      <LocationPolicyNote policy={data.locationPolicy} />
    </>
  )
}

/** 事先告訴使用者：這次上班／下班是否需要定位、允許半徑與精度上限（數值來自後端）。 */
function LocationPolicyNote({ policy }: Readonly<{ policy: LocationPolicy }>) {
  if (!policy.enabled || (!policy.clockInRequired && !policy.clockOutRequired)) {
    return (
      <p className="muted small" data-testid="location-policy" data-required="none">
        定位限制未啟用：打卡不需要定位。
      </p>
    )
  }
  const which = [policy.clockInRequired && '上班', policy.clockOutRequired && '下班'].filter(Boolean).join('、')
  return (
    <p className="banner banner--info small" data-testid="location-policy" data-required={`${policy.clockInRequired ? 'in' : ''}${policy.clockOutRequired ? 'out' : ''}`}>
      {which}打卡需要定位：按下按鈕後瀏覽器會詢問位置，需在公司 {policy.radiusMeters} 公尺範圍內、定位精度 {policy.maxAccuracyMeters} 公尺以內。
      {!policy.clockInRequired && '上班打卡不需要定位。'}
      {!policy.clockOutRequired && '下班打卡不需要定位。'}
    </p>
  )
}

function RetryButton({ action, disabled, onRetry }: Readonly<{ action: Action; disabled: boolean; onRetry: (action: Action) => void }>) {
  return (
    <button type="button" className="btn btn--small" onClick={() => onRetry(action)} disabled={disabled}>
      重試{ACTION_LABEL[action]}
    </button>
  )
}
