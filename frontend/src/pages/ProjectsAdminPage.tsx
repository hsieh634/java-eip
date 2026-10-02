import { useRef, useState, type FormEvent } from 'react'
import type { CurrentUser } from '../api/auth'
import { ApiError } from '../api/http'
import { createProject, fetchAllProjects, updateProject, type Project } from '../api/projects'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { FormError } from '../lib/formError'
import { formatDateTime } from '../lib/time'
import { useLifetime } from '../lib/useLifetime'
import { useLoad } from '../lib/useLoad'

const CODE = /^[A-Z0-9_-]{2,32}$/

/** 專案管理（僅 ADMIN）。入口只對 ADMIN 顯示，但真正的權限由後端驗證。 */
export function ProjectsAdminPage({ user }: Readonly<{ user: CurrentUser }>) {
  if (user.role !== 'ADMIN') {
    return (
      <>
        <h1>專案管理</h1>
        <p className="form-error" role="alert">
          只有管理員可以使用這個功能。
        </p>
      </>
    )
  }
  return <ProjectsContent />
}

type Action = { kind: 'edit' | 'toggle'; project: Project }

function ProjectsContent() {
  const [seq, setSeq] = useState(0)
  const projects = useLoad(() => fetchAllProjects(), `admin-projects:${seq}`)
  const [action, setAction] = useState<Action | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [conflict, setConflict] = useState(false)
  const reload = () => {
    setConflict(false)
    setSeq((s) => s + 1)
  }
  const done = (message: string) => {
    setAction(null)
    setNotice(message)
    reload()
  }
  const onConflict = () => {
    setAction(null)
    setNotice(null)
    setConflict(true)
  }

  return (
    <>
      <h1>專案管理</h1>
      <p className="muted">建立可填報工時的專案。停用不會刪除專案或工時：停用後不能新增工時，既有紀錄仍可查看，並可依規則修改。</p>
      {notice && (
        <p className="notice notice--success" role="status" data-testid="project-notice">
          {notice}
        </p>
      )}
      {conflict && (
        <div className="banner banner--warn" role="alert" data-testid="project-conflict">
          <p>這個專案已被其他管理員修改，你的變更沒有儲存（不會覆蓋較新的資料）。請重新載入後再操作。</p>
          <button type="button" className="btn btn--small" onClick={reload}>
            重新載入
          </button>
        </div>
      )}
      <div className="stack">
        <section className="card" aria-labelledby="create-project-title">
          <h2 id="create-project-title">建立專案</h2>
          <CreateProjectForm onCreated={(p) => done(`已建立專案 ${p.code}。`)} />
        </section>

        <section className="card card--records" aria-labelledby="project-list-title">
          <div className="card__title-row">
            <h2 id="project-list-title">專案清單</h2>
            <button type="button" className="btn btn--small" onClick={reload}>
              重新整理
            </button>
          </div>
          {projects.state.kind === 'loading' && <p className="muted">載入中…</p>}
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
          {projects.state.kind === 'ready' && projects.state.data.length === 0 && <p className="muted">尚未建立任何專案。</p>}
          {projects.state.kind === 'ready' && projects.state.data.length > 0 && (
            <table className="records-table" data-testid="project-table">
              <thead>
                <tr>
                  <th scope="col">代碼</th>
                  <th scope="col">名稱</th>
                  <th scope="col">說明</th>
                  <th scope="col">狀態</th>
                  <th scope="col">最後修改</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                {projects.state.data.map((p) => (
                  <tr key={p.id} data-project-code={p.code} data-active={p.active} className={p.active ? undefined : 'row--inactive'}>
                    <td data-label="代碼">{p.code}</td>
                    <td data-label="名稱">{p.name}</td>
                    <td data-label="說明" className="cell--wrap">
                      {p.description ?? '—'}
                    </td>
                    <td data-label="狀態">
                      <span className={p.active ? 'status-chip status-chip--working' : 'status-chip status-chip--voided'}>{p.active ? '啟用' : '已停用'}</span>
                    </td>
                    <td data-label="最後修改">{formatDateTime(p.updatedAt)}</td>
                    <td data-label="操作">
                      <div className="actions">
                        <button type="button" className="btn btn--small" onClick={() => setAction({ kind: 'edit', project: p })}>
                          編輯
                        </button>
                        <button type="button" className={p.active ? 'btn btn--small btn--danger' : 'btn btn--small'} onClick={() => setAction({ kind: 'toggle', project: p })}>
                          {p.active ? '停用' : '重新啟用'}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>
      {action?.kind === 'edit' && <EditProjectDialog project={action.project} onClose={() => setAction(null)} onDone={done} onConflict={onConflict} />}
      {action?.kind === 'toggle' && <ToggleProjectDialog project={action.project} onClose={() => setAction(null)} onDone={done} onConflict={onConflict} />}
    </>
  )
}

function CreateProjectForm({ onCreated }: Readonly<{ onCreated: (p: Project) => void }>) {
  const messageOf = useApiErrorMessage()
  const lifetime = useLifetime()
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const submitting = useRef(false)
  const normalized = code.trim().toUpperCase()

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (submitting.current) return
    if (!CODE.test(normalized)) return setError('專案代碼需為 2～32 個字元，只能使用英文字母、數字、底線（_）與連字號（-）。')
    if (!name.trim()) return setError('請填寫專案名稱。')
    if (name.trim().length > 100) return setError('專案名稱最多 100 個字。')
    submitting.current = true
    setBusy(true)
    setError(null)
    const life = lifetime.current
    try {
      const created = await createProject({ code: normalized, name: name.trim(), description: description.trim() || null })
      if (!life.alive) return
      setCode('')
      setName('')
      setDescription('')
      onCreated(created)
    } catch (e) {
      if (!life.alive) return
      setError(messageOf(e))
    }
    submitting.current = false
    setBusy(false)
  }

  return (
    <form className="correction-form" onSubmit={submit} noValidate data-testid="create-project-form">
      <div className="form-grid">
        <label htmlFor="pj-code">專案代碼</label>
        <input id="pj-code" type="text" maxLength={64} value={code} onChange={(e) => setCode(e.target.value)} placeholder="例如 TP-WEB" />
        <label htmlFor="pj-name">專案名稱</label>
        <input id="pj-name" type="text" maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <p className="muted small">{normalized ? `將建立為：${normalized}（建立後不可修改代碼）` : '代碼會自動去除前後空白並轉成大寫；建立後不可修改。'}</p>
      <label htmlFor="pj-description">說明（選填）</label>
      <textarea id="pj-description" rows={2} maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} />
      <p className="form-error" role="alert">
        {error}
      </p>
      <div className="actions">
        <button type="submit" className="btn btn--primary" disabled={busy}>
          {busy ? '建立中…' : '建立專案'}
        </button>
      </div>
    </form>
  )
}

interface DialogProps {
  project: Project
  onClose: () => void
  onDone: (message: string) => void
  onConflict: () => void
}

/** 送出修改；版本不符（409 PROJECT_CHANGED）時關閉對話框並提示重新載入，不自動以新版本重送。 */
async function submitUpdate(p: Project, body: { name: string; description: string | null; active: boolean }, onConflict: () => void) {
  try {
    await updateProject(p.id, { ...body, expectedVersion: p.version })
    return true
  } catch (e) {
    if (e instanceof ApiError && e.code === 'PROJECT_CHANGED') {
      onConflict()
      return false
    }
    throw e
  }
}

function EditProjectDialog({ project, onClose, onDone, onConflict }: Readonly<DialogProps>) {
  const [name, setName] = useState(project.name)
  const [description, setDescription] = useState(project.description ?? '')
  return (
    <ConfirmDialog
      title={`編輯專案 ${project.code}`}
      confirmLabel="儲存"
      onCancel={onClose}
      onConfirm={async () => {
        if (!name.trim()) throw new FormError('請填寫專案名稱。')
        if (await submitUpdate(project, { name: name.trim(), description: description.trim() || null, active: project.active }, onConflict)) {
          onDone(`已更新專案 ${project.code}。`)
        }
      }}
    >
      <p className="small">代碼：{project.code}（不可修改）；版本 {project.version}</p>
      <label htmlFor="edit-pj-name">專案名稱</label>
      <input id="edit-pj-name" type="text" maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
      <label htmlFor="edit-pj-description">說明（選填）</label>
      <textarea id="edit-pj-description" rows={3} maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} />
    </ConfirmDialog>
  )
}

function ToggleProjectDialog({ project, onClose, onDone, onConflict }: Readonly<DialogProps>) {
  const deactivate = project.active
  return (
    <ConfirmDialog
      title={deactivate ? `確認停用專案 ${project.code}` : `確認重新啟用專案 ${project.code}`}
      confirmLabel={deactivate ? '確認停用' : '確認啟用'}
      danger={deactivate}
      onCancel={onClose}
      onConfirm={async () => {
        if (await submitUpdate(project, { name: project.name, description: project.description, active: !deactivate }, onConflict)) {
          onDone(deactivate ? `已停用專案 ${project.code}。` : `已重新啟用專案 ${project.code}。`)
        }
      }}
    >
      <p data-testid="toggle-outcome">
        {deactivate
          ? '停用後：員工不能再新增這個專案的工時，也不能把其他紀錄改到這個專案；既有工時保留、仍可查看，維持原專案時仍可修改日期、時數與內容。不會刪除任何資料。'
          : '重新啟用後：員工可以再新增這個專案的工時。'}
      </p>
      <p className="small">
        {project.name}（最後修改 {formatDateTime(project.updatedAt)}）
      </p>
    </ConfirmDialog>
  )
}
