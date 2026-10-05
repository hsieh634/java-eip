import { useState } from 'react'
import type { CurrentUser } from '../api/auth'
import { DepartmentsPanel } from '../organization/DepartmentsPanel'
import { EmployeesPanel } from '../organization/EmployeesPanel'

type Tab = 'employees' | 'departments'

/** 組織與員工（僅 ADMIN）。入口只對 ADMIN 顯示，但真正的權限由後端驗證（/api/admin/**）。 */
export function OrganizationAdminPage({ user }: Readonly<{ user: CurrentUser }>) {
  const [tab, setTab] = useState<Tab>('employees')
  if (user.role !== 'ADMIN') {
    return (
      <>
        <h1>組織與員工</h1>
        <p className="form-error" role="alert">
          只有管理員可以使用這個功能。
        </p>
      </>
    )
  }
  return (
    <>
      <h1>組織與員工</h1>
      <p className="muted">
        管理部門與員工帳號，並設定每位員工的部門與直屬主管。部門是平面清單（沒有上下層）；部門與員工都不刪除，只停用。
        沒有部門或主管的員工照常可以登入、打卡與填工時。
      </p>
      <div className="tabs" role="tablist" aria-label="組織與員工">
        <button
          type="button"
          role="tab"
          id="tab-employees"
          aria-selected={tab === 'employees'}
          aria-controls="panel-employees"
          className={tab === 'employees' ? 'tab tab--active' : 'tab'}
          onClick={() => setTab('employees')}
        >
          員工
        </button>
        <button
          type="button"
          role="tab"
          id="tab-departments"
          aria-selected={tab === 'departments'}
          aria-controls="panel-departments"
          className={tab === 'departments' ? 'tab tab--active' : 'tab'}
          onClick={() => setTab('departments')}
        >
          部門
        </button>
      </div>
      <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
        {tab === 'employees' ? <EmployeesPanel user={user} /> : <DepartmentsPanel />}
      </div>
    </>
  )
}
