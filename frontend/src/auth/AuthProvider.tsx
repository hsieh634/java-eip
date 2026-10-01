import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import * as authApi from '../api/auth'
import { ApiError } from '../api/http'
import { AuthContext, type AuthContextValue, type AuthState, type LogoutResult } from './AuthContext'

/** 401 是「確定未登入」；其他錯誤（連不上、500）不能誤判成未登入。 */
function stateFromError(error: unknown): AuthState {
  if (error instanceof ApiError && error.status === 401) {
    return { status: 'anonymous' }
  }
  const message = error instanceof ApiError ? error.message : '發生未預期的錯誤'
  return { status: 'unavailable', message }
}

export function AuthProvider({ children }: Readonly<{ children: ReactNode }>) {
  const [state, setState] = useState<AuthState>({ status: 'loading' })

  /**
   * 向後端確認「我現在是誰」，更新狀態並回傳新狀態。後端是唯一的事實來源，前端不自己記登入狀態。
   * 此函式不會 reject（所有錯誤都轉成狀態）。
   */
  const loadState = useCallback(async (): Promise<AuthState> => {
    let next: AuthState
    try {
      next = { status: 'authenticated', user: await authApi.fetchMe() }
    } catch (error) {
      next = stateFromError(error)
    }
    setState(next)
    return next
  }, [])

  const refresh = useCallback(async () => {
    await loadState()
  }, [loadState])

  // 頁面載入時問一次。同時，無論結果是 200 或 401，後端回應都會帶 XSRF-TOKEN Cookie，
  // 這是之後登入（POST）能通過 CSRF 檢查的前提。
  // active 旗標：元件卸載（或 StrictMode 開發模式的重複掛載）後，丟棄過期的回應。
  useEffect(() => {
    let active = true
    authApi.fetchMe().then(
      (user) => active && setState({ status: 'authenticated', user }),
      (error: unknown) => active && setState(stateFromError(error)),
    )
    return () => {
      active = false
    }
  }, [])

  const login = useCallback(async (username: string, password: string) => {
    // 失敗時拋出 ApiError，由登入頁顯示訊息；成功才改變登入狀態
    const user = await authApi.login(username, password)
    setState({ status: 'authenticated', user })
  }, [])

  /**
   * 登出不假設成功：不論登出請求的結果，都重新問後端「我現在是誰」，以後端回答為準。
   *  - 後端回 401 → 已登出（即使登出請求報錯，例如 Session 剛好已過期）→ 狀態變 anonymous，App 回登入頁
   *  - 後端說仍是登入 → 回傳 ok:false 與登出請求的錯誤，由畫面顯示失敗並允許重試
   *  - 後端連不上 → 狀態變 unavailable，App 顯示無法連線與重試按鈕
   * 登出請求的例外在這裡被 catch，因此呼叫端不會遇到未處理的 Promise 拒絕。
   */
  const logout = useCallback(async (): Promise<LogoutResult> => {
    let logoutError: unknown = null
    try {
      await authApi.logout()
    } catch (error) {
      logoutError = error
    }

    const next = await loadState()
    if (next.status === 'anonymous') {
      return { ok: true }
    }
    return { ok: false, error: logoutError }
  }, [loadState])

  const value = useMemo<AuthContextValue>(() => ({ state, login, logout, refresh }), [state, login, logout, refresh])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
