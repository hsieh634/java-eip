import { createContext } from 'react'
import type { CurrentUser } from '../api/auth'

/**
 * 登入狀態有四種，不能只用「有沒有 user」表示：
 * - loading：還不知道（剛開啟頁面，正在問後端）；此時若直接顯示登入頁會閃一下
 * - anonymous：確定未登入
 * - authenticated：已登入
 * - unavailable：連不上後端；不能誤判成「未登入」
 */
export type AuthState =
  | { status: 'loading' }
  | { status: 'anonymous' }
  | { status: 'authenticated'; user: CurrentUser }
  | { status: 'unavailable'; message: string }

/**
 * 登出的結果。logout() 永遠不會 reject，失敗以結果表示，由畫面決定如何提示：
 * - ok:true  → 向後端確認過，已不是登入狀態
 * - ok:false → 向後端確認過仍是登入狀態（或無法確認）；error 是登出請求本身的錯誤
 */
export type LogoutResult = { ok: true } | { ok: false; error: unknown }

export interface AuthContextValue {
  state: AuthState
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<LogoutResult>
  refresh: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)
