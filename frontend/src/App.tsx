import { useAuth } from './auth/useAuth'
import { AppShell } from './components/AppShell'
import { useHashView } from './navigation/useHashView'
import { AdminAttendancePage } from './pages/AdminAttendancePage'
import { AttendancePage } from './pages/AttendancePage'
import { GeofenceSettingsPage } from './pages/GeofenceSettingsPage'
import { LeaveAdminPage } from './pages/LeaveAdminPage'
import { LeaveApprovalsPage } from './pages/LeaveApprovalsPage'
import { LeavePage } from './pages/LeavePage'
import { OrganizationAdminPage } from './pages/OrganizationAdminPage'
import { ProjectsAdminPage } from './pages/ProjectsAdminPage'
import { TimesheetPage } from './pages/TimesheetPage'
import { HomePage } from './pages/HomePage'
import { LoginPage } from './pages/LoginPage'

/**
 * 以登入狀態決定顯示哪個畫面；登入後以網址 hash（#/attendance）決定顯示哪一頁。
 * 頁面仍少，所以不引入路由套件；畫面變多再評估 react-router。
 * 注意：這只是「畫面」的切換，真正的保護在後端——就算有人強行顯示頁面，沒有有效 Session 也拿不到任何資料。
 */
export default function App() {
  const { state, refresh } = useAuth()
  const view = useHashView()

  switch (state.status) {
    case 'loading':
      return <p className="center-message">載入中…</p>
    case 'unavailable':
      return (
        <div className="center-message">
          <p role="alert">{state.message}</p>
          <button type="button" className="btn" onClick={() => void refresh()}>
            重試
          </button>
        </div>
      )
    case 'anonymous':
      return <LoginPage />
    case 'authenticated':
      return (
        <AppShell user={state.user} view={view}>
          {view === 'attendance' && <AttendancePage />}
          {view === 'admin-attendance' && <AdminAttendancePage user={state.user} />}
          {view === 'admin-geofence' && <GeofenceSettingsPage user={state.user} />}
          {view === 'timesheets' && <TimesheetPage />}
          {view === 'admin-projects' && <ProjectsAdminPage user={state.user} />}
          {view === 'admin-organization' && <OrganizationAdminPage user={state.user} />}
          {view === 'leave' && <LeavePage />}
          {view === 'leave-approvals' && <LeaveApprovalsPage user={state.user} />}
          {view === 'admin-leave' && <LeaveAdminPage user={state.user} />}
          {view === 'home' && <HomePage user={state.user} />}
        </AppShell>
      )
  }
}
