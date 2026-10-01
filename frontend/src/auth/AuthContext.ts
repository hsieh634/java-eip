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

export interface AuthContextValue {
  state: AuthState
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
  refresh: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)
