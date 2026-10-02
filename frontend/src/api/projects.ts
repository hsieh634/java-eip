import { api } from './http'

/** 管理端看到的完整專案（對應後端 ProjectResponse）。 */
export interface Project {
  id: number
  code: string
  name: string
  description: string | null
  active: boolean
  /** 修改時帶回，用來偵測「已被其他管理員改過」 */
  version: number
  createdAt: string
  updatedAt: string
}

/** 一般使用者可選擇的專案（只有啟用中的）。 */
export interface SelectableProject {
  id: number
  code: string
  name: string
  description: string | null
}

export const fetchSelectableProjects = () => api<{ projects: SelectableProject[] }>('/api/projects').then((r) => r.projects)

export const fetchAllProjects = () => api<{ projects: Project[] }>('/api/admin/projects').then((r) => r.projects)
export const createProject = (body: { code: string; name: string; description: string | null }) =>
  api<Project>('/api/admin/projects', { method: 'POST', body })
/** 修改名稱、說明、啟用狀態（代碼建立後不可修改）。版本不符 → 409 PROJECT_CHANGED。 */
export const updateProject = (id: number, body: { name: string; description: string | null; active: boolean; expectedVersion: number }) =>
  api<Project>(`/api/admin/projects/${id}`, { method: 'PUT', body })
