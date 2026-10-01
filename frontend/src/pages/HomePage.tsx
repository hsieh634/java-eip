import { useState } from 'react'
import type { CurrentUser, Role } from '../api/auth'
import { useAuth } from '../auth/useAuth'

const ROLE_LABEL: Record<Role, string> = {
  EMPLOYEE: '員工',
  MANAGER: '主管',
  ADMIN: '管理員',
}

/** 尚未實作的功能。只列出名稱與預計階段，不顯示任何假資料。 */
const PENDING_FEATURES = [
  { key: 'attendance', label: '出勤打卡', stage: 'T03' },
  { key: 'timesheet', label: '工時回報', stage: 'T04' },
  { key: 'leave', label: '請假申請', stage: 'T05' },
  { key: 'settings', label: '必要設定', stage: 'T06' },
] as const

const PENDING_CARDS = [
  { title: '今日打卡', stage: 'T03' },
  { title: '本週工時', stage: 'T04' },
  { title: '待審核', stage: 'T05' },
] as const

export function HomePage({ user }: Readonly<{ user: CurrentUser }>) {
  const { logout } = useAuth()
  const [loggingOut, setLoggingOut] = useState(false)

  async function handleLogout() {
    setLoggingOut(true)
    try {
      await logout()
    } finally {
      setLoggingOut(false)
    }
  }

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand__mark" aria-hidden="true">R</span>
          <span>RenewFlow EIP</span>
        </div>
        <div className="topbar__user">
          <span className="topbar__name">{user.displayName}</span>
          <span className="badge">{ROLE_LABEL[user.role]}</span>
          <button type="button" className="btn" onClick={handleLogout} disabled={loggingOut}>
            {loggingOut ? '登出中…' : '登出'}
          </button>
        </div>
      </header>

      <div className="shell__body">
        <nav className="sidebar" aria-label="主選單">
          <ul>
            <li>
              <button type="button" className="nav-item nav-item--active" aria-current="page">
                首頁
              </button>
            </li>
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

        <main className="content">
          <h1>首頁</h1>
          <p className="muted">你好，{user.displayName}。以下功能將依階段陸續開放。</p>

          <div className="cards">
            <section className="card" aria-labelledby="card-me">
              <h2 id="card-me">我的資訊</h2>
              <dl className="info">
                <dt>員工編號</dt>
                <dd>{user.employeeNo}</dd>
                <dt>帳號</dt>
                <dd>{user.username}</dd>
                <dt>角色</dt>
                <dd>{ROLE_LABEL[user.role]}</dd>
              </dl>
            </section>

            {PENDING_CARDS.map((c) => (
              <section key={c.title} className="card card--pending" aria-label={`${c.title}（尚未啟用）`}>
                <h2>{c.title}</h2>
                <p className="muted">尚未啟用</p>
                <p className="muted small">預計於 {c.stage} 開放，目前沒有資料可顯示。</p>
              </section>
            ))}
          </div>
        </main>
      </div>
    </div>
  )
}
