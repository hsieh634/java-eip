import type { Role } from './auth'
import { api } from './http'

/** 部門（對應後端 DepartmentResponse）。平面結構，沒有上下層。 */
export interface Department {
  id: number
  code: string
  name: string
  active: boolean
  activeEmployeeCount: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface DepartmentRef {
  id: number
  code: string
  name: string
  active: boolean
}

export interface EmployeeRef {
  id: number
  employeeNo: string
  displayName: string
  role: Role
  enabled: boolean
}

/** 管理端看到的員工（對應後端 EmployeeAdminResponse）。沒有任何密碼欄位。 */
export interface AdminEmployee {
  id: number
  employeeNo: string
  username: string
  displayName: string
  role: Role
  enabled: boolean
  department: DepartmentRef | null
  manager: EmployeeRef | null
  subordinateCount: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface EmployeePage {
  items: AdminEmployee[]
  page: number
  size: number
  total: number
}

export interface ManagerOption {
  id: number
  employeeNo: string
  displayName: string
  role: Role
}

export type StatusFilter = 'ALL' | 'ACTIVE' | 'INACTIVE'

/** 歷程中的一個欄位變更：部門與主管是 { id, label }，其他是字串或布林；null＝未指派／無。 */
export interface OrgChange {
  field: string
  before: unknown
  after: unknown
}

export interface OrgHistoryEntry {
  id: number
  action: 'DEPARTMENT_CREATED' | 'DEPARTMENT_UPDATED' | 'EMPLOYEE_CREATED' | 'EMPLOYEE_UPDATED'
  reason: string | null
  changes: OrgChange[]
  actor: { id: number; employeeNo: string; displayName: string } | null
  source: 'ADMIN' | 'SYSTEM_DEMO_SEED'
  occurredAt: string
}

const DEPARTMENTS = '/api/admin/departments'
const EMPLOYEES = '/api/admin/employees'

export const fetchDepartments = () => api<{ departments: Department[] }>(DEPARTMENTS).then((r) => r.departments)
export const createDepartment = (body: { code: string; name: string }) => api<Department>(DEPARTMENTS, { method: 'POST', body })
/** 修改名稱與啟用狀態（代碼不可修改）。啟用狀態變更時 reason 必填。版本不符 → 409 DEPARTMENT_CHANGED。 */
export const updateDepartment = (id: number, body: { name: string; active: boolean; expectedVersion: number; reason: string | null }) =>
  api<Department>(`${DEPARTMENTS}/${id}`, { method: 'PUT', body })
export const fetchDepartmentHistory = (id: number) =>
  api<{ entries: OrgHistoryEntry[] }>(`${DEPARTMENTS}/${id}/history`).then((r) => r.entries)

export function fetchEmployees(params: { q: string; status: StatusFilter; page: number; size: number }) {
  const search = new URLSearchParams({ status: params.status, page: String(params.page), size: String(params.size) })
  if (params.q.trim()) search.set('q', params.q.trim())
  return api<EmployeePage>(`${EMPLOYEES}?${search.toString()}`)
}
export const fetchEmployee = (id: number) => api<AdminEmployee>(`${EMPLOYEES}/${id}`)
export const fetchManagerOptions = () => api<{ options: ManagerOption[] }>(`${EMPLOYEES}/manager-options`).then((r) => r.options)
export const fetchEmployeeHistory = (id: number) => api<{ entries: OrgHistoryEntry[] }>(`${EMPLOYEES}/${id}/history`).then((r) => r.entries)

export interface CreateEmployeeBody {
  employeeNo: string
  username: string
  displayName: string
  role: Role
  departmentId: number | null
  managerId: number | null
  /** 原樣送出（不 trim、不截斷）；後端只保存雜湊，不會回傳 */
  initialPassword: string
  reason: string | null
}
export const createEmployee = (body: CreateEmployeeBody) => api<AdminEmployee>(EMPLOYEES, { method: 'POST', body })

export interface UpdateEmployeeBody {
  displayName: string
  role: Role
  enabled: boolean
  departmentId: number | null
  managerId: number | null
  expectedVersion: number
  reason: string | null
}
/** 員工編號、帳號、密碼不能在這裡修改。版本不符 → 409 EMPLOYEE_CHANGED。 */
export const updateEmployee = (id: number, body: UpdateEmployeeBody) => api<AdminEmployee>(`${EMPLOYEES}/${id}`, { method: 'PUT', body })
