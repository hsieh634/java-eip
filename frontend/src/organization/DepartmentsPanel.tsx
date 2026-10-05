import { useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../api/http'
import {
  createDepartment,
  fetchDepartmentHistory,
  fetchDepartments,
  updateDepartment,
  type Department,
} from '../api/organization'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'
import { formatDateTime } from '../lib/time'
import { useLifetime } from '../lib/useLifetime'
import { useLoad } from '../lib/useLoad'
import { OrgHistoryDialog } from './OrgHistoryDialog'

const CODE = /^[A-Z0-9_-]{2,32}$/

type Action = { kind: 'edit' | 'history'; department: Department }

/** 部門管理：平面結構（沒有上下層）；不刪除，只停用／重新啟用。 */
export function DepartmentsPanel() {
  const [seq, setSeq] = useState(0)
  const departments = useLoad(() => fetchDepartments(), `org-departments:${seq}`)
  const [action, setAction] = useState<Action | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const reload = () => setSeq((s) => s + 1)
  const done = (message: string) => {
    setAction(null)
    setNotice(message)
    reload()
  }

  return (
    <div className="stack">
      {notice && (
        <p className="notice notice--success" role="status" data-testid="department-notice">
          {notice}
        </p>
      )}
      <section className="card" aria-labelledby="create-department-title">
        <h2 id="create-department-title">建立部門</h2>
        <CreateDepartmentForm onCreated={(d) => done(`已建立部門 ${d.code} ${d.name}。`)} />
      </section>

      <section className="card card--records" aria-labelledby="department-list-title">
        <div className="card__title-row">
          <h2 id="department-list-title">部門清單</h2>
          <button type="button" className="btn btn--small" onClick={reload}>
            重新整理
          </button>
        </div>
        {departments.state.kind === 'loading' && <p className="muted">載入中…</p>}
        {departments.state.kind === 'error' && (
          <div>
            <p className="form-error" role="alert">
              無法載入部門：{departments.state.message}
            </p>
            <button type="button" className="btn" onClick={departments.reload}>
              重試
            </button>
          </div>
        )}
        {departments.state.kind === 'ready' && departments.state.data.length === 0 && (
          <p className="muted" data-testid="department-empty">
            尚未建立任何部門。員工沒有部門也能正常登入、打卡與填工時。
          </p>
        )}
        {departments.state.kind === 'ready' && departments.state.data.length > 0 && (
          <table className="records-table" data-testid="department-table">
            <thead>
              <tr>
                <th scope="col">代碼</th>
                <th scope="col">名稱</th>
                <th scope="col">啟用中的員工</th>
                <th scope="col">狀態</th>
                <th scope="col">最後修改</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              {departments.state.data.map((d) => (
                <tr key={d.id} data-department-code={d.code} data-active={d.active} className={d.active ? undefined : 'row--inactive'}>
                  <td data-label="代碼">{d.code}</td>
                  <td data-label="名稱">{d.name}</td>
                  <td data-label="啟用中的員工">{d.activeEmployeeCount} 人</td>
                  <td data-label="狀態">
                    <span className={d.active ? 'status-chip status-chip--working' : 'status-chip status-chip--voided'}>{d.active ? '啟用' : '已停用'}</span>
                  </td>
                  <td data-label="最後修改">{formatDateTime(d.updatedAt)}</td>
                  <td data-label="操作">
                    <div className="actions">
                      <button type="button" className="btn btn--small" onClick={() => setAction({ kind: 'edit', department: d })}>
                        編輯
                      </button>
                      <button type="button" className="btn btn--small" onClick={() => setAction({ kind: 'history', department: d })}>
                        歷程
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      {action?.kind === 'edit' && (
        <EditDepartmentDialog department={action.department} onClose={() => setAction(null)} onDone={done} />
      )}
      {action?.kind === 'history' && (
        <OrgHistoryDialog
          title={`部門歷程：${action.department.code} ${action.department.name}`}
          loadKey={`department-history:${action.department.id}`}
          loader={() => fetchDepartmentHistory(action.department.id)}
          onClose={() => setAction(null)}
        />
      )}
    </div>
  )
}

function CreateDepartmentForm({ onCreated }: Readonly<{ onCreated: (d: Department) => void }>) {
  const messageOf = useApiErrorMessage()
  const lifetime = useLifetime()
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const submitting = useRef(false)
  const normalized = code.trim().toUpperCase()

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (submitting.current) return
    if (!CODE.test(normalized)) return setError('部門代碼需為 2～32 個字元，只能使用英文字母、數字、底線（_）與連字號（-）。')
    if (!name.trim()) return setError('請填寫部門名稱。')
    if (name.trim().length > 100) return setError('部門名稱最多 100 個字。')
    submitting.current = true
    setBusy(true)
    setError(null)
    const life = lifetime.current
    try {
      const created = await createDepartment({ code: normalized, name: name.trim() })
      if (!life.alive) return
      setCode('')
      setName('')
      onCreated(created)
    } catch (e) {
      if (!life.alive) return
      setError(messageOf(e))
    }
    submitting.current = false
    setBusy(false)
  }

  return (
    <form className="correction-form" onSubmit={submit} noValidate data-testid="create-department-form">
      <div className="form-grid">
        <label htmlFor="dept-code">部門代碼</label>
        <input id="dept-code" type="text" maxLength={64} value={code} onChange={(e) => setCode(e.target.value)} placeholder="例如 PROJECT" />
        <label htmlFor="dept-name">部門名稱</label>
        <input id="dept-name" type="text" maxLength={100} value={name} onChange={(e) => setName(e.target.value)} placeholder="例如 專案服務" />
      </div>
      <p className="muted small">{normalized ? `將建立為：${normalized}（建立後不可修改代碼）` : '代碼會自動去除前後空白並轉成大寫；建立後不可修改。'}</p>
      <p className="form-error" role="alert">
        {error}
      </p>
      <div className="actions">
        <button type="submit" className="btn btn--primary" disabled={busy}>
          {busy ? '建立中…' : '建立部門'}
        </button>
      </div>
    </form>
  )
}

/**
 * 修改名稱、停用或重新啟用（代碼不可修改）。啟用狀態有變更時原因必填。
 * 失敗或版本衝突時保留輸入；「載入最新資料」由使用者明確選擇，才會以最新內容取代。
 */
function EditDepartmentDialog({
  department,
  onClose,
  onDone,
}: Readonly<{ department: Department; onClose: () => void; onDone: (message: string) => void }>) {
  const messageOf = useApiErrorMessage()
  const lifetime = useLifetime()
  const [base, setBase] = useState(department)
  const [name, setName] = useState(department.name)
  const [active, setActive] = useState(department.active)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [conflict, setConflict] = useState(false)
  const [busy, setBusy] = useState(false)

  const nameChanged = name.trim() !== base.name
  const activeChanged = active !== base.active
  const deactivating = base.active && !active

  async function save() {
    if (busy) return
    if (!name.trim()) return setError('請填寫部門名稱。')
    if (!nameChanged && !activeChanged) return setError('內容與目前相同，沒有需要儲存的變更。')
    if (activeChanged && !reason.trim()) return setError('變更部門啟用狀態時必須填寫原因。')
    setBusy(true)
    setError(null)
    const life = lifetime.current
    try {
      const saved = await updateDepartment(base.id, { name: name.trim(), active, expectedVersion: base.version, reason: reason.trim() || null })
      if (!life.alive) return
      onDone(`已更新部門 ${saved.code}${activeChanged ? (saved.active ? '（已重新啟用）' : '（已停用）') : ''}。`)
    } catch (e) {
      if (!life.alive) return
      setConflict(e instanceof ApiError && e.code === 'DEPARTMENT_CHANGED')
      setError(messageOf(e))
      setBusy(false)
    }
  }

  async function loadLatest() {
    setBusy(true)
    const life = lifetime.current
    try {
      const latest = (await fetchDepartments()).find((d) => d.id === base.id)
      if (!life.alive) return
      if (latest) {
        setBase(latest)
        setName(latest.name)
        setActive(latest.active)
      }
      setConflict(false)
      setError(latest ? null : '這個部門已不存在。')
    } catch (e) {
      if (!life.alive) return
      setError(messageOf(e))
    }
    setBusy(false)
  }

  return (
    <div className="modal-backdrop">
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="edit-dept-title" data-testid="edit-department-dialog">
        <h2 id="edit-dept-title">編輯部門 {base.code}</h2>
        <div className="modal__body">
          <p className="small">代碼：{base.code}（不可修改）；版本 {base.version}</p>
          <label htmlFor="edit-dept-name">部門名稱</label>
          <input id="edit-dept-name" type="text" maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
          <label htmlFor="edit-dept-active">狀態</label>
          <select id="edit-dept-active" value={active ? 'true' : 'false'} onChange={(e) => setActive(e.target.value === 'true')}>
            <option value="true">啟用</option>
            <option value="false">停用</option>
          </select>
          {deactivating && (
            <p className="banner banner--warn small" data-testid="deactivate-warning">
              停用後不能再把員工指派到這個部門；已停用的員工可保留此部門。目前有 {base.activeEmployeeCount} 位啟用中的員工
              {base.activeEmployeeCount > 0 ? '，需先調整他們的部門或停用帳號，否則系統會拒絕。' : '。'}不會刪除任何資料。
            </p>
          )}
          <label htmlFor="edit-dept-reason">原因{activeChanged ? '（必填）' : '（選填）'}</label>
          <textarea id="edit-dept-reason" rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
        </div>
        {conflict && (
          <div className="banner banner--warn" data-testid="department-conflict">
            <p>這個部門已被其他管理員修改，你的變更沒有儲存（輸入仍保留）。可載入最新資料後再修改。</p>
            <button type="button" className="btn btn--small" onClick={() => void loadLatest()} disabled={busy}>
              載入最新資料（會取代目前輸入）
            </button>
          </div>
        )}
        <p className="form-error" role="alert">
          {error}
        </p>
        <div className="actions">
          <button type="button" className={deactivating ? 'btn btn--danger' : 'btn btn--primary'} onClick={() => void save()} disabled={busy}>
            {busy ? '處理中…' : '確認儲存'}
          </button>
          <button type="button" className="btn" onClick={onClose} disabled={busy}>
            取消
          </button>
        </div>
      </div>
    </div>
  )
}
