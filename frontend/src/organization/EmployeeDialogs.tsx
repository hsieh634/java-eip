import { useState } from 'react'
import type { CurrentUser, Role } from '../api/auth'
import { ApiError } from '../api/http'
import { createEmployee, fetchEmployee, updateEmployee, type AdminEmployee } from '../api/organization'
import { PASSWORD_MAX_BYTES, utf8ByteLength } from '../auth/passwordPolicy'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'
import { useLifetime } from '../lib/useLifetime'
import type { OrgOptions } from './EmployeesPanel'
import { ROLE_LABEL, ROLE_SHORT } from './labels'

const EMPLOYEE_NO = /^[A-Z0-9_-]{2,20}$/
const USERNAME = /^[a-z0-9._-]{2,50}$/
const ROLES: Role[] = ['EMPLOYEE', 'MANAGER', 'ADMIN']

const toId = (value: string) => (value === '' ? null : Number(value))
const fromId = (value: number | null) => (value === null ? '' : String(value))

// ---------------------------------------------------------------- 建立

/**
 * 建立員工。初始密碼由管理員輸入並自行交付給本人：系統不寄送邀請，也不會強制首次登入修改密碼。
 * 密碼原樣送出（不 trim、不截斷），只在後端雜湊保存；建立後畫面不再顯示，也不會出現在歷程。
 */
export function CreateEmployeeDialog({
  options,
  onClose,
  onDone,
}: Readonly<{ options: OrgOptions; onClose: () => void; onDone: (message: string) => void }>) {
  const messageOf = useApiErrorMessage()
  const lifetime = useLifetime()
  const [employeeNo, setEmployeeNo] = useState('')
  const [username, setUsername] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [role, setRole] = useState<Role>('EMPLOYEE')
  const [departmentId, setDepartmentId] = useState('')
  const [managerId, setManagerId] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const normalizedNo = employeeNo.trim().toUpperCase()
  const normalizedUsername = username.trim().toLowerCase()
  const passwordBytes = utf8ByteLength(password)
  const activeDepartments = options.departments.filter((d) => d.active)

  async function save() {
    if (busy) return
    if (!EMPLOYEE_NO.test(normalizedNo)) return setError('員工編號需為 2～20 個字元，只能使用英文字母、數字、底線（_）與連字號（-）。')
    if (!USERNAME.test(normalizedUsername)) return setError('帳號需為 2～50 個字元，只能使用英文字母、數字、點（.）、底線（_）與連字號（-）。')
    if (!displayName.trim() || displayName.trim().length > 100) return setError('請填寫顯示名稱（最多 100 個字）。')
    if (!password.trim()) return setError('請輸入初始密碼。')
    if (passwordBytes > PASSWORD_MAX_BYTES) return setError(`初始密碼最多 ${PASSWORD_MAX_BYTES} 個位元組（中文字每字 3 位元組）。`)
    setBusy(true)
    setError(null)
    const life = lifetime.current
    try {
      const created = await createEmployee({
        employeeNo: normalizedNo,
        username: normalizedUsername,
        displayName: displayName.trim(),
        role,
        departmentId: toId(departmentId),
        managerId: toId(managerId),
        initialPassword: password,
        reason: reason.trim() || null,
      })
      if (!life.alive) return
      setPassword('')
      onDone(`已建立員工 ${created.employeeNo} ${created.displayName}（帳號 ${created.username}）。請以安全的方式把初始密碼交給本人；系統不會再顯示這個密碼。`)
    } catch (e) {
      if (!life.alive) return
      setError(messageOf(e))
      setBusy(false)
    }
  }

  return (
    <div className="modal-backdrop">
      <div className="modal modal--wide" role="dialog" aria-modal="true" aria-labelledby="create-emp-title" data-testid="create-employee-dialog">
        <h2 id="create-emp-title">新增員工</h2>
        <div className="modal__body">
          <div className="form-grid">
            <label htmlFor="new-emp-no">員工編號</label>
            <input id="new-emp-no" type="text" maxLength={40} value={employeeNo} onChange={(e) => setEmployeeNo(e.target.value)} placeholder="例如 STX-007" />
            <label htmlFor="new-emp-username">帳號</label>
            <input
              id="new-emp-username"
              type="text"
              maxLength={100}
              autoComplete="off"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="例如 stmax_amy"
            />
            <label htmlFor="new-emp-name">顯示名稱</label>
            <input id="new-emp-name" type="text" maxLength={100} value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
            <label htmlFor="new-emp-role">角色</label>
            <select id="new-emp-role" value={role} onChange={(e) => setRole(e.target.value as Role)}>
              {ROLES.map((r) => (
                <option key={r} value={r}>
                  {ROLE_LABEL[r]}
                </option>
              ))}
            </select>
            <label htmlFor="new-emp-dept">部門</label>
            <select id="new-emp-dept" value={departmentId} onChange={(e) => setDepartmentId(e.target.value)}>
              <option value="">未指派</option>
              {activeDepartments.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.code} {d.name}
                </option>
              ))}
            </select>
            <label htmlFor="new-emp-manager">直屬主管</label>
            <select id="new-emp-manager" value={managerId} onChange={(e) => setManagerId(e.target.value)}>
              <option value="">未指派</option>
              {options.managers.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.displayName}（{m.employeeNo}，{ROLE_SHORT[m.role]}）
                </option>
              ))}
            </select>
          </div>
          <p className="muted small">
            {normalizedNo || normalizedUsername
              ? `將建立為：員工編號 ${normalizedNo || '—'}、帳號 ${normalizedUsername || '—'}（建立後不可修改）`
              : '員工編號會轉成大寫、帳號會轉成小寫（與登入相同）；建立後都不可修改。'}
          </p>
          <label htmlFor="new-emp-password">初始密碼</label>
          <input
            id="new-emp-password"
            type={showPassword ? 'text' : 'password'}
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <label className="checkbox small">
            <input type="checkbox" checked={showPassword} onChange={(e) => setShowPassword(e.target.checked)} /> 顯示密碼
          </label>
          <p className="banner banner--info small" data-testid="password-delivery-notice">
            初始密碼由你（管理員）自行交付給員工：系統不會寄送邀請信，也不會要求首次登入修改密碼。密碼會原樣保存（前後空白也算），最多 {PASSWORD_MAX_BYTES}{' '}
            個位元組（目前 {passwordBytes}）。建立後系統不會再顯示這個密碼。
          </p>
          <label htmlFor="new-emp-reason">備註（選填）</label>
          <textarea id="new-emp-reason" rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="例如 新進人員" />
        </div>
        <p className="form-error" role="alert">
          {error}
        </p>
        <div className="actions">
          <button type="button" className="btn btn--primary" onClick={() => void save()} disabled={busy}>
            {busy ? '建立中…' : '確認建立'}
          </button>
          <button type="button" className="btn" onClick={onClose} disabled={busy}>
            取消
          </button>
        </div>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------- 修改

interface Draft {
  displayName: string
  role: Role
  enabled: boolean
  departmentId: string
  managerId: string
}

const draftOf = (e: AdminEmployee): Draft => ({
  displayName: e.displayName,
  role: e.role,
  enabled: e.enabled,
  departmentId: fromId(e.department?.id ?? null),
  managerId: fromId(e.manager?.id ?? null),
})

/**
 * 修改顯示名稱、角色、啟用狀態、部門、直屬主管（員工編號、帳號、密碼不能在這裡改）。
 * 送出前列出「變更摘要」；角色、啟用、部門或主管有變更時原因必填。
 * 失敗或版本衝突時保留輸入；「載入最新資料」由使用者明確選擇才會取代輸入。
 */
export function EditEmployeeDialog({
  employee,
  user,
  options,
  onClose,
  onDone,
}: Readonly<{ employee: AdminEmployee; user: CurrentUser; options: OrgOptions; onClose: () => void; onDone: (message: string) => void }>) {
  const messageOf = useApiErrorMessage()
  const lifetime = useLifetime()
  const [base, setBase] = useState(employee)
  const [draft, setDraft] = useState<Draft>(() => draftOf(employee))
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [conflict, setConflict] = useState(false)
  const [busy, setBusy] = useState(false)
  const self = base.id === user.id
  const original = draftOf(base)
  const set = (patch: Partial<Draft>) => setDraft((d) => ({ ...d, ...patch }))

  // 可選的部門：啟用中的部門；目前所屬的部門已停用時也列出（保留不動是允許的）
  const departmentChoices = options.departments.filter((d) => d.active || String(d.id) === original.departmentId)
  // 可選的主管：啟用中的 MANAGER／ADMIN，排除本人
  const managerChoices = options.managers.filter((m) => m.id !== base.id)
  const currentManagerMissing = base.manager !== null && !managerChoices.some((m) => m.id === base.manager?.id)

  const departmentLabel = (id: string) => {
    if (id === '') return '未指派'
    const d = options.departments.find((x) => String(x.id) === id)
    return d ? `${d.code} ${d.name}${d.active ? '' : '（已停用）'}` : `#${id}`
  }
  const managerLabel = (id: string) => {
    if (id === '') return '未指派'
    if (base.manager && String(base.manager.id) === id) return base.manager.displayName
    return options.managers.find((m) => String(m.id) === id)?.displayName ?? `#${id}`
  }

  const changes: { label: string; before: string; after: string }[] = []
  if (draft.displayName.trim() !== original.displayName) changes.push({ label: '顯示名稱', before: original.displayName, after: draft.displayName.trim() })
  if (draft.role !== original.role) changes.push({ label: '角色', before: ROLE_SHORT[original.role], after: ROLE_SHORT[draft.role] })
  if (draft.enabled !== original.enabled) changes.push({ label: '帳號狀態', before: original.enabled ? '啟用' : '停用', after: draft.enabled ? '啟用' : '停用' })
  if (draft.departmentId !== original.departmentId)
    changes.push({ label: '部門', before: departmentLabel(original.departmentId), after: departmentLabel(draft.departmentId) })
  if (draft.managerId !== original.managerId) changes.push({ label: '直屬主管', before: managerLabel(original.managerId), after: managerLabel(draft.managerId) })
  const needsReason = changes.some((c) => c.label !== '顯示名稱')
  const sessionEnds = draft.role !== original.role || (original.enabled && !draft.enabled)

  async function save() {
    if (busy) return
    if (!draft.displayName.trim() || draft.displayName.trim().length > 100) return setError('請填寫顯示名稱（最多 100 個字）。')
    if (changes.length === 0) return setError('內容與目前相同，沒有需要儲存的變更。')
    if (needsReason && !reason.trim()) return setError('變更角色、啟用狀態、部門或主管時必須填寫原因。')
    setBusy(true)
    setError(null)
    const life = lifetime.current
    try {
      const saved = await updateEmployee(base.id, {
        displayName: draft.displayName.trim(),
        role: draft.role,
        enabled: draft.enabled,
        departmentId: toId(draft.departmentId),
        managerId: toId(draft.managerId),
        expectedVersion: base.version,
        reason: reason.trim() || null,
      })
      if (!life.alive) return
      onDone(`已更新員工 ${saved.employeeNo} ${saved.displayName}：${changes.map((c) => c.label).join('、')}。`)
    } catch (e) {
      if (!life.alive) return
      setConflict(e instanceof ApiError && e.code === 'EMPLOYEE_CHANGED')
      setError(messageOf(e))
      setBusy(false)
    }
  }

  async function loadLatest() {
    setBusy(true)
    const life = lifetime.current
    try {
      const latest = await fetchEmployee(base.id)
      if (!life.alive) return
      setBase(latest)
      setDraft(draftOf(latest))
      setConflict(false)
      setError(null)
    } catch (e) {
      if (!life.alive) return
      setError(messageOf(e))
    }
    setBusy(false)
  }

  return (
    <div className="modal-backdrop">
      <div className="modal modal--wide" role="dialog" aria-modal="true" aria-labelledby="edit-emp-title" data-testid="edit-employee-dialog">
        <h2 id="edit-emp-title">
          編輯員工 {base.employeeNo} {base.displayName}
        </h2>
        <div className="modal__body">
          <p className="small">
            帳號：{base.username}（員工編號與帳號不可修改；本頁不提供密碼修改）；版本 {base.version}
            {base.subordinateCount > 0 && `；目前是 ${base.subordinateCount} 位員工的直屬主管`}
          </p>
          <div className="form-grid">
            <label htmlFor="edit-emp-name">顯示名稱</label>
            <input id="edit-emp-name" type="text" maxLength={100} value={draft.displayName} onChange={(e) => set({ displayName: e.target.value })} />
            <label htmlFor="edit-emp-role">角色</label>
            <select id="edit-emp-role" value={draft.role} onChange={(e) => set({ role: e.target.value as Role })} disabled={self}>
              {ROLES.map((r) => (
                <option key={r} value={r}>
                  {ROLE_LABEL[r]}
                </option>
              ))}
            </select>
            <label htmlFor="edit-emp-enabled">帳號狀態</label>
            <select
              id="edit-emp-enabled"
              value={draft.enabled ? 'true' : 'false'}
              onChange={(e) => set({ enabled: e.target.value === 'true' })}
              disabled={self}
            >
              <option value="true">啟用</option>
              <option value="false">停用</option>
            </select>
            <label htmlFor="edit-emp-dept">部門</label>
            <select id="edit-emp-dept" value={draft.departmentId} onChange={(e) => set({ departmentId: e.target.value })}>
              <option value="">未指派</option>
              {departmentChoices.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.code} {d.name}
                  {d.active ? '' : '（已停用）'}
                </option>
              ))}
            </select>
            <label htmlFor="edit-emp-manager">直屬主管</label>
            <select id="edit-emp-manager" value={draft.managerId} onChange={(e) => set({ managerId: e.target.value })}>
              <option value="">未指派</option>
              {currentManagerMissing && base.manager && <option value={base.manager.id}>{base.manager.displayName}（目前）</option>}
              {managerChoices.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.displayName}（{m.employeeNo}，{ROLE_SHORT[m.role]}）
                </option>
              ))}
            </select>
          </div>
          {self && <p className="muted small">這是你自己的帳號：可以修改名稱、部門與主管，但不能停用自己或移除自己的管理員角色。</p>}
          <p className="muted small">主管只是組織關係：被設為主管不會因此取得查看部屬出勤或工時的權限。</p>
          <div className="change-summary" data-testid="change-summary">
            <strong className="small">變更摘要</strong>
            {changes.length === 0 ? (
              <p className="muted small">尚未變更任何欄位。</p>
            ) : (
              <ul className="change-list">
                {changes.map((c) => (
                  <li key={c.label} className="small">
                    {c.label}：{c.before} → <strong>{c.after}</strong>
                  </li>
                ))}
              </ul>
            )}
            {sessionEnds && <p className="banner banner--warn small">儲存後，這位員工目前的登入會失效，下一個操作需要重新登入。</p>}
          </div>
          <label htmlFor="edit-emp-reason">原因{needsReason ? '（必填）' : '（選填）'}</label>
          <textarea id="edit-emp-reason" rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
        </div>
        {conflict && (
          <div className="banner banner--warn" data-testid="employee-conflict">
            <p>這位員工的資料已被其他管理員修改，你的變更沒有儲存（輸入仍保留）。可載入最新資料後再修改。</p>
            <button type="button" className="btn btn--small" onClick={() => void loadLatest()} disabled={busy}>
              載入最新資料（會取代目前輸入）
            </button>
          </div>
        )}
        <p className="form-error" role="alert">
          {error}
        </p>
        <div className="actions">
          <button type="button" className={sessionEnds ? 'btn btn--danger' : 'btn btn--primary'} onClick={() => void save()} disabled={busy}>
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
