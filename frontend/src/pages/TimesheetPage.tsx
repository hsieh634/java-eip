import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../api/http'
import { fetchSelectableProjects, type SelectableProject } from '../api/projects'
import { createEntry, fetchEntry, fetchTimesheets, updateEntry, type TimesheetEntry, type TimesheetList } from '../api/timesheets'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'
import { useAuth } from '../auth/useAuth'
import { formatMinutes, minutesToHoursText, parseHours } from '../lib/hours'
import { newRequestId } from '../lib/requestId'
import { formatWorkDate, inclusiveDays, taipeiToday } from '../lib/time'
import { useLifetime, type Lifetime } from '../lib/useLifetime'
import { useLoad } from '../lib/useLoad'

const MAX_RANGE_DAYS = 31 // 與後端一致；後端仍會自行驗證

function monthRange(today: string) {
  const [y, m] = today.split('-').map(Number)
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate()
  return { from: `${today.slice(0, 8)}01`, to: `${today.slice(0, 8)}${String(last).padStart(2, '0')}` }
}

type FormMode = { kind: 'new'; seq: number } | { kind: 'edit'; id: number }

/**
 * 可填報的專案清單（工時頁專用，不使用共用的 useLoad）。
 * 初次載入與背景更新分開處理：
 * - 初次載入失敗：還沒有清單，顯示錯誤與重試（沒有表單可以顯示）。
 * - 背景更新失敗（例如送出後才發現專案停用而重新取得）：**保留上一份清單**，表單不卸載，
 *   另外顯示「專案清單更新失敗」與重試。
 * 每次請求取得序號，只採用最後發出的那一次；卸載後的回應不更新畫面。
 */
function useSelectableProjects() {
  const { refresh } = useAuth()
  // 掛載有效期間（與 useLifetime 相同的作法；寫在這裡讓 React Compiler 看得出它是 ref）
  const lifetime = useRef<Lifetime>({ alive: false })
  useEffect(() => {
    const current: Lifetime = { alive: true }
    lifetime.current = current
    return () => {
      current.alive = false
    }
  }, [])
  const seq = useRef(0)
  const [projects, setProjects] = useState<SelectableProject[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)

  const load = useCallback((): Promise<void> => {
    const mine = ++seq.current
    const life = lifetime.current
    const isLatest = () => life.alive && mine === seq.current
    return fetchSelectableProjects().then(
      (data) => {
        if (!isLatest()) return
        setProjects(data)
        setError(null)
        setRefreshing(false)
      },
      (e: unknown) => {
        if (!isLatest()) return
        setRefreshing(false)
        if (e instanceof ApiError && e.status === 401) {
          void refresh()
          return
        }
        setError(e instanceof ApiError ? e.message : '發生未預期的錯誤')
      },
    )
  }, [refresh])

  useEffect(() => {
    void load()
  }, [load])

  /** 背景更新（或初次載入失敗後的重試）：保留目前的清單直到新回應到達。 */
  const reload = useCallback(() => {
    setRefreshing(true)
    void load()
  }, [load])

  return { projects, error, refreshing, reload }
}

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
  const projects = useSelectableProjects()

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
  const selectable = projects.projects

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
          <div className="card__title-row">
            <h2 id="entry-form-title">{mode.kind === 'new' ? '新增工時' : '修改工時'}</h2>
            {selectable && (
              // 重疊的更新是安全的：只採用最後發出的那一次
              <button type="button" className="btn btn--small" onClick={projects.reload}>
                重新整理專案清單
              </button>
            )}
          </div>
          {!selectable && !projects.error && <p className="muted">載入專案中…</p>}
          {!selectable && projects.error && (
            <div>
              <p className="form-error" role="alert">
                無法載入專案：{projects.error}
              </p>
              <button type="button" className="btn" onClick={projects.reload}>
                重試
              </button>
            </div>
          )}
          {selectable && projects.error && (
            <div className="banner banner--warn" role="alert" data-testid="projects-refresh-error">
              <p>專案清單更新失敗：{projects.error}。表單內容已保留，可以重試。</p>
              <button type="button" className="btn btn--small" onClick={projects.reload} disabled={projects.refreshing}>
                重試更新專案清單
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
              onProjectsStale={projects.reload}
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
              onProjectsStale={projects.reload}
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
  /** 新增模式：清空並開始另一筆（外層換 key → 新的 requestId） */
  onReset: (() => void) | null
  /** 修改模式：版本衝突時重新載入這一筆 */
  onReload?: () => void
}

/**
 * 表單的三個階段：
 * - editing：可以編輯；送出時把欄位標準化成一份「提交內容」。
 * - submitting：請求進行中，表單鎖定——成功後會換新表單，不能讓「送出後才輸入的內容」被一起清掉。
 * - unknown：新增的結果不明（網路中斷、回應遺失、5xx）。表單鎖定在剛才那份提交內容，requestId 與它綁在一起；
 *   只能「重新送出原內容」（同一個 requestId、同一份內容）來確認，或明確「放棄追蹤」。
 * 明確的拒絕（欄位不合法、專案停用、每日上限…）代表後端沒有建立任何東西，回到 editing 讓使用者修正。
 */
type Phase = 'editing' | 'submitting' | 'unknown'

interface Submission {
  workDate: string
  projectId: number
  description: string
  minutes: number
}

function EntryFields({ entry, projects, today, onDone, onCancel, onReset, onReload, onProjectsStale, onListStale }: Readonly<EntryFieldsProps>) {
  const messageOf = useApiErrorMessage()
  const lifetime = useLifetime()
  // 這一次「邏輯上的新增」的識別碼。只會用來送出「同一份內容」：結果不明時鎖定表單、重送原內容；
  // 成功、確認已建立（DUPLICATE_SUBMISSION）或使用者明確開始另一筆時，外層換 key 建立新表單（新的 requestId）
  const [requestId] = useState(newRequestId)
  const [workDate, setWorkDate] = useState(entry?.workDate ?? today)
  const [projectId, setProjectId] = useState(entry ? String(entry.project.id) : '')
  // 目前選擇的專案名稱：專案之後若被停用而不在清單中，仍能顯示原本選的是哪一個
  const [projectLabel, setProjectLabel] = useState(entry ? `${entry.project.code} ${entry.project.name}` : '')
  const [description, setDescription] = useState(entry?.description ?? '')
  const [hours, setHours] = useState(entry ? minutesToHoursText(entry.minutes) : '')
  const [error, setError] = useState<string | null>(null)
  const [conflict, setConflict] = useState(false)
  const [phase, setPhase] = useState<Phase>('editing')
  // 結果不明時「已經送出過的那一份內容」；重送只送它
  const [pending, setPending] = useState<Submission | null>(null)
  const [confirmAbandon, setConfirmAbandon] = useState(false)
  // 防連按：ref 立即生效（state 要等下一次 render）
  const submitting = useRef(false)
  const locked = phase !== 'editing'

  // 選項：啟用中的專案；修改時原本的專案即使已停用也要列出（目前已允許的規則）
  const options = projects.map((p) => ({ id: p.id, label: `${p.code} ${p.name}`, disabled: false }))
  if (entry && !projects.some((p) => p.id === entry.project.id)) {
    options.push({ id: entry.project.id, label: `${entry.project.code} ${entry.project.name}（已停用）`, disabled: false })
  }
  // 目前選的專案已不在清單（例如剛被停用）：照實標示，不偷偷改選其他專案
  const selectedUnavailable = projectId !== '' && !options.some((o) => String(o.id) === projectId)
  if (selectedUnavailable) {
    options.push({ id: Number(projectId), label: `${projectLabel}（已停用或無法選擇，請改選）`, disabled: true })
  }
  const parsed = hours.trim() ? parseHours(hours) : null

  /** 鎖定時忽略任何輸入（disabled 之外的第二道保護）。 */
  const edit = (apply: () => void) => {
    if (!locked) apply()
  }

  async function send(values: Submission) {
    submitting.current = true
    setPhase('submitting')
    setError(null)
    setConfirmAbandon(false)
    const life = lifetime.current
    try {
      if (entry) await updateEntry(entry.id, { ...values, expectedVersion: entry.version })
      else await createEntry({ ...values, requestId })
      if (!life.alive) return // 已切頁：不更新畫面（POST 已送出，無法撤回）
      onDone(entry ? '已更新工時紀錄。' : '已新增工時紀錄。')
    } catch (e) {
      if (!life.alive) return
      submitting.current = false
      if (e instanceof ApiError && e.code === 'DUPLICATE_SUBMISSION') {
        // 只代表「原提交已經存在」——而送出的正是原提交（表單一直鎖定），所以沒有任何未儲存的新內容被丟掉
        onDone(`原提交已經建立（紀錄 #${String(e.details.entryId)}），沒有重複新增。若要更改內容，請在下方清單按「修改」。`)
        return
      }
      if (!entry && e instanceof ApiError && (e.status === 0 || e.status >= 500)) {
        // 結果不明：伺服器可能其實已經寫入。保留這份內容與 requestId，表單維持鎖定，不自動重送
        setPending(values)
        setPhase('unknown')
        setError(
          '無法確認原提交是否已新增（網路或伺服器問題），已重新查詢清單。表單已鎖定為剛才送出的內容：' +
            '按「重新送出原內容」會用同一個識別碼確認，不會重複建立；確認建立後，可在清單中按「修改」更改內容。',
        )
        onListStale()
        return
      }
      setPending(null)
      setPhase('editing')
      if (e instanceof ApiError && e.code === 'TIMESHEET_CHANGED') {
        setConflict(true)
        setError('這筆紀錄已在其他分頁或裝置被修改，你的變更沒有儲存。請重新載入最新內容後再修改。')
        return
      }
      if (e instanceof ApiError && (e.code === 'PROJECT_INACTIVE' || e.code === 'PROJECT_NOT_FOUND')) {
        // 畫面上的專案資料已過期：背景重新取得可選專案（失敗也不會卸載表單），並刷新清單
        onProjectsStale()
        onListStale()
      }
      if (entry && e instanceof ApiError && (e.status === 0 || e.status >= 500)) {
        setError('無法確認是否已儲存（網路或伺服器問題），已重新查詢清單，請確認後再決定是否重試。')
        onListStale()
        return
      }
      // 明確的拒絕：後端沒有建立或修改任何東西，可以修正後再送
      setError(messageOf(e))
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (submitting.current) return
    if (phase === 'unknown' && pending) {
      await send(pending) // 只重送原內容
      return
    }
    if (!workDate) return setError('請選擇日期。')
    if (workDate > today) return setError('不能填寫未來日期的工時。')
    if (!projectId) return setError('請選擇專案。')
    if (!description.trim()) return setError('請填寫工作內容。')
    const minutes = parseHours(hours)
    if (!minutes.ok) return setError(minutes.message)
    await send({ workDate, projectId: Number(projectId), description: description.trim(), minutes: minutes.minutes })
  }

  const noProjects = !entry && projects.length === 0
  let submitLabel = '送出'
  if (phase === 'submitting') submitLabel = '送出中…'
  else if (phase === 'unknown') submitLabel = '重新送出原內容'
  return (
    <form className="correction-form" onSubmit={submit} noValidate data-testid="entry-form" data-mode={entry ? 'edit' : 'new'} data-phase={phase} data-unknown={phase === 'unknown' || undefined}>
      {noProjects && (
        <p className="banner banner--warn" data-testid="no-projects">
          目前沒有可填報的專案，請先由管理員建立或啟用專案。
        </p>
      )}
      <fieldset className="form-fieldset" disabled={locked}>
        <div className="form-grid">
          <label htmlFor="ts-date">日期</label>
          <input id="ts-date" type="date" max={today} value={workDate} onChange={(e) => edit(() => setWorkDate(e.target.value))} />
          <label htmlFor="ts-project">專案</label>
          <select
            id="ts-project"
            value={projectId}
            disabled={noProjects}
            onChange={(e) =>
              edit(() => {
                setProjectId(e.target.value)
                setProjectLabel(e.target.selectedOptions[0]?.textContent ?? '')
              })
            }
          >
            <option value="">請選擇…</option>
            {options.map((o) => (
              <option key={o.id} value={o.id} disabled={o.disabled}>
                {o.label}
              </option>
            ))}
          </select>
          <label htmlFor="ts-hours">時數（小時）</label>
          <input id="ts-hours" type="text" inputMode="decimal" placeholder="例如 1.5" value={hours} onChange={(e) => edit(() => setHours(e.target.value))} />
        </div>
        {selectedUnavailable && (
          <p className="banner banner--warn small" data-testid="project-unavailable">
            原本選擇的專案已停用或目前無法選擇，請改選其他啟用中的專案（系統不會自動替你改選）。
          </p>
        )}
        <p className="muted small" data-testid="hours-preview">
          {parsed === null && '以 0.25 小時（15 分鐘）為單位：0.25＝15 分、1.5＝1 小時 30 分、7.75＝7 小時 45 分。'}
          {parsed?.ok === true && `＝ ${formatMinutes(parsed.minutes)}（${parsed.minutes} 分鐘）`}
          {parsed?.ok === false && parsed.message}
        </p>
        <label htmlFor="ts-description">工作內容（必填）</label>
        <textarea id="ts-description" rows={3} maxLength={1000} value={description} onChange={(e) => edit(() => setDescription(e.target.value))} />
      </fieldset>
      <p className="form-error" role="alert" data-testid="entry-error">
        {error}
      </p>
      {confirmAbandon && onReset && (
        <div className="banner banner--warn" role="alert" data-testid="abandon-warning">
          <p>原提交可能已經建立（只是沒有收到回應）。放棄追蹤後，請先在下方清單確認是否已有這筆，避免重複填報。</p>
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
        <button type="submit" className="btn btn--primary" disabled={phase === 'submitting' || noProjects}>
          {submitLabel}
        </button>
        {conflict && onReload && (
          <button type="button" className="btn" onClick={onReload}>
            重新載入最新內容
          </button>
        )}
        {onReset && phase !== 'unknown' && (
          <button type="button" className="btn" onClick={onReset} disabled={phase === 'submitting'}>
            清空，開始新的一筆
          </button>
        )}
        {onReset && phase === 'unknown' && !confirmAbandon && (
          <button type="button" className="btn" onClick={() => setConfirmAbandon(true)}>
            放棄追蹤，開始新的一筆
          </button>
        )}
        {onCancel && (
          <button type="button" className="btn" onClick={onCancel} disabled={phase === 'submitting'}>
            取消修改
          </button>
        )}
      </div>
    </form>
  )
}
