import { useEffect, useRef, useState } from 'react'
import type { CurrentUser } from '../api/auth'
import { ApiError, api } from '../api/http'
import { fetchEmployees, fetchManagerOptions, type AdminEmployee, type ManagerOption } from '../api/organization'
import { WORKFLOW_URL, checkWorkflowOperation, fetchWorkflow, fetchWorkflowOperation, previewWorkflow, sameWorkflowValues,
  type WorkflowIntent, type WorkflowPreview, type WorkflowSettings, type WorkflowValues, type WorkflowVersion } from '../api/leaveWorkflow'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'
import { formatDateTime } from '../lib/time'

const EMPTY: WorkflowValues = { name: '請假簽核流程', description: '', source: 'MANAGER', designatedApproverId: null }
const sourceLabel = (v: WorkflowValues) => v.source === 'MANAGER' ? '申請人的直屬主管' : `指定審核人 #${v.designatedApproverId}`
const describe = (v: WorkflowVersion | null) => v ? `版本 ${v.number}／${v.values.name}／${sourceLabel(v.values)}` : '尚無發布版本，採原直屬主管預設'

export function LeaveWorkflowPage({ user }: Readonly<{ user: CurrentUser }>) {
  if (user.role !== 'ADMIN') return <><h1>簽核流程設定</h1><p role="alert">只有管理員可以設定流程；審核權限仍依實際指派。</p></>
  return <WorkflowContent />
}

function WorkflowContent() {
  const messageOf = useApiErrorMessage()
  const [settings, setSettings] = useState<WorkflowSettings | null>(null)
  const [form, setForm] = useState<WorkflowValues>(EMPTY)
  const [options, setOptions] = useState<ManagerOption[]>([])
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [intent, setIntent] = useState<WorkflowIntent | null>(null)
  const [unknown, setUnknown] = useState(false)
  const [confirmPublish, setConfirmPublish] = useState(false)
  const [reason, setReason] = useState('')
  const [query, setQuery] = useState('')
  const [employees, setEmployees] = useState<AdminEmployee[]>([])
  const [employeeId, setEmployeeId] = useState('')
  const [previewDraft, setPreviewDraft] = useState(false)
  const [preview, setPreview] = useState<WorkflowPreview | null>(null)
  const [previewError, setPreviewError] = useState('')
  const alive = useRef(true), loadingSeq = useRef(0), previewSeq = useRef(0), searchSeq = useRef(0), busyRef = useRef(false)
  const baseline = settings?.draft?.values ?? settings?.current?.values ?? EMPTY
  const dirty = !sameWorkflowValues(form, baseline)
  const unsavedRef = useRef(false)
  useEffect(() => { unsavedRef.current = dirty || !!intent }, [dirty, intent])

  useEffect(() => {
    alive.current = true
    const seq = ++loadingSeq.current
    void Promise.all([fetchWorkflow(), fetchManagerOptions()]).then(([s, o]) => {
      if (!alive.current || seq !== loadingSeq.current) return
      setSettings(s); setForm(s.draft?.values ?? s.current?.values ?? EMPTY); setOptions(o)
    }).catch(e => { if (alive.current && seq === loadingSeq.current) setError(messageOf(e)) })
    const warn = (event: BeforeUnloadEvent) => { if (unsavedRef.current) event.preventDefault() }
    const onHash = () => {
      if (unsavedRef.current && window.location.hash !== '#/admin/leave/workflow' && !window.confirm('尚有未儲存內容或結果待查證。離開會失去本頁追蹤，請先查證實際版本。確定離開？'))
        window.location.hash = '#/admin/leave/workflow'
    }
    window.addEventListener('beforeunload', warn); window.addEventListener('hashchange', onHash)
    return () => { alive.current = false; window.removeEventListener('beforeunload', warn); window.removeEventListener('hashchange', onHash) }
  }, [messageOf])

  async function reload(preserve = true) {
    if (busyRef.current) return
    const seq = ++loadingSeq.current
    try {
      const s = await fetchWorkflow()
      if (!alive.current || seq !== loadingSeq.current) return
      setSettings(s); setPreview(null); ++previewSeq.current
      if (!s.draft) setPreviewDraft(false)
      if (!preserve) { setForm(s.draft?.values ?? s.current?.values ?? EMPTY); setReason(''); setConfirmPublish(false) }
      setNotice(preserve ? '已讀取最新版本，保留目前輸入；請比較差異後再操作。' : '已載入最新設定。')
    } catch (e) { if (alive.current && seq === loadingSeq.current) setError(messageOf(e)) }
  }
  function accepted(result: unknown, op: WorkflowIntent) {
    const checked = checkWorkflowOperation(result, op)
    if (!checked) throw new Error('無法確認伺服器回應是否對應原操作')
    setIntent(null); setUnknown(false); setConfirmPublish(false); setReason('')
    if (op.action === 'PUBLISHED') setPreviewDraft(false)
    setNotice(`${op.action === 'PUBLISHED' ? '已確認發布' : '已確認儲存'}版本 ${checked.version.number}；發布僅影響新提交。`)
    setSettings(s => s ? { ...s, current: op.action === 'PUBLISHED' ? checked.version : s.current, draft: op.action === 'PUBLISHED' ? null : checked.version } : s)
    setForm(checked.version.values);setError('');setPreview(null);++previewSeq.current
    const seq = ++loadingSeq.current
    void fetchWorkflow().then(s => { if (alive.current && seq === loadingSeq.current) setSettings(s) })
      .catch(() => { if (alive.current && seq === loadingSeq.current) setError('原操作已確認，但版本歷程未能重新載入，請重新查詢版本。') })
  }
  async function execute(op: WorkflowIntent, wasUnknown = false, verify = false) {
    if (busyRef.current) return
    busyRef.current = true; setBusy(true); setIntent(op); setError(''); setNotice(''); ++loadingSeq.current
    try {
      const result = verify ? await fetchWorkflowOperation(op.requestId) : await api<unknown>(op.url, { method: op.method, body: op.body })
      if (alive.current) accepted(result, op)
    } catch (e) {
      if (!alive.current) return
      const uncertain = wasUnknown || verify || !(e instanceof ApiError) || e.status === 0 || e.status >= 500
      if (uncertain) { setUnknown(true); setError('結果尚未確認，請保留原操作識別碼查證或以原鍵重試。' + messageOf(e)) }
      else { setIntent(null); setUnknown(false); setError(messageOf(e)); setConfirmPublish(false) }
    } finally { busyRef.current = false; if (alive.current) setBusy(false) }
  }
  function save() {
    if (!settings || intent || busyRef.current) return
    const values = { ...form, name: form.name.trim(), description: form.description.trim() }
    if (!values.name || (values.source === 'DESIGNATED' && !values.designatedApproverId)) { setError('請填寫流程名稱及審核人來源。'); return }
    const d = settings.draft, requestId = crypto.randomUUID()
    const body = { requestId, expectedPublishedId: settings.current?.id ?? null, expectedVersion: d?.rowVersion ?? null, values }
    void execute({ requestId, action: d ? 'DRAFT_SAVED' : 'DRAFT_CREATED', targetId: d?.id ?? null, expectedRowVersion: d?.rowVersion ?? null,
      basePublishedId: settings.current?.id ?? null, values, method: d ? 'PUT' : 'POST', url: WORKFLOW_URL + '/drafts' + (d ? '/' + d.id : ''), body })
  }
  function publish() {
    const d = settings?.draft
    if (!d || dirty || intent || busyRef.current) return
    if (!reason.trim()) { setError('請填寫發布原因。'); return }
    const requestId = crypto.randomUUID()
    void execute({ requestId, action: 'PUBLISHED', targetId: d.id, expectedRowVersion: d.rowVersion, basePublishedId: d.basePublishedId,
      values: d.values, method: 'POST', url: `${WORKFLOW_URL}/drafts/${d.id}/publish`, body: { requestId, expectedVersion: d.rowVersion, reason: reason.trim() } })
  }
  async function search() {
    const seq = ++searchSeq.current;setPreview(null);++previewSeq.current;setEmployeeId('')
    try { const r = await fetchEmployees({ q: query, status: 'ALL', page: 0, size: 20 }); if (alive.current && seq === searchSeq.current) setEmployees(r.items) }
    catch (e) { if (alive.current && seq === searchSeq.current) setPreviewError(messageOf(e)) }
  }
  async function loadPreview() {
    if (!employeeId || !settings) return
    const seq = ++previewSeq.current;setPreview(null);setPreviewError('')
    try {
      const r = await previewWorkflow(Number(employeeId), previewDraft ? settings.draft?.id ?? null : null)
      if (alive.current && seq === previewSeq.current) setPreview(r)
    } catch (e) { if (alive.current && seq === previewSeq.current) setPreviewError(messageOf(e)) }
  }
  const locked = busy || !!intent
  return <>
    <h1>簽核流程設定</h1>
    <p>請假單關卡：個人指定審核人優先於共用流程；高優先來源失效會阻擋，不會自動換人。設定權不等於審核權。</p>
    {notice && <p className="notice notice--success" role="status" data-testid="workflow-notice">{notice}</p>}
    {error && <p className="form-error" role="alert">{error}</p>}
    {!settings && !error && <p>載入中…</p>}
    <div className="actions"><button className="btn" disabled={busy} onClick={() => void reload(true)}>重新查詢版本（保留輸入）</button>
      <button className="btn" disabled={locked} onClick={() => { if (!dirty || window.confirm('放棄未儲存輸入並載入最新版本？')) void reload(false) }}>放棄輸入並載入最新</button></div>
    {unknown && intent && <section className="banner banner--warn" data-testid="workflow-unknown"><h2>操作結果待查證</h2>
      <p className="cell--wrap">操作識別碼：{intent.requestId}。原本文保持鎖定，查不到不代表未送出；不會自動重送。</p>
      <div className="actions"><button className="btn" disabled={busy} onClick={() => void execute(intent, true, true)}>查證原操作</button>
        <button className="btn" disabled={busy} onClick={() => void execute(intent, true)}>以原識別碼重試</button>
        <button className="btn" disabled={busy} onClick={() => { if (window.confirm('放棄追蹤不會取消伺服器操作；可能已儲存或發布。請重新查詢後確認，確定放棄？')) { setIntent(null);setUnknown(false);void reload(false) } }}>放棄追蹤並查詢</button></div>
    </section>}
    {settings && <div className="stack">
      <section className="card" data-testid="workflow-current"><h2>目前發布版本</h2><p>{describe(settings.current)}</p><p className="small">發布僅影響之後真正的新提交，既有待審與原提交快照不會改派。</p></section>
      <section className="card"><h2>{settings.draft ? `編輯草稿版本 ${settings.draft.number}（編輯版 ${settings.draft.rowVersion}）` : '建立後續草稿'}</h2>
        {dirty && <p className="banner banner--warn" data-testid="workflow-dirty">有尚未儲存的變更，請先儲存再發布或預覽草稿。</p>}
        <fieldset disabled={locked} className="form-grid workflow-form">
          <label htmlFor="wf-name">流程名稱</label><input id="wf-name" maxLength={100} value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
          <label htmlFor="wf-description">說明</label><textarea id="wf-description" maxLength={1000} value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} />
          <label htmlFor="wf-source">單關卡審核人來源</label><select id="wf-source" value={form.source} onChange={e => { setForm(f => ({ ...f, source: e.target.value as WorkflowValues['source'], designatedApproverId: null }));setPreview(null);++previewSeq.current }}>
            <option value="MANAGER">申請人的直屬主管</option><option value="DESIGNATED">指定一名合格主管／管理員</option></select>
          {form.source === 'DESIGNATED' && <><label htmlFor="wf-approver">指定審核人</label><select id="wf-approver" value={form.designatedApproverId ?? ''} onChange={e => setForm(f => ({ ...f, designatedApproverId: e.target.value ? Number(e.target.value) : null }))}>
            <option value="">請選擇</option>{form.designatedApproverId && !options.some(o => o.id === form.designatedApproverId) && <option value={form.designatedApproverId}>目前設定 #{form.designatedApproverId}（需重新確認資格）</option>}
            {options.map(o => <option key={o.id} value={o.id}>{o.displayName}（{o.employeeNo}）</option>)}</select></>}
        </fieldset>
        <p className="small">固定一關：請假審核。指定人不能審自己的申請；發布不代表每一位員工都有合法審核人，請使用員工預覽。</p>
        <div className="actions"><button className="btn btn--primary" disabled={locked} onClick={save}>儲存草稿</button>
          <button className="btn" disabled={locked || dirty || !settings.draft} onClick={() => { setConfirmPublish(true);setError('') }}>檢查發布差異</button></div>
        {confirmPublish && settings.draft && <section className="banner banner--warn" data-testid="workflow-publish-confirm"><h3>確認發布新版本</h3>
          <p>目前：{describe(settings.current)}</p><p>發布後：{describe(settings.draft)}</p>
          <p className="cell--wrap">說明：{settings.current?.values.description || '（無）'} → {settings.draft.values.description || '（無）'}</p>
          <p>個人指定仍優先；只影響新提交，不修改既有待審。</p>
          <label htmlFor="wf-reason">發布原因（必填）</label><textarea id="wf-reason" disabled={locked} maxLength={500} value={reason} onChange={e => setReason(e.target.value)} />
          <div className="actions"><button className="btn btn--danger" disabled={locked || dirty} onClick={publish}>確認發布</button><button className="btn" disabled={locked} onClick={() => setConfirmPublish(false)}>取消發布</button></div>
        </section>}
      </section>
      <section className="card"><h2>員工唯讀預覽</h2><p>採目前正式規則或已儲存草稿；個人指定依然優先。預覽不建立假單，真正提交會重新驗證。</p>
        <label htmlFor="wf-search">員工名稱／帳號／員編</label><input id="wf-search" value={query} onChange={e => { setQuery(e.target.value);++searchSeq.current }} />
        <button className="btn" onClick={() => void search()}>搜尋預覽員工</button>
        <div className="form-grid"><label htmlFor="wf-employee">員工（最多前20筆）</label><select id="wf-employee" value={employeeId} onChange={e => { setEmployeeId(e.target.value);setPreview(null);++previewSeq.current }}><option value="">請選擇</option>{employees.map(e => <option key={e.id} value={e.id}>{e.displayName}（{e.employeeNo}）{!e.enabled && '／停用'}</option>)}</select>
          <label htmlFor="wf-preview-draft">預覽已儲存草稿</label><input id="wf-preview-draft" type="checkbox" checked={previewDraft} disabled={!settings.draft} onChange={e => { setPreviewDraft(e.target.checked);setPreview(null);++previewSeq.current }} /></div>
        <button className="btn" disabled={!employeeId || (previewDraft && (!settings.draft || dirty))} onClick={() => void loadPreview()}>預覽解析結果</button>
        {previewError && <p className="form-error" role="alert">{previewError}</p>}
        {preview && <div className="banner banner--info" data-testid="workflow-preview"><p>{preview.employee.displayName}／來源：{preview.source === 'PERSONAL' ? '個人指定' : preview.source === 'WORKFLOW' ? '請假流程' : '原直屬主管預設'}{preview.personalOverride && '（優先於共用流程）'}</p>
          <p>流程版本：{preview.workflowVersionNumber ?? '無'}／{preview.workflowName ?? '不適用'}／關卡：{preview.stageName}</p><p>審核人：{preview.approver?.displayName ?? '無合法審核人'}</p>{preview.problem && <p role="alert">{preview.problem}</p>}</div>}
      </section>
      <section className="card"><h2>版本與發布紀錄</h2><ul className="history-list" data-testid="workflow-versions">{settings.versions.map(v => <li key={v.id}>{describe(v)}／{v.status === 'PUBLISHED' ? '已發布（不可修改）' : '草稿'}／{v.publishReason}</li>)}</ul>
        <ul className="history-list" data-testid="workflow-history">{settings.history.map(h => <li key={h.requestId}><strong>{h.action === 'PUBLISHED' ? '發布' : '儲存草稿'}／版本 {h.after.number}</strong><p>{h.actor.displayName}／{formatDateTime(h.occurredAt)}／{h.reason}</p>
          <p className="cell--wrap">{describe(h.before)} → {describe(h.after)}；說明：{h.before?.values.description || '（無）'} → {h.after.values.description || '（無）'}</p></li>)}</ul>
      </section>
    </div>}
  </>
}
