import { useSyncExternalStore } from 'react'

export type View = 'home' | 'attendance'

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
  return hash === '#/attendance' ? 'attendance' : 'home'
}
