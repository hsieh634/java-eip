import { useSyncExternalStore } from 'react'

export type View =
  | 'home'
  | 'attendance'
  | 'timesheets'
  | 'leave'
  | 'leave-approvals'
  | 'admin-attendance'
  | 'admin-geofence'
  | 'admin-projects'
  | 'admin-organization'
  | 'admin-leave'
  | 'admin-leave-workflow'

function subscribe(onChange: () => void) {
  window.addEventListener('hashchange', onChange)
  return () => window.removeEventListener('hashchange', onChange)
}

/**
 * 用網址的 hash（#/attendance）決定顯示哪個畫面：重新整理與瀏覽器「上一頁」都能正常運作，
 * 又不需要為兩個畫面引入路由套件（畫面變多時再評估 react-router）。
 */
export function useHashView(): View {
  const hash = useSyncExternalStore(subscribe, () => window.location.hash)
  if (hash === '#/attendance') return 'attendance'
  if (hash === '#/admin/attendance') return 'admin-attendance'
  if (hash === '#/admin/geofence') return 'admin-geofence'
  if (hash === '#/timesheets') return 'timesheets'
  if (hash === '#/admin/projects') return 'admin-projects'
  if (hash === '#/admin/organization') return 'admin-organization'
  if (hash === '#/leave') return 'leave'
  if (hash === '#/leave/approvals') return 'leave-approvals'
  if (hash === '#/admin/leave') return 'admin-leave'
  if (hash === '#/admin/leave/workflow') return 'admin-leave-workflow'
  return 'home'
}
