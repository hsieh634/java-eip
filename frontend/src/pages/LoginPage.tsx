import { useState, type FormEvent } from 'react'
import { ApiError } from '../api/http'
import { PASSWORD_MAX_BYTES, utf8ByteLength } from '../auth/passwordPolicy'
import { useAuth } from '../auth/useAuth'

export function LoginPage() {
  const { login } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  // 提早提示：密碼以 UTF-8 位元組計算上限（不是字元數）。後端仍會驗證，這裡不能取代後端。
  const passwordBytes = utf8ByteLength(password)
  const passwordTooLong = passwordBytes > PASSWORD_MAX_BYTES

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (submitting || passwordTooLong) return // 原因：防止快速連按送出多次；這只是改善操作體驗，後端不依賴它

    setError(null)
    setSubmitting(true)
    try {
      await login(username.trim(), password) // 只 trim 帳號；密碼一律照原樣送出，不 trim、不截斷
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
          aria-describedby={passwordTooLong ? 'password-hint' : undefined}
          aria-invalid={passwordTooLong}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        {passwordTooLong && (
          <p id="password-hint" className="form-error" role="status">
            密碼太長：目前 {passwordBytes} 位元組，上限 {PASSWORD_MAX_BYTES} 位元組（英數字 1 位元組、中文字約 3 位元組、表情符號 4 位元組）。
          </p>
        )}

        <p className="form-error" role="alert" aria-live="polite">
          {error}
        </p>

        <button
          type="submit"
          className="btn btn--primary"
          disabled={submitting || !username || !password || passwordTooLong}
        >
          {submitting ? '登入中…' : '登入'}
        </button>
      </form>
    </main>
  )
}
