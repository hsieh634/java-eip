import { useState, type FormEvent } from 'react'
import type { CurrentUser } from '../api/auth'
import { ApiError } from '../api/http'
import {
  fetchAdminLeave,
  fetchAdminLeaves,
  fetchApproverSetting,
  fetchApproverSettingHistory,
  fetchApproverSettings,
  reassignLeave,
  updateApproverSetting,
  type ApproverSetting,
  type LeaveDetail,
  type LeaveRequest,
} from '../api/leave'
import { fetchEmployees, fetchManagerOptions, type ManagerOption } from '../api/organization'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'
import { LeaveDetailDialog, LeaveHistoryList } from '../leave/LeaveDetailDialog'
import { LeaveList } from '../leave/LeaveList'
import { APPROVER_SOURCE_LABEL, describe } from '../leave/labels'
import { useLifetime } from '../lib/useLifetime'
import { useLoad } from '../lib/useLoad'

type Tab = 'requests' | 'settings'

/**
 * ADMIN「請假管理」：查詢申請與歷程、改派待審申請、管理指定請假審核人。
 * 這裡沒有核准或退回——即使是 ADMIN，也只能在「請假審核」處理指派給自己的申請。
 */
export function LeaveAdminPage({ user }: Readonly<{ user: CurrentUser }>) {
  const [tab, setTab] = useState<Tab>('requests')
  if (user.role !== 'ADMIN') {
    return (
      <>
        <h1>請假管理</h1>
        <p className="form-error" role="alert">
          只有管理員可以使用這個功能。
        </p>
      </>
    )
  }
  return (
    <>
      <h1>請假管理</h1>
      <p className="muted">
        查詢所有請假申請、改派待審申請，以及設定「指定請假審核人」。這裡<strong>不能</strong>核准或退回：只有目前的審核人能在「請假審核」處理；
        你自己的申請也不能由你改派。已核准的申請不能修改或取消（本階段沒有取消流程）。
      </p>
      <div className="tabs" role="tablist" aria-label="請假管理">
        <button type="button" role="tab" id="tab-leave-requests" aria-selected={tab === 'requests'} className={tab === 'requests' ? 'tab tab--active' : 'tab'} onClick={() => setTab('requests')}>
          申請查詢與改派
        </button>
        <button type="button" role="tab" id="tab-leave-settings" aria-selected={tab === 'settings'} className={tab === 'settings' ? 'tab tab--active' : 'tab'} onClick={() => setTab('settings')}>
          指定請假審核人
        </button>
      </div>
      <div role="tabpanel">{tab === 'requests' ? <RequestsPanel user={user} /> : <SettingsPanel />}</div>
    </>
  )
}

// ================================================================ 申請查詢與改派

function RequestsPanel({ user }: Readonly<{ user: CurrentUser }>) {
  const [seq, setSeq] = useState(0)
  const [open, setOpen] = useState<LeaveRequest | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const managers = useLoad(() => fetchManagerOptions(), 'leave-admin-managers')
  const refresh = () => setSeq((s) => s + 1)
  return (
    <div className="stack">
      {notice && (
        <p className="notice notice--success" role="status" data-testid="leave-admin-notice">
          {notice}
        </p>
      )}
      <section className="card card--records" aria-labelledby="admin-leave-title">
        <div className="card__title-row">
          <h2 id="admin-leave-title">所有請假申請</h2>
          <button type="button" className="btn btn--small" onClick={refresh}>
            重新整理
          </button>
        </div>
        <LeaveList
          loadKey="admin-leaves"
          load={(q) => fetchAdminLeaves(q, null)}
          refreshSeq={seq}
          showApplicant
          emptyText="沒有符合條件的申請。"
          onOpen={setOpen}
          testId="admin-leaves"
        />
      </section>
      {open && (
        <LeaveDetailDialog
          title={`請假申請 #${open.id}`}
          loadKey={`admin-leave:${open.id}:${seq}`}
          loader={() => fetchAdminLeave(open.id)}
          onClose={() => setOpen(null)}
          actions={(detail, reload) => {
            if (detail.request.status !== 'PENDING') return <p className="muted small">已結案的申請不能改派。</p>
            if (detail.request.applicant.id === user.id) {
              return (
                <p className="banner banner--info small" data-testid="self-reassign-note">
                  這是你自己的申請：你不能改派，也不能審核。需要時請另一位管理員改派。
                </p>
              )
            }
            if (managers.state.kind === 'error') {
              return (
                <p className="form-error small" role="alert">
                  無法載入審核人選項：{managers.state.message}{' '}
                  <button type="button" className="btn btn--small" onClick={managers.reload}>
                    重試
                  </button>
                </p>
              )
            }
            if (managers.state.kind !== 'ready') return <p className="muted small">載入審核人選項中…</p>
            return (
              <ReassignAction
                detail={detail}
                options={managers.state.data}
                onReload={reload}
                onDone={(message) => {
                  setNotice(message)
                  refresh()
                }}
              />
            )
          }}
        />
      )}
    </div>
  )
}

function ReassignAction({
  detail,
  options,
  onReload,
  onDone,
}: Readonly<{ detail: LeaveDetail; options: ManagerOption[]; onReload: () => void; onDone: (message: string) => void }>) {
  const messageOf = useApiErrorMessage()
  const lifetime = useLifetime()
  const r = detail.request
  const choices = options.filter((o) => o.id !== r.applicant.id && o.id !== r.approver.id)
  const [approverId, setApproverId] = useState('')
  const [reason, setReason] = useState('')
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [stale, setStale] = useState(false)
  const target = choices.find((o) => String(o.id) === approverId)

  function review(event: FormEvent) {
    event.preventDefault()
    if (!target) return setError('請選擇新的審核人。')
    if (!reason.trim()) return setError('改派原因必填。')
    setError(null)
    setConfirming(true)
  }

  async function confirm() {
    if (busy || !target) return
    setBusy(true)
    setError(null)
    const life = lifetime.current
    try {
      await reassignLeave(r.id, { approverId: target.id, expectedVersion: r.version, reason: reason.trim() })
      if (!life.alive) return
      setBusy(false)
      setConfirming(false)
      onDone(`已將申請 #${r.id} 改派給 ${target.displayName}。`)
      onReload()
    } catch (e) {
      if (!life.alive) return
      setBusy(false)
      setStale(e instanceof ApiError && (e.code === 'LEAVE_CHANGED' || e.code === 'LEAVE_NOT_PENDING'))
      setError(messageOf(e))
    }
  }

  return (
    <form className="correction-form" onSubmit={review} noValidate data-testid="reassign-form">
      <h3>改派</h3>
      {!r.approver.valid && <p className="banner banner--warn small">目前審核人已停用或不是主管／管理員，無法審核，請改派。</p>}
      <fieldset className="form-fieldset" disabled={confirming}>
        <label htmlFor="reassign-to">新的審核人（啟用中的主管或管理員；不含申請人與目前審核人）</label>
        <select id="reassign-to" value={approverId} onChange={(e) => setApproverId(e.target.value)}>
          <option value="">請選擇…</option>
          {choices.map((o) => (
            <option key={o.id} value={o.id}>
              {o.displayName}（{o.employeeNo}）
            </option>
          ))}
        </select>
        <label htmlFor="reassign-reason">改派原因（必填）</label>
        <textarea id="reassign-reason" rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      </fieldset>
      {confirming && target && (
        <div className="banner banner--warn" data-testid="reassign-confirm">
          <p>
            確認改派：{r.applicant.displayName} 的 {describe(r)}
          </p>
          <p>
            審核人：{r.approver.displayName} → <strong>{target.displayName}</strong>
          </p>
          <p className="small">改派後原審核人不能再查看或處理這筆申請。原因：{reason.trim()}</p>
        </div>
      )}
      <p className="form-error" role="alert">
        {error}
      </p>
      <div className="actions">
        {!confirming && (
          <button type="submit" className="btn btn--primary">
            檢查改派內容
          </button>
        )}
        {confirming && !stale && (
          <button type="button" className="btn btn--danger" onClick={() => void confirm()} disabled={busy}>
            {busy ? '處理中…' : '確認改派'}
          </button>
        )}
        {stale && (
          <button type="button" className="btn" onClick={onReload}>
            重新載入
          </button>
        )}
        {confirming && (
          <button type="button" className="btn" onClick={() => setConfirming(false)} disabled={busy}>
            返回修改
          </button>
        )}
      </div>
    </form>
  )
}

// ================================================================ 指定請假審核人

function SettingsPanel() {
  const [seq, setSeq] = useState(0)
  const settings = useLoad(() => fetchApproverSettings(), `approver-settings:${seq}`)
  const managers = useLoad(() => fetchManagerOptions(), `approver-setting-managers:${seq}`)
  const [selected, setSelected] = useState<number | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  return (
    <div className="stack">
      <p className="muted">
        「個人指定請假審核人」優先於已發布的共用流程；尚無發布流程時才採直屬主管。只影響<strong>之後送出</strong>的申請，不會改既有申請，也不改組織主管關係。
        移除個人指定後依共用流程／主管預設解析；高優先設定失效時不會自動換人。
      </p>
      {notice && (
        <p className="notice notice--success" role="status" data-testid="setting-notice">
          {notice}
        </p>
      )}
      <section className="card" aria-labelledby="setting-pick-title">
        <h2 id="setting-pick-title">設定某位員工</h2>
        <EmployeePicker onPick={setSelected} />
        {selected !== null && managers.state.kind === 'ready' && (
          <SettingEditor
            key={`${selected}:${seq}`}
            employeeId={selected}
            managers={managers.state.data}
            onSaved={(message) => {
              setNotice(message)
              setSeq((s) => s + 1)
            }}
          />
        )}
        {managers.state.kind === 'error' && (
          <p className="form-error" role="alert">
            無法載入審核人選項：{managers.state.message}
            <button type="button" className="btn btn--small" onClick={managers.reload}>
              重試
            </button>
          </p>
        )}
      </section>
      <section className="card card--records" aria-labelledby="setting-list-title">
        <h2 id="setting-list-title">目前的指定設定</h2>
        {settings.state.kind === 'loading' && <p className="muted">載入中…</p>}
        {settings.state.kind === 'error' && (
          <div>
            <p className="form-error" role="alert">
              無法載入設定：{settings.state.message}
            </p>
            <button type="button" className="btn" onClick={settings.reload}>
              重試
            </button>
          </div>
        )}
        {settings.state.kind === 'ready' && settings.state.data.length === 0 && (
          <p className="muted" data-testid="settings-empty">
            目前沒有個人指定設定，新提交依已發布流程解析；尚無流程時採直屬主管。
          </p>
        )}
        {settings.state.kind === 'ready' && settings.state.data.length > 0 && (
          <table className="records-table" data-testid="settings-table">
            <thead>
              <tr>
                <th scope="col">員工</th>
                <th scope="col">指定審核人</th>
                <th scope="col">直屬主管</th>
                <th scope="col">目前送出時的審核人</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              {settings.state.data.map((s) => (
                <tr key={s.employee.id} data-employee-no={s.employee.employeeNo}>
                  <td data-label="員工">
                    {s.employee.displayName}（{s.employee.employeeNo}）
                  </td>
                  <td data-label="指定審核人">
                    {s.designatedApprover?.displayName}
                    {s.designatedApprover && !s.designatedApprover.valid && <span className="tag">已無效</span>}
                  </td>
                  <td data-label="直屬主管">{s.manager?.displayName ?? '（無）'}</td>
                  <td data-label="目前送出時的審核人">{effectiveText(s)}</td>
                  <td data-label="操作">
                    <button type="button" className="btn btn--small" onClick={() => setSelected(s.employee.id)}>
                      編輯
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  )
}

function effectiveText(s: ApproverSetting): string {
  return s.effective.approver ? `${s.effective.approver.displayName}（${APPROVER_SOURCE_LABEL[s.effective.source]}）` : `無法送出：${s.effective.problem ?? ''}`
}

/** 以姓名、帳號或員工編號搜尋員工（沿用組織管理的員工清單 API）。 */
function EmployeePicker({ onPick }: Readonly<{ onPick: (id: number) => void }>) {
  const [q, setQ] = useState('')
  const [query, setQuery] = useState<string | null>(null)
  const results = useLoad(query === null ? null : () => fetchEmployees({ q: query, status: 'ALL', page: 0, size: 20 }), `leave-setting-search:${query}`)
  return (
    <div>
      <form
        className="range-form"
        onSubmit={(e) => {
          e.preventDefault()
          setQuery(q)
        }}
        data-testid="setting-search"
      >
        <div className="range-field">
          <label htmlFor="setting-q">員工</label>
          <input id="setting-q" type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="姓名、帳號或員工編號" />
        </div>
        <button type="submit" className="btn">
          搜尋
        </button>
      </form>
      {results.state.kind === 'loading' && query !== null && <p className="muted">搜尋中…</p>}
      {results.state.kind === 'error' && (
        <p className="form-error" role="alert">
          無法搜尋員工：{results.state.message}
          <button type="button" className="btn btn--small" onClick={results.reload}>
            重試
          </button>
        </p>
      )}
      {results.state.kind === 'ready' && results.state.data.items.length === 0 && <p className="muted">沒有符合的員工。</p>}
      {results.state.kind === 'ready' && results.state.data.items.length > 0 && (
        <ul className="pick-list" data-testid="setting-search-results">
          {results.state.data.items.map((e) => (
            <li key={e.id}>
              <button type="button" className="btn btn--small" data-username={e.username} onClick={() => onPick(e.id)}>
                {e.displayName}（{e.employeeNo}）
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** 讀取目前設定 → 選擇審核人或「不指定」→ 原因 → 儲存（附版本）。衝突時保留輸入，明確重新載入。 */
function SettingEditor({ employeeId, managers, onSaved }: Readonly<{ employeeId: number; managers: ManagerOption[]; onSaved: (message: string) => void }>) {
  const [loadSeq, setLoadSeq] = useState(0)
  const current = useLoad(() => fetchApproverSetting(employeeId), `approver-setting:${employeeId}:${loadSeq}`)
  const history = useLoad(() => fetchApproverSettingHistory(employeeId), `approver-setting-history:${employeeId}:${loadSeq}`)
  if (current.state.kind === 'loading') return <p className="muted">載入設定中…</p>
  if (current.state.kind === 'error') {
    return (
      <div>
        <p className="form-error" role="alert">
          無法載入設定：{current.state.message}
        </p>
        <button type="button" className="btn" onClick={current.reload}>
          重試
        </button>
      </div>
    )
  }
  const s = current.state.data
  return (
    <div data-testid="setting-editor" data-employee-no={s.employee.employeeNo}>
      <dl className="compare">
        <dt>員工</dt>
        <dd>
          {s.employee.displayName}（{s.employee.employeeNo}）
        </dd>
        <dt>直屬主管</dt>
        <dd>{s.manager?.displayName ?? '（無）'}</dd>
        <dt>目前指定</dt>
        <dd data-testid="setting-current">
          {s.designatedApprover ? `${s.designatedApprover.displayName}（${s.designatedApprover.employeeNo}）${s.designatedApprover.valid ? '' : '（已無效）'}` : '（未指定，依共用流程／主管預設）'}
        </dd>
        <dt>目前送出時的審核人</dt>
        <dd>{effectiveText(s)}</dd>
      </dl>
      <SettingForm key={`${s.version}`} setting={s} managers={managers} onSaved={onSaved} onReloadLatest={() => setLoadSeq((n) => n + 1)} />
      <h3>設定歷程</h3>
      {history.state.kind === 'ready' ? (
        <LeaveHistoryList entries={history.state.data} />
      ) : history.state.kind === 'error' ? (
        <p className="form-error">無法載入歷程：{history.state.message}</p>
      ) : (
        <p className="muted">載入中…</p>
      )}
    </div>
  )
}

function SettingForm({
  setting,
  managers,
  onSaved,
  onReloadLatest,
}: Readonly<{ setting: ApproverSetting; managers: ManagerOption[]; onSaved: (message: string) => void; onReloadLatest: () => void }>) {
  const messageOf = useApiErrorMessage()
  const lifetime = useLifetime()
  const [approverId, setApproverId] = useState(setting.designatedApprover ? String(setting.designatedApprover.id) : '')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [conflict, setConflict] = useState(false)
  const choices = managers.filter((m) => m.id !== setting.employee.id)
  const original = setting.designatedApprover
  // 原指定審核人若已停用或降成 EMPLOYEE，就不在「有效審核人」選項中。為了讓受控的 value 有對應選項，
  // 另外列出一個「目前指定（已無效）」的保留選項：它只用來表示原值（disabled，不能被新選取），
  // 管理員可以直接改選「不指定」或另一位有效審核人（T05 複審 R3）
  const originalMissing = original !== null && !choices.some((m) => m.id === original.id)
  const labelOf = (id: string): string => {
    if (id === '') return '（未指定，依共用流程／主管預設）'
    if (original && String(original.id) === id) return `${original.displayName}（${original.employeeNo}）${original.valid ? '' : '（已無效）'}`
    const m = choices.find((x) => String(x.id) === id)
    return m ? `${m.displayName}（${m.employeeNo}）` : '（不在可選清單中）'
  }
  const before = original ? labelOf(String(original.id)) : '（未指定）'
  const after = labelOf(approverId)

  async function save(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    if ((original ? String(original.id) : '') === approverId) return setError('內容與目前相同，沒有需要儲存的變更。')
    if (!reason.trim()) return setError('修改原因必填。')
    setBusy(true)
    setError(null)
    const life = lifetime.current
    try {
      await updateApproverSetting(setting.employee.id, { approverId: approverId === '' ? null : Number(approverId), expectedVersion: setting.version, reason: reason.trim() })
      if (!life.alive) return
      onSaved(`已更新 ${setting.employee.displayName} 的指定請假審核人：${before} → ${after}。只影響之後送出的申請。`)
    } catch (e) {
      if (!life.alive) return
      setBusy(false)
      setConflict(e instanceof ApiError && e.code === 'APPROVER_SETTING_CHANGED')
      setError(messageOf(e))
    }
  }

  return (
    <form className="correction-form" onSubmit={save} noValidate data-testid="setting-form">
      <label htmlFor="setting-approver">指定請假審核人</label>
      <select id="setting-approver" value={approverId} onChange={(e) => setApproverId(e.target.value)}>
        <option value="">不指定（依共用流程／主管預設）</option>
        {originalMissing && original && (
          <option value={original.id} disabled data-testid="setting-original-invalid">
            目前指定：{original.displayName}（{original.employeeNo}）{original.valid ? '' : '（已無效）'}
          </option>
        )}
        {choices.map((m) => (
          <option key={m.id} value={m.id}>
            {m.displayName}（{m.employeeNo}）
          </option>
        ))}
      </select>
      <p className="small" data-testid="setting-change">
        變更：{before} → <strong>{after}</strong>
      </p>
      <label htmlFor="setting-reason">修改原因（必填）</label>
      <textarea id="setting-reason" rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      <p className="form-error" role="alert">
        {error}
      </p>
      <div className="actions">
        <button type="submit" className="btn btn--primary" disabled={busy}>
          {busy ? '儲存中…' : '確認儲存'}
        </button>
        {originalMissing && original && !original.valid && (
        <p className="banner banner--warn small" data-testid="setting-invalid-warning">
          目前指定的 {original.displayName} 已停用或不是主管／管理員，這位員工目前無法送出請假。請改選「不指定（依共用流程／主管預設）」或另一位有效審核人；系統不會自動清除。
        </p>
      )}
      {conflict && (
          <button type="button" className="btn" onClick={onReloadLatest}>
            載入最新設定（會取代目前輸入）
          </button>
        )}
      </div>
    </form>
  )
}
