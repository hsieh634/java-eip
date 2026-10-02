import { useEffect, useRef } from 'react'

export interface Lifetime {
  alive: boolean
}

/**
 * 元件「這一次掛載」的有效期間（與 T03.2 打卡卡片相同的作法）。
 * 卸載時 alive 變成 false 且永不復活；重新掛載（含 StrictMode 的模擬卸載再掛載）會換新的物件。
 * 非同步操作開始時記住 `lifetime.current`，回來後用它判斷是否還能更新畫面。
 */
export function useLifetime() {
  const lifetime = useRef<Lifetime>({ alive: false })
  useEffect(() => {
    const current: Lifetime = { alive: true }
    lifetime.current = current
    return () => {
      current.alive = false
    }
  }, [])
  return lifetime
}
