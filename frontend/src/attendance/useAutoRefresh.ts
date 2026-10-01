import { useEffect, useRef } from 'react'

/**
 * 在這些時機重新向後端查詢，避免畫面停在過期的狀態（例如隔天仍顯示昨天已完成、管理員已核准但畫面沒變）：
 *  - 視窗取得焦點、分頁重新可見（兩次之間至少隔 3 秒，避免連續觸發）
 *  - 可選：固定間隔輪詢（pollMs；null＝不輪詢）。例如有待審申請時每 30 秒一次。
 * 不使用 WebSocket；這些觸發已足夠，也不需要新套件。
 */
export function useAutoRefresh(refresh: () => void, pollMs: number | null = null) {
  const latest = useRef(refresh)
  useEffect(() => {
    latest.current = refresh
  })

  useEffect(() => {
    let last = Date.now()
    const run = () => {
      const now = Date.now()
      if (now - last < 3000) return
      last = now
      latest.current()
    }
    const onVisible = () => {
      if (document.visibilityState === 'visible') run()
    }
    window.addEventListener('focus', run)
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      window.removeEventListener('focus', run)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [])

  useEffect(() => {
    if (!pollMs) return
    const timer = setInterval(() => latest.current(), pollMs)
    return () => clearInterval(timer)
  }, [pollMs])
}
