import { useState, type ReactNode } from 'react'
import type { CurrentUser, Role } from '../api/auth'
import { describeLogoutFailure } from '../auth/logoutFailureMessage'
import { useAuth } from '../auth/useAuth'
import type { View } from '../navigation/useHashView'

const ROLE_LABEL: Record<Role, string> = {
  EMPLOYEE: '員工',
  MANAGER: '主管',
  ADMIN: '管理員',
}

/** 尚未實作的功能。只列出名稱與預計階段，不顯示任何假資料。 */
const PENDING_FEATURES = [
  { key: 'settings', label: '必要設定' },
] as const

const ALL_ROLES: readonly Role[] = ['EMPLOYEE', 'MANAGER', 'ADMIN']

/** 一般入口：請假審核只對 MANAGER、ADMIN 顯示（隱藏入口不是安全機制；資料範圍由後端驗證）。 */
const NAV_ITEMS: readonly { view: View; href: string; label: string; roles: readonly Role[] }[] = [
  { view: 'attendance', href: '#/attendance', label: '出勤打卡', roles: ALL_ROLES },
  { view: 'timesheets', href: '#/timesheets', label: '工時回報', roles: ALL_ROLES },
  { view: 'leave', href: '#/leave', label: '請假', roles: ALL_ROLES },
  { view: 'leave-approvals', href: '#/leave/approvals', label: '請假審核', roles: ['MANAGER', 'ADMIN'] },
]

/** 只對 ADMIN 顯示的入口（隱藏入口不是安全機制；權限由後端 /api/admin/** 驗證）。 */
const ADMIN_ITEMS: readonly { view: View; href: string; label: string }[] = [
  { view: 'admin-attendance', href: '#/admin/attendance', label: '出勤管理' },
  { view: 'admin-geofence', href: '#/admin/geofence', label: '定位打卡設定' },
  { view: 'admin-projects', href: '#/admin/projects', label: '專案管理' },
  { view: 'admin-organization', href: '#/admin/organization', label: '組織與員工' },
  { view: 'admin-leave', href: '#/admin/leave', label: '請假管理' },
  { view: 'admin-leave-workflow', href: '#/admin/leave/workflow', label: '簽核流程設定' },
]

/** 登入後的共用框架：頂欄（品牌、使用者、登出）、側邊選單、登出失敗提示。 */
export function AppShell({ user, view, children }: Readonly<{ user: CurrentUser; view: View; children: ReactNode }>) {
  const { logout } = useAuth()
  const [loggingOut, setLoggingOut] = useState(false)
  const [logoutError, setLogoutError] = useState<string | null>(null)

  /**
   * UI 邊界：在這裡把失敗轉成畫面上的提示。
   * 成功時 AuthProvider 會把狀態改成 anonymous，App 換成登入頁，此元件隨即卸載，所以只在失敗時更新自己的狀態。
   */
  async function handleLogout() {
    setLoggingOut(true)
    setLogoutError(null)
    try {
      const result = await logout()
      if (!result.ok) {
        setLogoutError(describeLogoutFailure(result.error))
        setLoggingOut(false)
      }
    } catch (error) {
      // 防禦：logout() 約定不 reject；萬一有未預期例外，也不讓它變成未處理的 Promise 拒絕
      setLogoutError(describeLogoutFailure(error))
      setLoggingOut(false)
    }
  }

  let logoutLabel = '登出'
  if (loggingOut) {
    logoutLabel = '登出中…'
  } else if (logoutError) {
    logoutLabel = '重試登出'
  }

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand__mark" aria-hidden="true">T</span>
          <span className="brand__text">
            <span className="brand__name">TeamPortal</span>
            <span className="brand__sub">企業員工入口平台</span>
          </span>
        </div>
        <div className="topbar__user">
          <span className="topbar__name">{user.displayName}</span>
          <span className="badge">{ROLE_LABEL[user.role]}</span>
          <button type="button" className="btn" onClick={() => void handleLogout()} disabled={loggingOut}>
            {logoutLabel}
          </button>
        </div>
      </header>

      {logoutError && (
        <div className="banner banner--error" role="alert">
          {logoutError}
        </div>
      )}

      <div className="shell__body">
        <nav className="sidebar" aria-label="主選單">
          <ul>
            <li>
              <a href="#/" className={view === 'home' ? 'nav-item nav-item--active' : 'nav-item'} aria-current={view === 'home' ? 'page' : undefined}>
                首頁
              </a>
            </li>
            {NAV_ITEMS.filter((item) => item.roles.includes(user.role)).map((item) => (
              <li key={item.view}>
                <a href={item.href} className={view === item.view ? 'nav-item nav-item--active' : 'nav-item'} aria-current={view === item.view ? 'page' : undefined}>
                  {item.label}
                </a>
              </li>
            ))}
            {user.role === 'ADMIN' &&
              ADMIN_ITEMS.map((item) => (
                <li key={item.view}>
                  <a
                    href={item.href}
                    className={view === item.view ? 'nav-item nav-item--active' : 'nav-item'}
                    aria-current={view === item.view ? 'page' : undefined}
                  >
                    {item.label}
                  </a>
                </li>
              ))}
            {PENDING_FEATURES.map((f) => (
              <li key={f.key}>
                <button type="button" className="nav-item" disabled>
                  <span>{f.label}</span>
                  <span className="tag">尚未啟用</span>
                </button>
              </li>
            ))}
          </ul>
        </nav>

        <main className="content">{children}</main>
      </div>
    </div>
  )
}
