import { useAuth } from './auth/useAuth'
import { HomePage } from './pages/HomePage'
import { LoginPage } from './pages/LoginPage'

/**
 * 以登入狀態決定顯示哪個畫面。T02 只有兩個畫面，所以不引入路由套件；
 * 畫面變多（T03 起）再加 react-router。
 * 注意：這只是「畫面」的切換，真正的保護在後端——就算有人強行顯示首頁，沒有 Session 也拿不到任何資料。
 */
export default function App() {
  const { state, refresh } = useAuth()

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
      return <HomePage user={state.user} />
  }
}
