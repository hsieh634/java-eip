import { useCallback, useEffect, useRef, useState } from 'react'
import * as attendanceApi from '../api/attendance'
import type { TodayAttendance } from '../api/attendance'
import { ApiError } from '../api/http'
import { useAuth } from '../auth/useAuth'

export type TodayState =
  | { kind: 'loading' }
  | { kind: 'ready'; data: TodayAttendance }
  | { kind: 'error'; message: string }

export interface Notice {
  kind: 'success' | 'error'
  text: string
}

type Action = 'in' | 'out'

/**
 * 今日出勤狀態與打卡動作。
 *
 * 核心原則：**任何打卡動作結束後（成功、409、網路中斷、5xx）都重新向後端查詢今日狀態**，
 * 畫面只顯示後端確認的結果，不自己假設「打卡成功」或「失敗」。
 * 例如回應遺失時，伺服器可能其實已經寫入；只有重新查詢才知道真相。
 * 401 → 更新登入狀態（回登入頁）；其他錯誤只顯示訊息，不會把使用者導回登入頁。
 */
export function useTodayAttendance(onChanged?: () => void) {
  const { refresh } = useAuth()
  const [state, setState] = useState<TodayState>({ kind: 'loading' })
  const [busy, setBusy] = useState<Action | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  // 防連按：state 更新是非同步的，用 ref 立即擋住第二次點擊。這只改善操作體驗，後端仍自行防重複。
  const inFlight = useRef(false)

  /** 讀取失敗的共同處理：401 → 登入狀態失效；其他 → 顯示錯誤與重試。 */
  const settleLoadError = useCallback(
    (error: unknown) => {
      if (error instanceof ApiError && error.status === 401) {
        void refresh()
        return
      }
      const message = error instanceof ApiError ? error.message : '發生未預期的錯誤'
      setState({ kind: 'error', message })
    },
    [refresh],
  )

  const reload = useCallback(async () => {
    try {
      setState({ kind: 'ready', data: await attendanceApi.fetchToday() })
    } catch (error) {
      settleLoadError(error)
    }
  }, [settleLoadError])

  // 首次載入。active 旗標：卸載（或開發模式重複掛載）後丟棄過期回應。
  useEffect(() => {
    let active = true
    attendanceApi.fetchToday().then(
      (data) => active && setState({ kind: 'ready', data }),
      (error: unknown) => active && settleLoadError(error),
    )
    return () => {
      active = false
    }
  }, [settleLoadError])

  const retry = useCallback(() => {
    setState({ kind: 'loading' })
    void reload()
  }, [reload])

  const perform = useCallback(
    async (action: Action) => {
      if (inFlight.current) return
      inFlight.current = true
      setBusy(action)
      setNotice(null)

      let outcome: Notice
      try {
        await (action === 'in' ? attendanceApi.clockIn() : attendanceApi.clockOut())
        outcome = { kind: 'success', text: action === 'in' ? '上班打卡成功。' : '下班打卡成功。' }
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          inFlight.current = false
          await refresh() // 登入已失效：回登入頁
          return
        }
        if (error instanceof ApiError && error.status === 409) {
          // 預期的業務衝突（例如在另一個分頁已經打過卡）：訊息來自後端，並更新為最新狀態
          outcome = { kind: 'error', text: `${error.message}，已更新為最新狀態。` }
        } else {
          // 網路中斷、回應遺失、5xx：結果未知，不能假設成功或失敗
          outcome = { kind: 'error', text: '無法確認打卡結果，已重新查詢目前狀態，請以下方顯示為準。' }
        }
      }

      await reload() // 無論結果如何，都以後端的真實狀態為準
      onChanged?.()
      setNotice(outcome)
      setBusy(null)
      inFlight.current = false
    },
    [reload, refresh, onChanged],
  )

  return {
    state,
    busy,
    notice,
    retry,
    clockIn: () => perform('in'),
    clockOut: () => perform('out'),
  }
}
