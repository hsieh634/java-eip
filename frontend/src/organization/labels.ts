import type { Role } from '../api/auth'
import type { OrgChange, OrgHistoryEntry } from '../api/organization'

export const ROLE_LABEL: Record<Role, string> = {
  EMPLOYEE: '員工（EMPLOYEE）',
  MANAGER: '主管（MANAGER）',
  ADMIN: '管理員（ADMIN）',
}

export const ROLE_SHORT: Record<Role, string> = {
  EMPLOYEE: '員工',
  MANAGER: '主管',
  ADMIN: '管理員',
}

export const ACTION_LABEL: Record<OrgHistoryEntry['action'], string> = {
  DEPARTMENT_CREATED: '建立部門',
  DEPARTMENT_UPDATED: '修改部門',
  EMPLOYEE_CREATED: '建立員工',
  EMPLOYEE_UPDATED: '修改員工',
}

const FIELD_LABEL: Record<string, string> = {
  code: '代碼',
  name: '名稱',
  active: '部門狀態',
  employeeNo: '員工編號',
  username: '帳號',
  displayName: '顯示名稱',
  role: '角色',
  enabled: '帳號狀態',
  department: '部門',
  manager: '直屬主管',
}

export const fieldLabel = (field: string) => FIELD_LABEL[field] ?? field

/** 歷程中的值轉成文字：null＝（無）；部門與主管顯示標籤；啟用狀態與角色轉成中文。 */
export function formatChangeValue(change: OrgChange, value: unknown): string {
  if (value === null || value === undefined) return '（無）'
  if (typeof value === 'boolean') {
    if (change.field === 'active' || change.field === 'enabled') return value ? '啟用' : '停用'
    return value ? '是' : '否'
  }
  if (typeof value === 'object' && 'label' in value) return String((value as { label: unknown }).label)
  if (change.field === 'role' && typeof value === 'string' && value in ROLE_SHORT) return ROLE_SHORT[value as Role]
  return String(value)
}
