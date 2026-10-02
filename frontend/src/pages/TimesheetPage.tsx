import { useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../api/http'
import { fetchSelectableProjects, type SelectableProject } from '../api/projects'
import { createEntry, fetchEntry, fetchTimesheets, updateEntry, type TimesheetEntry, type TimesheetList } from '../api/timesheets'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'
import { formatMinutes, minutesToHoursText, parseHours } from '../lib/hours'
import { newRequestId } from '../lib/requestId'
import { formatWorkDate, inclusiveDays, taipeiToday } from '../lib/time'
import { useLifetime } from '../lib/useLifetime'
import { useLoad } from '../lib/useLoad'

const MAX_RANGE_DAYS = 31 // 與後端一致；後端仍會自行驗證

function monthRange(today: string) {
  const [y, m] = today.split('-').map(Number)
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate()
  return { from: `${today.slice(0, 8)}01`, to: `${today.slice(0, 8)}${String(last).padStart(2, '0')}` }
}

type FormMode = { kind: 'new'; seq: number } | { kind: 'edit'; id: number }

/**
 * 工時回報（所有已登入的使用者，只看與修改自己的）。
 * 清單、合計都以後端回應為準；同條件再按「查詢」也會重新取得（序號 seq 納入載入 key）；
 * 舊的查詢回應由 useLoad 依 key 丟棄，不會覆蓋新結果。
 */
export function TimesheetPage() {
  const today = taipeiToday()
  const initial = monthRange(today)
  const [from, setFrom] = useState(initial.from)
  const [to, setTo] = useState(initial.to)
  const [applied, setApplied] = useState<{ from: string; to: string; seq: number } | null>(null)
  const [rangeError, setRangeError] = useState<string | null>(null)
  const [mode, setMode] = useState<FormMode>({ kind: 'new', seq: 0 })
  const [notice, setNotice] = useState<string | null>(null)

  const listKey = applied ? `${applied.from}|${applied.to}|${applied.seq}` : 'default'
  const list = useLoad(() => fetchTimesheets(applied ?? undefined), `timesheets:${listKey}`)
  // 專案清單過期時（例如送出後才發現專案已停用）在背景重新取得：表單與錯誤訊息保持在畫面上
  const projects = useLoad(() => fetchSelectableProjects(), 'projects')

  function search(event: FormEvent) {
    event.preventDefault()
    if (!from || !to) return setRangeError('請選擇起日與迄日。')
    if (from > to) return setRangeError('起日不可晚於迄日。')
    if (inclusiveDays(from, to) > MAX_RANGE_DAYS) return setRangeError(`日期範圍最多 ${MAX_RANGE_DAYS} 天（含起訖日）。`)
    setRangeError(null)
    setApplied((prev) => ({ from, to, seq: (prev?.seq ?? 0) + 1 }))
  }

  /** 新增或修改完成：以後端資料重新整理清單與合計；新增表單換一個新的 requestId。 */
  function saved(message: string) {
    setNotice(message)
    setMode((m) => ({ kind: 'new', seq: (m.kind === 'new' ? m.seq : 0) + 1 }))
    list.refetch()
  }

  const data = list.state.kind === 'ready' ? list.state.data : null
  const selectable = projects.state.kind === 'ready' ? projects.state.data : null

  return (
    <>
      <h1>工時回報</h1>
      <p className="muted">
        記錄自己每天在各專案的工作內容與時數（以 0.25 小時為單位，每日合計最多 24 小時）。工時與出勤打卡是不同的資料：沒有打卡或定位失敗，仍可填寫。
      </p>
      {notice && (
        <p className="notice notice--success" role="status" data-testid="timesheet-notice">
          {notice}
        </p>
      )}

      <div className="stack">
        <section className="card" aria-labelledby="entry-form-title">
          <h2 id="entry-form-title">{mode.kind === 'new' ? '新增工時' : '修改工時'}</h2>
          {projects.state.kind === 'loading' && <p className="muted">載入專案中…</p>}
          {projects.state.kind === 'error' && (
            <div>
              <p className="form-error" role="alert">
                無法載入專案：{projects.state.message}
              </p>
              <button type="button" className="btn" onClick={projects.reload}>
                重試
              </button>
            </div>
          )}
          {selectable && mode.kind === 'new' && (
            <EntryFields
              key={`new:${mode.seq}`}
              entry={null}
              projects={selectable}
              today={today}
              onDone={saved}
              onCancel={null}
              onProjectsStale={projects.refetch}
              onListStale={list.refetch}
              onReset={() => setMode((m) => ({ kind: 'new', seq: (m.kind === 'new' ? m.seq : 0) + 1 }))}
            />
          )}
          {selectable && mode.kind === 'edit' && (
            <EditEntry
              key={mode.id}
              id={mode.id}
              projects={selectable}
              today={today}
              onDone={saved}
              onCancel={() => setMode({ kind: 'new', seq: 0 })}
              onProjectsStale={projects.refetch}
              onListStale={list.refetch}
            />
          )}
        </section>

        <section className="card card--records" aria-labelledby="timesheet-list-title">
          <h2 id="timesheet-list-title">我的工時紀錄</h2>
          <form className="range-form" onSubmit={search} noValidate>
            <div className="range-field">
              <label htmlFor="ts-from">起日</label>
              <input id="ts-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
            </div>
            <div className="range-field">
              <label htmlFor="ts-to">迄日</label>
              <input id="ts-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
            </div>
            <button type="submit" className="btn">
              查詢
            </button>
            <button
              type="button"
              className="btn"
              onClick={() => {
                const m = monthRange(taipeiToday())
                setFrom(m.from)
                setTo(m.to)
                setRangeError(null)
                setApplied((prev) => ({ ...m, seq: (prev?.seq ?? 0) + 1 }))
              }}
            >
              本月
            </button>
          </form>
          <p className="form-error" role="alert">
            {rangeError}
          </p>

          {list.state.kind === 'loading' && <p className="muted">載入中…</p>}
          {list.state.kind === 'error' && (
            <div>
              <p className="form-error" role="alert">
                無法載入工時紀錄：{list.state.message}
              </p>
              <button type="button" className="btn" onClick={list.reload}>
                重試
              </button>
            </div>
          )}
          {data && <TimesheetTable data={data} onEdit={(id) => setMode({ kind: 'edit', id })} />}
        </section>
      </div>
    </>
  )
}

function TimesheetTable({ data, onEdit }: Readonly<{ data: TimesheetList; onEdit: (id: number) => void }>) {
  return (
    <>
      <p data-testid="timesheet-range" className="small">
        {data.from} ～ {data.to}（台北時間）
      </p>
      <p className="status" data-testid="timesheet-total" data-minutes={data.totalMinutes}>
        期間合計：{formatMinutes(data.totalMinutes)}
      </p>
      {data.entries.length === 0 && (
        <p className="muted" data-testid="timesheet-empty">
          這段期間沒有工時紀錄。
        </p>
      )}
      {data.entries.length > 0 && (
        <>
          <table className="records-table" data-testid="timesheet-table">
            <thead>
              <tr>
                <th scope="col">日期</th>
                <th scope="col">專案</th>
                <th scope="col">工作內容</th>
                <th scope="col">時數</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              {data.entries.map((e) => (
                <tr key={e.id} data-entry-id={e.id} data-minutes={e.minutes}>
                  <td data-label="日期">{formatWorkDate(e.workDate)}</td>
                  <td data-label="專案">
                    {e.project.code} {e.project.name}
                    {!e.project.active && <span className="status-chip status-chip--voided">已停用</span>}
                  </td>
                  <td data-label="工作內容" className="cell--wrap">
                    {e.description}
                  </td>
                  <td data-label="時數">{formatMinutes(e.minutes)}</td>
                  <td data-label="操作">
                    <button type="button" className="btn btn--small" onClick={() => onEdit(e.id)}>
                      修改
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <h3>每日合計</h3>
          <ul className="daily-totals" data-testid="timesheet-daily">
            {data.daily.map((d) => (
              <li key={d.date} data-date={d.date} data-minutes={d.minutes}>
                {formatWorkDate(d.date)}：{formatMinutes(d.minutes)}
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  )
}

interface FormCallbacks {
  projects: SelectableProject[]
  today: string
  onDone: (message: string) => void
  onProjectsStale: () => void
  onListStale: () => void
}

/** 修改模式：每次都向後端取得這一筆的最新內容與版本（不使用清單上可能過期的資料）。 */
function EditEntry({ id, onCancel, ...rest }: Readonly<FormCallbacks & { id: number; onCancel: () => void }>) {
  const [loadSeq, setLoadSeq] = useState(0)
  const entry = useLoad(() => fetchEntry(id), `entry:${id}:${loadSeq}`)
  if (entry.state.kind === 'loading') return <p className="muted">載入這筆紀錄中…</p>
  if (entry.state.kind === 'error') {
    return (
      <div>
        <p className="form-error" role="alert">
          無法載入這筆紀錄：{entry.state.message}
        </p>
        <div className="actions">
          <button type="button" className="btn" onClick={entry.reload}>
            重試
          </button>
          <button type="button" className="btn" onClick={onCancel}>
            取消
          </button>
        </div>
      </div>
    )
  }
  const current = entry.state.data
  return (
    <EntryFields
      // 版本或重新載入序號改變 → 以最新內容重新初始化表單
      key={`${current.id}:${current.version}:${loadSeq}`}
      entry={current}
      onCancel={onCancel}
      onReset={null}
      onReload={() => setLoadSeq((s) => s + 1)}
      {...rest}
    />
  )
}

interface EntryFieldsProps extends FormCallbacks {
  /** null＝新增 */
  entry: TimesheetEntry | null
  onCancel: (() => void) | null
  /** 新增模式：清空並開始另一筆（換新的 requestId） */
  onReset: (() => void) | null
  /** 修改模式：版本衝突時重新載入這一筆 */
  onReload?: () => void
}

function EntryFields({ entry, projects, today, onDone, onCancel, onReset, onReload, onProjectsStale, onListStale }: Readonly<EntryFieldsProps>) {
  const messageOf = useApiErrorMessage()
  const lifetime = useLifetime()
  // 這一次「邏輯上的新增」的識別碼：結果不明而重送時沿用；成功或明確開始另一筆時，外層換 key 重新建立
  const [requestId] = useState(newRequestId)
  const [workDate, setWorkDate] = useState(entry?.workDate ?? today)
  const [projectId, setProjectId] = useState(entry ? String(entry.project.id) : '')
  const [description, setDescription] = useState(entry?.description ?? '')
  const [hours, setHours] = useState(entry ? minutesToHoursText(entry.minutes) : '')
  const [error, setError] = useState<string | null>(null)
  const [conflict, setConflict] = useState(false)
  const [unknown, setUnknown] = useState(false)
  const [busy, setBusy] = useState(false)
  // 防連按：ref 立即生效（state 要等下一次 render）
  const submitting = useRef(false)

  // 修改時，原本的專案即使已停用（不在啟用清單內）也要顯示，不能被清空
  const options = entry && !projects.some((p) => p.id === entry.project.id)
    ? [...projects.map((p) => ({ id: p.id, label: `${p.code} ${p.name}` })), { id: entry.project.id, label: `${entry.project.code} ${entry.project.name}（已停用）` }]
    : projects.map((p) => ({ id: p.id, label: `${p.code} ${p.name}` }))
  const parsed = hours.trim() ? parseHours(hours) : null

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (submitting.current) return
    if (!workDate) return setError('請選擇日期。')
    if (workDate > today) return setError('不能填寫未來日期的工時。')
    if (!projectId) return setError('請選擇專案。')
    if (!description.trim()) return setError('請填寫工作內容。')
    const minutes = parseHours(hours)
    if (!minutes.ok) return setError(minutes.message)

    submitting.current = true
    setBusy(true)
    setError(null)
    setUnknown(false)
    const life = lifetime.current
    const values = { workDate, projectId: Number(projectId), description: description.trim(), minutes: minutes.minutes }
    try {
      if (entry) await updateEntry(entry.id, { ...values, expectedVersion: entry.version })
      else await createEntry({ ...values, requestId })
      if (!life.alive) return // 已切頁：不更新畫面（POST 已送出，無法撤回）
      onDone(entry ? '已更新工時紀錄。' : '已新增工時紀錄。')
    } catch (e) {
      if (!life.alive) return
      submitting.current = false
      setBusy(false)
      if (e instanceof ApiError && e.code === 'DUPLICATE_SUBMISSION') {
        // 結果不明時的重送：先前那次其實已經成功，不重複新增
        onDone(`這次提交先前已經建立（紀錄 #${String(e.details.entryId)}），沒有重複新增。`)
        return
      }
      if (e instanceof ApiError && e.code === 'TIMESHEET_CHANGED') {
        setConflict(true)
        setError('這筆紀錄已在其他分頁或裝置被修改，你的變更沒有儲存。請重新載入最新內容後再修改。')
        return
      }
      if (e instanceof ApiError && (e.code === 'PROJECT_INACTIVE' || e.code === 'PROJECT_NOT_FOUND')) {
        // 畫面上的專案資料已過期：重新取得可選專案，並刷新清單（既有紀錄會顯示「已停用」）
        onProjectsStale()
        onListStale()
      }
      if (e instanceof ApiError && (e.status === 0 || e.status >= 500)) {
        // 結果不明：伺服器可能其實已經寫入。重新查詢清單，不自動重送；再按送出會沿用同一個識別碼，不會重複建立
        setUnknown(true)
        setError(
          entry
            ? '無法確認是否已儲存（網路或伺服器問題），已重新查詢清單，請確認後再決定是否重試。'
            : '無法確認是否已新增（網路或伺服器問題），已重新查詢清單。若清單中沒有這筆，可再按「送出」——會沿用同一個識別碼，不會重複建立。',
        )
        onListStale()
        return
      }
      setError(messageOf(e))
    }
  }

  const noProjects = !entry && projects.length === 0
  return (
    <form className="correction-form" onSubmit={submit} noValidate data-testid="entry-form" data-mode={entry ? 'edit' : 'new'} data-unknown={unknown || undefined}>
      {noProjects && (
        <p className="banner banner--warn" data-testid="no-projects">
          目前沒有可填報的專案，請先由管理員建立或啟用專案。
        </p>
      )}
      <div className="form-grid">
        <label htmlFor="ts-date">日期</label>
        <input id="ts-date" type="date" max={today} value={workDate} onChange={(e) => setWorkDate(e.target.value)} />
        <label htmlFor="ts-project">專案</label>
        <select id="ts-project" value={projectId} onChange={(e) => setProjectId(e.target.value)} disabled={noProjects}>
          <option value="">請選擇…</option>
          {options.map((o) => (
            <option key={o.id} value={o.id}>
              {o.label}
            </option>
          ))}
        </select>
        <label htmlFor="ts-hours">時數（小時）</label>
        <input id="ts-hours" type="text" inputMode="decimal" placeholder="例如 1.5" value={hours} onChange={(e) => setHours(e.target.value)} />
      </div>
      <p className="muted small" data-testid="hours-preview">
        {parsed === null && '以 0.25 小時（15 分鐘）為單位：0.25＝15 分、1.5＝1 小時 30 分、7.75＝7 小時 45 分。'}
        {parsed?.ok === true && `＝ ${formatMinutes(parsed.minutes)}（${parsed.minutes} 分鐘）`}
        {parsed?.ok === false && parsed.message}
      </p>
      <label htmlFor="ts-description">工作內容（必填）</label>
      <textarea id="ts-description" rows={3} maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} />
      <p className="form-error" role="alert" data-testid="entry-error">
        {error}
      </p>
      <div className="actions">
        <button type="submit" className="btn btn--primary" disabled={busy || noProjects}>
          {busy ? '送出中…' : '送出'}
        </button>
        {conflict && onReload && (
          <button type="button" className="btn" onClick={onReload}>
            重新載入最新內容
          </button>
        )}
        {onReset && (
          <button type="button" className="btn" onClick={onReset} disabled={busy}>
            清空，開始新的一筆
          </button>
        )}
        {onCancel && (
          <button type="button" className="btn" onClick={onCancel} disabled={busy}>
            取消修改
          </button>
        )}
      </div>
    </form>
  )
}
