import type { CurrentUser, Role } from '../api/auth'
import { TodayAttendanceCard } from '../components/TodayAttendanceCard'

const ROLE_LABEL: Record<Role, string> = {
  EMPLOYEE: '員工',
  MANAGER: '主管',
  ADMIN: '管理員',
}

/** 尚未實作的卡片。不顯示任何假統計，只標示預計開放的階段。 */
const PENDING_CARDS = [
  { title: '本週工時', stage: 'T04' },
  { title: '待審核', stage: 'T05' },
] as const

/** 首頁內容（頂欄與側邊選單在 AppShell）。今日打卡卡片顯示真實資料。 */
export function HomePage({ user }: Readonly<{ user: CurrentUser }>) {
  return (
    <>
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

        <TodayAttendanceCard />

        {PENDING_CARDS.map((c) => (
          <section key={c.title} className="card card--pending" aria-label={`${c.title}（尚未啟用）`}>
            <h2>{c.title}</h2>
            <p className="muted">尚未啟用</p>
            <p className="muted small">預計於 {c.stage} 開放，目前沒有資料可顯示。</p>
          </section>
        ))}
      </div>
    </>
  )
}
