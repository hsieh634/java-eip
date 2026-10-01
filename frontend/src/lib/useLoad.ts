import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../api/http'
import { useAuth } from '../auth/useAuth'

export type LoadState<T> = { kind: 'loading' } | { kind: 'ready'; data: T } | { kind: 'error'; message: string }

/**
 * 依 key 載入資料。key 改變（例如換了員工）時，舊的結果不會被當成新的結果顯示（先回到 loading）；
 * 過期的回應會被丟棄；401 → 更新登入狀態（回登入頁）；其他錯誤 → error 狀態與可重試。
 * loader 為 null 表示目前不需要載入。
 */
export function useLoad<T>(loader: (() => Promise<T>) | null, key: string) {
  const { refresh } = useAuth()
  const [stored, setStored] = useState<{ key: string; value: LoadState<T> } | null>(null)
  const [tick, setTick] = useState(0)
  const enabled = loader !== null

  useEffect(() => {
    if (!loader) return
    let active = true
    loader().then(
      (data) => active && setStored({ key, value: { kind: 'ready', data } }),
      (error: unknown) => {
        if (!active) return
        if (error instanceof ApiError && error.status === 401) {
          void refresh()
          return
        }
        setStored({ key, value: { kind: 'error', message: error instanceof ApiError ? error.message : '發生未預期的錯誤' } })
      },
    )
    return () => {
      active = false
    }
    // loader 每次 render 都是新函式；以 key 代表「要載入什麼」，tick 代表「重新載入」
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, tick, enabled, refresh])

  const reload = useCallback(() => {
    setStored(null)
    setTick((t) => t + 1)
  }, [])

  /** 重新取得但保留目前畫面（背景更新，不閃爍）。 */
  const refetch = useCallback(() => setTick((t) => t + 1), [])

  const state: LoadState<T> = stored && stored.key === key ? stored.value : { kind: 'loading' }
  return { state, reload, refetch }
}
