import { useState, type FormEvent } from 'react'
import { ApiError } from '../api/http'
import { useAuth } from '../auth/useAuth'

export function LoginPage() {
  const { login } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (submitting) return // 原因：防止快速連按送出多次；這只是改善操作體驗，後端不依賴它

    setError(null)
    setSubmitting(true)
    try {
      await login(username.trim(), password)
      // 成功後 AuthProvider 狀態改變，App 會自動切到首頁
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '登入失敗，請稍後再試')
      setPassword('') // 失敗後清掉密碼欄位
      setSubmitting(false)
    }
  }

  return (
    <main className="login-page">
      <form className="login-card" onSubmit={handleSubmit} noValidate>
        <div className="brand brand--large">
          <span className="brand__mark" aria-hidden="true">R</span>
          <span>RenewFlow EIP</span>
        </div>
        <h1>登入</h1>

        <label htmlFor="username">帳號</label>
        <input
          id="username"
          name="username"
          autoComplete="username"
          autoFocus
          maxLength={50}
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />

        <label htmlFor="password">密碼</label>
        <input
          id="password"
          name="password"
          type="password"
          autoComplete="current-password"
          maxLength={72}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />

        <p className="form-error" role="alert" aria-live="polite">
          {error}
        </p>

        <button type="submit" className="btn btn--primary" disabled={submitting || !username || !password}>
          {submitting ? '登入中…' : '登入'}
        </button>
      </form>
    </main>
  )
}
