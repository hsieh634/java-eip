import { api } from './http'

export type Role = 'EMPLOYEE' | 'MANAGER' | 'ADMIN'

/** 對應後端 CurrentUserResponse。 */
export interface CurrentUser {
  id: number
  employeeNo: string
  username: string
  displayName: string
  role: Role
}

export const fetchMe = () => api<CurrentUser>('/api/auth/me')

export const login = (username: string, password: string) =>
  api<CurrentUser>('/api/auth/login', { method: 'POST', body: { username, password } })

export const logout = () => api<void>('/api/auth/logout', { method: 'POST' })
