import type { CurrentUser, Role } from '../api/auth'
import { TodayAttendanceCard } from '../components/TodayAttendanceCard'

const ROLE_LABEL: Record<Role, string> = {
  EMPLOYEE: '員工',
  MANAGER: '主管',
  ADMIN: '管理員',
}

/** 首頁內容（頂欄與側邊選單在 AppShell）。今日打卡卡片顯示真實資料。 */
export function HomePage({ user }: Readonly<{ user: CurrentUser }>) {
  return (
    <>
      <h1>首頁</h1>
      <p className="muted">你好，{user.displayName}。左側選單可以進入各項功能。</p>

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

        <section className="card" aria-labelledby="card-timesheet">
          <h2 id="card-timesheet">工時回報</h2>
          <p className="muted">記錄自己每天在各專案的工作內容與時數，並查看每日與期間合計。</p>
          <a className="btn" href="#/timesheets">
            前往工時回報
          </a>
        </section>

        <section className="card" aria-labelledby="card-leave">
          <h2 id="card-leave">請假</h2>
          <p className="muted">送出事假、病假或特休申請，查看審核結果；待審中的申請可以撤回。</p>
          <div className="actions">
            <a className="btn" href="#/leave">
              前往請假
            </a>
            {user.role !== 'EMPLOYEE' && (
              <a className="btn" href="#/leave/approvals">
                請假審核
              </a>
            )}
          </div>
        </section>
      </div>
    </>
  )
}
