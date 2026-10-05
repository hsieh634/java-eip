import { useState, type FormEvent } from 'react'
import type { CurrentUser } from '../api/auth'
import {
  fetchDepartments,
  fetchEmployeeHistory,
  fetchEmployees,
  fetchManagerOptions,
  type AdminEmployee,
  type Department,
  type ManagerOption,
  type StatusFilter,
} from '../api/organization'
import { useLoad } from '../lib/useLoad'
import { CreateEmployeeDialog, EditEmployeeDialog } from './EmployeeDialogs'
import { OrgHistoryDialog } from './OrgHistoryDialog'
import { ROLE_SHORT } from './labels'

const PAGE_SIZE = 20

type Action = { kind: 'create' } | { kind: 'edit' | 'history'; employee: AdminEmployee }

export interface OrgOptions {
  departments: Department[]
  managers: ManagerOption[]
}

/** 員工管理：搜尋、篩選、分頁、建立、修改（含角色、啟用、部門、直屬主管）、歷程。 */
export function EmployeesPanel({ user }: Readonly<{ user: CurrentUser }>) {
  const [draftQuery, setDraftQuery] = useState('')
  const [query, setQuery] = useState({ q: '', status: 'ALL' as StatusFilter, page: 0 })
  const [seq, setSeq] = useState(0)
  const employees = useLoad(() => fetchEmployees({ ...query, size: PAGE_SIZE }), `org-employees:${JSON.stringify(query)}:${seq}`)
  const options = useLoad(
    () => Promise.all([fetchDepartments(), fetchManagerOptions()]).then(([departments, managers]) => ({ departments, managers })),
    `org-options:${seq}`,
  )
  const [action, setAction] = useState<Action | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const reload = () => setSeq((s) => s + 1)
  const done = (message: string) => {
    setAction(null)
    setNotice(message)
    reload()
  }

  function search(event: FormEvent) {
    event.preventDefault()
    setQuery((q) => ({ ...q, q: draftQuery, page: 0 }))
  }

  const ready = employees.state.kind === 'ready' ? employees.state.data : null
  const totalPages = ready ? Math.max(1, Math.ceil(ready.total / ready.size)) : 1
  const optionsReady = options.state.kind === 'ready' ? options.state.data : null

  return (
    <div className="stack">
      {notice && (
        <p className="notice notice--success" role="status" data-testid="employee-notice">
          {notice}
        </p>
      )}
      <section className="card card--records" aria-labelledby="employee-list-title">
        <div className="card__title-row">
          <h2 id="employee-list-title">員工清單</h2>
          <div className="actions">
            <button type="button" className="btn btn--small" onClick={reload}>
              重新整理
            </button>
            <button
              type="button"
              className="btn btn--small btn--primary"
              onClick={() => setAction({ kind: 'create' })}
              disabled={!optionsReady}
              data-testid="open-create-employee"
            >
              新增員工
            </button>
          </div>
        </div>
        <form className="range-form" onSubmit={search} role="search" data-testid="employee-search">
          <div className="range-field">
            <label htmlFor="emp-q">搜尋</label>
            <input id="emp-q" type="search" maxLength={100} value={draftQuery} onChange={(e) => setDraftQuery(e.target.value)} placeholder="姓名、帳號或員工編號" />
          </div>
          <div className="range-field">
            <label htmlFor="emp-status">狀態</label>
            <select
              id="emp-status"
              value={query.status}
              onChange={(e) => setQuery((q) => ({ ...q, status: e.target.value as StatusFilter, page: 0 }))}
            >
              <option value="ALL">全部</option>
              <option value="ACTIVE">啟用中</option>
              <option value="INACTIVE">已停用</option>
            </select>
          </div>
          <button type="submit" className="btn">
            搜尋
          </button>
        </form>
        {options.state.kind === 'error' && (
          <p className="form-error" role="alert">
            無法載入部門與主管選項：{options.state.message}
            <button type="button" className="btn btn--small" onClick={options.reload}>
              重試
            </button>
          </p>
        )}
        {employees.state.kind === 'loading' && <p className="muted">載入中…</p>}
        {employees.state.kind === 'error' && (
          <div>
            <p className="form-error" role="alert">
              無法載入員工：{employees.state.message}
            </p>
            <button type="button" className="btn" onClick={employees.reload}>
              重試
            </button>
          </div>
        )}
        {ready && ready.items.length === 0 && (
          <p className="muted" data-testid="employee-empty">
            {query.q || query.status !== 'ALL' ? '沒有符合條件的員工。' : '尚未建立任何員工。'}
          </p>
        )}
        {ready && ready.items.length > 0 && (
          <>
            <table className="records-table" data-testid="employee-table">
              <thead>
                <tr>
                  <th scope="col">員工編號</th>
                  <th scope="col">姓名</th>
                  <th scope="col">帳號</th>
                  <th scope="col">角色</th>
                  <th scope="col">部門</th>
                  <th scope="col">直屬主管</th>
                  <th scope="col">狀態</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                {ready.items.map((e) => (
                  <tr key={e.id} data-employee-no={e.employeeNo} data-username={e.username} data-enabled={e.enabled} className={e.enabled ? undefined : 'row--inactive'}>
                    <td data-label="員工編號">{e.employeeNo}</td>
                    <td data-label="姓名">{e.displayName}</td>
                    <td data-label="帳號">{e.username}</td>
                    <td data-label="角色">{ROLE_SHORT[e.role]}</td>
                    <td data-label="部門" data-testid="cell-department">
                      {e.department ? `${e.department.code} ${e.department.name}${e.department.active ? '' : '（已停用）'}` : '未指派'}
                    </td>
                    <td data-label="直屬主管" data-testid="cell-manager">
                      {e.manager ? e.manager.displayName : '未指派'}
                    </td>
                    <td data-label="狀態">
                      <span className={e.enabled ? 'status-chip status-chip--working' : 'status-chip status-chip--voided'}>{e.enabled ? '啟用' : '已停用'}</span>
                    </td>
                    <td data-label="操作">
                      <div className="actions">
                        <button type="button" className="btn btn--small" onClick={() => setAction({ kind: 'edit', employee: e })} disabled={!optionsReady}>
                          編輯
                        </button>
                        <button type="button" className="btn btn--small" onClick={() => setAction({ kind: 'history', employee: e })}>
                          歷程
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="pager" data-testid="employee-pager">
              <button
                type="button"
                className="btn btn--small"
                disabled={query.page === 0}
                onClick={() => setQuery((q) => ({ ...q, page: q.page - 1 }))}
              >
                上一頁
              </button>
              <span className="small">
                第 {ready.page + 1} / {totalPages} 頁，共 {ready.total} 人
              </span>
              <button
                type="button"
                className="btn btn--small"
                disabled={ready.page + 1 >= totalPages}
                onClick={() => setQuery((q) => ({ ...q, page: q.page + 1 }))}
              >
                下一頁
              </button>
            </div>
          </>
        )}
      </section>
      {action?.kind === 'create' && optionsReady && (
        <CreateEmployeeDialog options={optionsReady} onClose={() => setAction(null)} onDone={done} />
      )}
      {action?.kind === 'edit' && optionsReady && (
        <EditEmployeeDialog employee={action.employee} user={user} options={optionsReady} onClose={() => setAction(null)} onDone={done} />
      )}
      {action?.kind === 'history' && (
        <OrgHistoryDialog
          title={`員工歷程：${action.employee.employeeNo} ${action.employee.displayName}`}
          loadKey={`employee-history:${action.employee.id}`}
          loader={() => fetchEmployeeHistory(action.employee.id)}
          onClose={() => setAction(null)}
        />
      )}
    </div>
  )
}
