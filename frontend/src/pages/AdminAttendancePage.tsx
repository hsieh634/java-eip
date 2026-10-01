import { useState } from 'react'
import * as admin from '../api/adminAttendance'
import type { AttendanceRecord } from '../api/attendance'
import type { AttendanceRequest } from '../api/attendanceRequests'
import { RECORD_STATUS_LABEL, REQUEST_STATUS_LABEL, REQUEST_TYPE_LABEL, describeTimes } from '../attendance/labels'
import { useAutoRefresh } from '../attendance/useAutoRefresh'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { HistoryList } from '../components/HistoryList'
import { FormError } from '../lib/formError'
import { formatDateTime, formatTime, formatWorkDate, inclusiveDays, taipeiToday, toTimeInputValue } from '../lib/time'
import { useLoad } from '../lib/useLoad'
import type { CurrentUser } from '../api/auth'

const MAX_RANGE_DAYS = 31

/** 出勤管理（僅 ADMIN）。入口只對 ADMIN 顯示，但真正的權限由後端驗證——非管理員直接呼叫 API 一律被拒絕。 */
export function AdminAttendancePage({ user }: Readonly<{ user: CurrentUser }>) {
  if (user.role !== 'ADMIN') {
    return (
      <>
        <h1>出勤管理</h1>
        <p className="form-error" role="alert">
          只有管理員可以使用這個功能。
        </p>
      </>
    )
  }
  return <AdminContent />
}

function AdminContent() {
  return (
    <>
      <h1>出勤管理</h1>
      <p className="muted">審核員工的重新打卡與更正申請，或在填寫原因後直接更正。每一項操作都會留下歷程。</p>
      <div className="stack">
        <PendingRequests />
        <EmployeeAttendance />
      </div>
    </>
  )
}

// ====================== 待審申請 ======================

type ReviewAction = { kind: 'approve' | 'reject'; request: AttendanceRequest }

function PendingRequests() {
  const [filter, setFilter] = useState<'PENDING' | 'ALL'>('PENDING')
  const { state, reload, refetch } = useLoad(() => admin.fetchRequests(filter), `requests:${filter}`)
  const [action, setAction] = useState<ReviewAction | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  useAutoRefresh(refetch, 30_000) // 視窗回到前景與每 30 秒：新申請會自己出現

  async function done(message: string) {
    setAction(null)
    setNotice(message)
    reload()
  }

  return (
    <section className="card" aria-labelledby="pending-title">
      <div className="card__title-row">
        <h2 id="pending-title">申請審核</h2>
        <select aria-label="申請篩選" value={filter} onChange={(e) => setFilter(e.target.value as 'PENDING' | 'ALL')}>
          <option value="PENDING">只看待審</option>
          <option value="ALL">全部</option>
        </select>
      </div>
      {notice && (
        <p className="notice notice--success" role="status" data-testid="admin-notice">
          {notice}
        </p>
      )}
      {state.kind === 'loading' && <p className="muted">載入中…</p>}
      {state.kind === 'error' && (
        <div>
          <p className="form-error" role="alert">
            無法載入申請：{state.message}
          </p>
          <button type="button" className="btn" onClick={reload}>
            重試
          </button>
        </div>
      )}
      {state.kind === 'ready' && state.data.length === 0 && <p className="muted" data-testid="admin-no-requests">目前沒有待審的申請。</p>}
      {state.kind === 'ready' && state.data.length > 0 && (
        <ul className="request-list" data-testid="admin-requests">
          {state.data.map((r) => (
            <li key={r.id} className="request-item" data-status={r.status} data-request-id={r.id}>
              <div className="request-item__head">
                <strong>{r.employee.displayName}（{r.employee.employeeNo}）</strong>
                <span>{REQUEST_TYPE_LABEL[r.type]}</span>
                <span>{formatWorkDate(r.workDate)}</span>
                <span className={`status-chip status-chip--req-${r.status.toLowerCase()}`}>{REQUEST_STATUS_LABEL[r.status]}</span>
              </div>
              <p className="request-item__reason">原因：{r.reason}</p>
              <p className="small">原時間（申請當時）：{describeTimes(r.original)}</p>
              {r.type === 'CORRECT_TIME' && <p className="small">申請時間：{describeTimes(r.requested)}</p>}
              {r.status === 'PENDING' && <p className="small">目前紀錄：{describeTimes(r.current)}</p>}
              {r.status === 'PENDING' && r.stale && (
                <p className="banner banner--warn" role="status">
                  申請之後出勤資料已有變動，這筆申請已過時，核准會被拒絕（不會覆蓋較新的資料）。建議退回並請員工重新提出。
                </p>
              )}
              {r.status !== 'PENDING' && (
                <p className="small">
                  {REQUEST_STATUS_LABEL[r.status]}
                  {r.reviewerName ? `（${r.reviewerName}，${formatDateTime(r.reviewedAt)}）` : ''}
                  {r.reviewComment ? `：${r.reviewComment}` : ''}
                </p>
              )}
              {r.status === 'PENDING' && (
                <div className="actions">
                  <button type="button" className="btn btn--primary" onClick={() => setAction({ kind: 'approve', request: r })}>
                    核准
                  </button>
                  <button type="button" className="btn" onClick={() => setAction({ kind: 'reject', request: r })}>
                    退回
                  </button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {action?.kind === 'approve' && <ApproveDialog request={action.request} onClose={() => setAction(null)} onDone={done} />}
      {action?.kind === 'reject' && <RejectDialog request={action.request} onClose={() => setAction(null)} onDone={done} />}
    </section>
  )
}

/** 執行前清楚列出：員工、日期、原值，以及核准後的預計結果。 */
function ExpectedOutcome({ request }: Readonly<{ request: AttendanceRequest }>) {
  return (
    <dl className="compare">
      <dt>員工</dt>
      <dd>{request.employee.displayName}（{request.employee.employeeNo}）</dd>
      <dt>日期</dt>
      <dd>{formatWorkDate(request.workDate)}</dd>
      <dt>申請類型</dt>
      <dd>{REQUEST_TYPE_LABEL[request.type]}</dd>
      <dt>原值</dt>
      <dd>{describeTimes(request.original)}</dd>
      <dt>預計結果</dt>
      <dd data-testid="expected-outcome">
        {request.type === 'RESET_TODAY'
          ? '原紀錄標為作廢（原時間保留在歷程中），今日回到「尚未上班」，員工可重新打卡一組。'
          : request.original
            ? `更正為 ${describeTimes(request.requested)}`
            : `新增補登紀錄 ${describeTimes(request.requested)}`}
      </dd>
    </dl>
  )
}

function ApproveDialog({ request, onClose, onDone }: Readonly<{ request: AttendanceRequest; onClose: () => void; onDone: (message: string) => Promise<void> }>) {
  const [comment, setComment] = useState('')
  return (
    <ConfirmDialog
      title="確認核准這筆申請"
      confirmLabel="確認核准"
      onCancel={onClose}
      onConfirm={async () => {
        await admin.approveRequest(request.id, comment.trim())
        await onDone(`已核准 ${request.employee.displayName} 的申請。`)
      }}
    >
      <ExpectedOutcome request={request} />
      <label htmlFor="approve-comment">審核意見（選填）</label>
      <textarea id="approve-comment" rows={2} maxLength={500} value={comment} onChange={(e) => setComment(e.target.value)} />
    </ConfirmDialog>
  )
}

function RejectDialog({ request, onClose, onDone }: Readonly<{ request: AttendanceRequest; onClose: () => void; onDone: (message: string) => Promise<void> }>) {
  const [reason, setReason] = useState('')
  return (
    <ConfirmDialog
      title="確認退回這筆申請"
      confirmLabel="確認退回"
      danger
      onCancel={onClose}
      onConfirm={async () => {
        if (!reason.trim()) throw new FormError('退回必須填寫原因。')
        await admin.rejectRequest(request.id, reason.trim())
        await onDone(`已退回 ${request.employee.displayName} 的申請。`)
      }}
    >
      <ExpectedOutcome request={request} />
      <p className="small">退回後出勤紀錄不會改變，員工會看到退回原因。</p>
      <label htmlFor="reject-reason">退回原因（必填）</label>
      <textarea id="reject-reason" rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
    </ConfirmDialog>
  )
}

// ====================== 員工出勤查詢與直接更正 ======================

type DirectAction =
  | { kind: 'correct'; employeeId: number; employeeName: string; record: AttendanceRecord }
  | { kind: 'create'; employeeId: number; employeeName: string }
  | { kind: 'void'; employeeId: number; employeeName: string; record: AttendanceRecord }

function monthStart(today: string): string {
  return `${today.slice(0, 8)}01`
}

function EmployeeAttendance() {
  const employees = useLoad(() => admin.fetchEmployees(), 'employees')
  const today = taipeiToday()
  const [employeeId, setEmployeeId] = useState('')
  const [from, setFrom] = useState(monthStart(today))
  const [to, setTo] = useState(today)
  const [applied, setApplied] = useState<{ employeeId: number; from: string; to: string } | null>(null)
  const [rangeError, setRangeError] = useState<string | null>(null)
  const [action, setAction] = useState<DirectAction | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const key = applied ? `${applied.employeeId}|${applied.from}|${applied.to}` : 'none'
  const records = useLoad(applied ? () => admin.fetchEmployeeRecords(applied.employeeId, applied.from, applied.to) : null, `records:${key}`)
  const history = useLoad(applied ? () => admin.fetchEmployeeHistory(applied.employeeId, applied.from, applied.to) : null, `history:${key}`)

  function search() {
    if (!employeeId) return setRangeError('請選擇員工。')
    if (!from || !to) return setRangeError('請選擇起日與迄日。')
    if (from > to) return setRangeError('起日不可晚於迄日。')
    if (inclusiveDays(from, to) > MAX_RANGE_DAYS) return setRangeError(`日期範圍最多 ${MAX_RANGE_DAYS} 天（含起訖日）。`)
    setRangeError(null)
    setNotice(null)
    setApplied({ employeeId: Number(employeeId), from, to })
  }

  function done(message: string) {
    setAction(null)
    setNotice(message)
    records.reload()
    history.reload()
  }

  const recordData = records.state.kind === 'ready' ? records.state.data : null

  return (
    <section className="card" aria-labelledby="employee-att-title">
      <h2 id="employee-att-title">員工出勤查詢與更正</h2>
      <div className="range-form">
        <div className="range-field">
          <label htmlFor="admin-employee">員工</label>
          <select id="admin-employee" value={employeeId} onChange={(e) => setEmployeeId(e.target.value)}>
            <option value="">請選擇…</option>
            {employees.state.kind === 'ready' &&
              employees.state.data.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.displayName}（{e.employeeNo}）
                </option>
              ))}
          </select>
        </div>
        <div className="range-field">
          <label htmlFor="admin-from">起日</label>
          <input id="admin-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div className="range-field">
          <label htmlFor="admin-to">迄日</label>
          <input id="admin-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
        <button type="button" className="btn" onClick={search}>
          查詢
        </button>
      </div>
      <p className="form-error" role="alert">
        {rangeError}
      </p>
      {employees.state.kind === 'error' && (
        <p className="form-error" role="alert">
          無法載入員工清單：{employees.state.message}
        </p>
      )}
      {notice && (
        <p className="notice notice--success" role="status" data-testid="admin-notice">
          {notice}
        </p>
      )}

      {applied && records.state.kind === 'loading' && <p className="muted">載入中…</p>}
      {applied && records.state.kind === 'error' && (
        <div>
          <p className="form-error" role="alert">
            無法載入出勤紀錄：{records.state.message}
          </p>
          <button type="button" className="btn" onClick={records.reload}>
            重試
          </button>
        </div>
      )}
      {recordData && (
        <>
          <div className="card__title-row">
            <h3>
              {recordData.employee.displayName} 的出勤（{recordData.from} ～ {recordData.to}）
            </h3>
            <button type="button" className="btn btn--small" onClick={() => setAction({ kind: 'create', employeeId: recordData.employee.id, employeeName: recordData.employee.displayName })}>
              新增（補登）紀錄
            </button>
          </div>
          {recordData.records.length === 0 && <p className="muted">這段期間沒有出勤紀錄。</p>}
          {recordData.records.length > 0 && (
            <table className="records-table" data-testid="admin-records-table">
              <thead>
                <tr>
                  <th scope="col">日期</th>
                  <th scope="col">上班</th>
                  <th scope="col">下班</th>
                  <th scope="col">狀態</th>
                  <th scope="col">版本</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                {recordData.records.map((r) => (
                  <tr key={r.id} className={r.status === 'VOIDED' ? 'row--voided' : undefined} data-record-id={r.id} data-status={r.status}>
                    <td data-label="日期">{formatWorkDate(r.workDate)}</td>
                    <td data-label="上班">{formatTime(r.clockInAt)}</td>
                    <td data-label="下班">{formatTime(r.clockOutAt)}</td>
                    <td data-label="狀態">
                      <span className={`status-chip status-chip--${r.status.toLowerCase()}`}>{RECORD_STATUS_LABEL[r.status]}</span>
                      {r.source === 'MANUAL' && <span className="status-chip status-chip--manual">補登</span>}
                      {r.corrected && r.source !== 'MANUAL' && <span className="status-chip status-chip--manual">已更正</span>}
                    </td>
                    <td data-label="版本">{r.version}</td>
                    <td data-label="操作">
                      {r.status !== 'VOIDED' && (
                        <div className="actions">
                          <button type="button" className="btn btn--small" onClick={() => setAction({ kind: 'correct', employeeId: recordData.employee.id, employeeName: recordData.employee.displayName, record: r })}>
                            更正時間
                          </button>
                          {r.workDate === today && (
                            <button type="button" className="btn btn--small btn--danger" onClick={() => setAction({ kind: 'void', employeeId: recordData.employee.id, employeeName: recordData.employee.displayName, record: r })}>
                              作廢今日紀錄
                            </button>
                          )}
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}

      {applied && (
        <div>
          <h3>操作歷程</h3>
          {history.state.kind === 'loading' && <p className="muted">載入中…</p>}
          {history.state.kind === 'error' && (
            <p className="form-error" role="alert">
              無法載入歷程：{history.state.message}
            </p>
          )}
          {history.state.kind === 'ready' && <HistoryList entries={history.state.data} showEmployee />}
        </div>
      )}

      {action?.kind === 'correct' && <DirectCorrectionDialog employeeId={action.employeeId} employeeName={action.employeeName} record={action.record} onClose={() => setAction(null)} onDone={done} />}
      {action?.kind === 'create' && <DirectCorrectionDialog employeeId={action.employeeId} employeeName={action.employeeName} record={null} onClose={() => setAction(null)} onDone={done} />}
      {action?.kind === 'void' && <VoidDialog employeeId={action.employeeId} employeeName={action.employeeName} record={action.record} onClose={() => setAction(null)} onDone={done} />}
    </section>
  )
}

interface DialogProps {
  employeeId: number
  employeeName: string
  onClose: () => void
  onDone: (message: string) => void
}

/** 直接更正（record 為 null＝建立／補登）。必填原因；以版本偵測資料是否已被改過；明確顯示原值與預計結果。 */
function DirectCorrectionDialog({ employeeId, employeeName, record, onClose, onDone }: Readonly<DialogProps & { record: AttendanceRecord | null }>) {
  const today = taipeiToday()
  const [workDate, setWorkDate] = useState(record?.workDate ?? today)
  const [clockIn, setClockIn] = useState(toTimeInputValue(record?.clockInAt ?? null))
  const [clockOut, setClockOut] = useState(toTimeInputValue(record?.clockOutAt ?? null))
  const [reason, setReason] = useState('')
  return (
    <ConfirmDialog
      title={record ? '確認直接更正出勤時間' : '確認新增（補登）出勤紀錄'}
      confirmLabel={record ? '確認更正' : '確認新增'}
      onCancel={onClose}
      onConfirm={async () => {
        if (!clockIn) throw new FormError('請填寫上班時間。')
        if (clockOut && clockOut < clockIn) throw new FormError('下班時間不能早於上班時間。')
        if (!reason.trim()) throw new FormError('請填寫原因。')
        await admin.correctDirectly({
          employeeId,
          workDate,
          expectedVersion: record ? record.version : null,
          clockInTime: clockIn,
          clockOutTime: clockOut || null,
          reason: reason.trim(),
        })
        onDone(`已${record ? '更正' : '新增'} ${employeeName} ${workDate} 的出勤。`)
      }}
    >
      <dl className="compare">
        <dt>員工</dt>
        <dd>{employeeName}</dd>
        <dt>日期</dt>
        <dd>
          {record ? (
            formatWorkDate(record.workDate)
          ) : (
            <input id="direct-date" type="date" max={today} value={workDate} onChange={(e) => setWorkDate(e.target.value)} aria-label="業務日期" />
          )}
        </dd>
        <dt>原值</dt>
        <dd>{record ? describeTimes({ clockInAt: record.clockInAt, clockOutAt: record.clockOutAt }) : '（沒有紀錄）'}</dd>
        <dt>預計結果</dt>
        <dd data-testid="expected-outcome">
          {clockIn || '—'} ～ {clockOut || '—（無下班時間）'}
        </dd>
      </dl>
      <div className="form-grid">
        <label htmlFor="direct-in">上班時間</label>
        <input id="direct-in" type="time" step={1} value={clockIn} onChange={(e) => setClockIn(e.target.value)} />
        <label htmlFor="direct-out">下班時間（可留空）</label>
        <input id="direct-out" type="time" step={1} value={clockOut} onChange={(e) => setClockOut(e.target.value)} />
      </div>
      <label htmlFor="direct-reason">原因（必填）</label>
      <textarea id="direct-reason" rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
    </ConfirmDialog>
  )
}

function VoidDialog({ employeeId, employeeName, record, onClose, onDone }: Readonly<DialogProps & { record: AttendanceRecord }>) {
  const [reason, setReason] = useState('')
  return (
    <ConfirmDialog
      title="確認作廢今日紀錄（開放重新打卡）"
      confirmLabel="確認作廢"
      danger
      onCancel={onClose}
      onConfirm={async () => {
        if (!reason.trim()) throw new FormError('請填寫原因。')
        await admin.voidToday({ employeeId, attendanceId: record.id, expectedVersion: record.version, reason: reason.trim() })
        onDone(`已作廢 ${employeeName} 今日的紀錄，對方可以重新打卡。`)
      }}
    >
      <dl className="compare">
        <dt>員工</dt>
        <dd>{employeeName}</dd>
        <dt>日期</dt>
        <dd>{formatWorkDate(record.workDate)}</dd>
        <dt>原值</dt>
        <dd>{describeTimes({ clockInAt: record.clockInAt, clockOutAt: record.clockOutAt })}</dd>
        <dt>預計結果</dt>
        <dd data-testid="expected-outcome">原紀錄標為作廢（原時間保留），今日回到「尚未上班」，員工可重新打卡一組。這不是刪除。</dd>
      </dl>
      <label htmlFor="void-reason">原因（必填）</label>
      <textarea id="void-reason" rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
    </ConfirmDialog>
  )
}
