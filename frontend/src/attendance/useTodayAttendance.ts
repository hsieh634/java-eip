import { useCallback, useEffect, useRef, useState } from 'react'
import * as attendanceApi from '../api/attendance'
import type { ClockLocation, LocationPolicy, TodayAttendance } from '../api/attendance'
import { ApiError } from '../api/http'
import { useAuth } from '../auth/useAuth'
import { GeolocationFailure, getCurrentLocation } from '../lib/geolocation'
import { taipeiToday } from '../lib/time'
import { useAutoRefresh } from './useAutoRefresh'

export type TodayState =
  | { kind: 'loading' }
  | { kind: 'ready'; data: TodayAttendance }
  | { kind: 'error'; message: string }

export type Action = 'in' | 'out'
/** 打卡進行中的階段：確認設定 → （需要時）取得位置 → 送出打卡 */
export type Phase = 'checking' | 'locating' | 'submitting'

export interface Notice {
  kind: 'success' | 'error'
  text: string
  /** 可以直接重試的失敗（定位被拒、逾時、精度不足、範圍外…）：畫面提供「重試」按鈕 */
  retry?: Action
}

const ACTION_LABEL: Record<Action, string> = { in: '上班打卡', out: '下班打卡' }

/** 按下按鈕之後，這個操作是否仍然成立（例如另一個分頁已經打過卡、畫面已更新）。 */
function stillAllowed(action: Action, state: TodayState): boolean {
  if (state.kind !== 'ready') return true // 狀態未知時交給後端判斷（後端仍會以 409 拒絕不成立的操作）
  return action === 'in' ? state.data.status === 'NOT_STARTED' : state.data.status === 'WORKING'
}

const requiresLocation = (action: Action, policy: LocationPolicy) => (action === 'in' ? policy.clockInRequired : policy.clockOutRequired)

/** 打卡失敗時給使用者看的說明。401 由呼叫端處理（回登入頁），這裡不會遇到。 */
function describeClockError(error: unknown, action: Action): Notice {
  if (error instanceof ApiError) {
    if (error.code === 'OUTSIDE_GEOFENCE') {
      const distance = Number(error.details.distanceMeters)
      const radius = Number(error.details.radiusMeters)
      // 只在顯示時四捨五入；是否在範圍內已由後端以未取整的距離判斷
      return { kind: 'error', retry: action, text: `不在公司範圍內：目前位置距離公司約 ${Math.round(distance)} 公尺，允許範圍是 ${radius} 公尺。` }
    }
    if (error.code === 'LOCATION_REQUIRED') {
      return { kind: 'error', retry: action, text: '定位設定剛剛更新，這次打卡需要定位。請再按一次（會詢問位置）。' }
    }
    if (error.status === 422) {
      return { kind: 'error', retry: action, text: error.message }
    }
    if (error.status === 409) {
      // 預期的業務衝突（例如在另一個分頁已經打過卡）：訊息來自後端，並更新為最新狀態
      return { kind: 'error', text: `${error.message}，已更新為最新狀態。` }
    }
    if (error.status === 403) {
      return { kind: 'error', text: `這次打卡被拒絕（${error.message}），已重新查詢目前狀態。` }
    }
    if (error.status >= 400 && error.status < 500) {
      return { kind: 'error', text: `打卡資料不正確：${error.message}` }
    }
  }
  // 網路中斷、回應遺失、5xx：結果未知，不能假設成功或失敗，也不會自動重送
  return { kind: 'error', text: '無法確認打卡結果，已重新查詢目前狀態，請以下方顯示為準（不會自動重送）。' }
}

/**
 * 今日出勤狀態與打卡動作。
 *
 * 核心原則：**任何打卡動作結束後（成功、409、網路中斷、5xx）都重新向後端查詢今日狀態**，
 * 畫面只顯示後端確認的結果，不自己假設「打卡成功」或「失敗」。
 * 例如回應遺失時，伺服器可能其實已經寫入；只有重新查詢才知道真相。
 * 401 → 更新登入狀態（回登入頁）；其他錯誤（含定位拒絕、403、5xx、網路錯誤）只顯示訊息，不會把使用者導回登入頁。
 */
export function useTodayAttendance(onChanged?: () => void) {
  const { refresh } = useAuth()
  const [state, setState] = useState<TodayState>({ kind: 'loading' })
  const [busy, setBusy] = useState<Action | null>(null)
  const [phase, setPhase] = useState<Phase | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  // 防連按：state 更新是非同步的，用 ref 立即擋住第二次點擊。這只改善操作體驗，後端仍自行防重複。
  const inFlight = useRef(false)
  // 元件是否仍在畫面上：切頁（卸載）後，晚到的定位結果不可再送出打卡
  const mounted = useRef(true)
  // 最新的今日狀態：非同步流程在「定位回來之後」用它重新確認操作是否仍成立
  const latestState = useRef<TodayState>(state)
  useEffect(() => {
    latestState.current = state
  }, [state])
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

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
      const data = await attendanceApi.fetchToday()
      if (mounted.current) setState({ kind: 'ready', data })
    } catch (error) {
      if (mounted.current) settleLoadError(error)
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

  // 視窗回到前景、切回分頁時重新查詢；另外每 10 秒檢查「台北的今天」是否已經換日，換日就重新查詢，
  // 避免隔天仍停留在昨天的「已完成」。
  useAutoRefresh(() => void reload())
  const shownDate = state.kind === 'ready' ? state.data.workDate : null
  useEffect(() => {
    if (!shownDate) return
    const timer = setInterval(() => {
      if (taipeiToday() !== shownDate) void reload()
    }, 10_000)
    return () => clearInterval(timer)
  }, [shownDate, reload])

  const retry = useCallback(() => {
    setState({ kind: 'loading' })
    void reload()
  }, [reload])

  /** 結束一次打卡流程：還原按鈕與階段；元件已卸載就不再更新畫面。 */
  const finish = useCallback((outcome: Notice) => {
    inFlight.current = false
    if (!mounted.current) return
    setNotice(outcome)
    setBusy(null)
    setPhase(null)
  }, [])

  /**
   * 取得位置並在送出前確認三件事：元件仍在畫面上（否則 'abandoned'）、這個操作仍成立、精度在上限內。
   * 回傳位置，或一個說明為什麼不送出的 Notice。
   */
  const locate = useCallback(async (action: Action, policy: LocationPolicy): Promise<ClockLocation | Notice | 'abandoned'> => {
    if (mounted.current) setPhase('locating')
    let location: ClockLocation
    try {
      location = await getCurrentLocation()
    } catch (error) {
      if (!mounted.current) return 'abandoned'
      const text = error instanceof GeolocationFailure ? error.message : '取得位置時發生錯誤。'
      return { kind: 'error', retry: action, text: `${text}（這次沒有打卡）` }
    }
    if (!mounted.current) return 'abandoned'
    if (!stillAllowed(action, latestState.current)) {
      return { kind: 'error', text: `今日狀態已經改變，已取消這次「${ACTION_LABEL[action]}」，請確認目前狀態後再操作。` }
    }
    if (location.accuracy > policy.maxAccuracyMeters) {
      return {
        kind: 'error',
        retry: action,
        text: `定位精度不足（約 ${Math.round(location.accuracy)} 公尺，需在 ${policy.maxAccuracyMeters} 公尺以內），這次沒有打卡。請移到訊號較好的地方後重試。`,
      }
    }
    return location
  }, [])

  /**
   * 一次打卡。action 在按下時就決定（閉包保存），之後不會因畫面刷新而從「上班」變成「下班」。
   * 1. 向後端取得最新的定位要求（取不到就不打卡，也不會改用不限制的模式）
   * 2. 需要定位才呼叫瀏覽器定位；不需要就沿用原本無本文的打卡
   * 3. 定位回來後確認：元件仍在畫面上、這個操作仍成立、精度在上限內——否則不送出
   * 4. 送出打卡；不論成功或失敗都重新查詢今日狀態（網路中斷時不自動重送）
   */
  const perform = useCallback(
    async (action: Action) => {
      if (inFlight.current) return
      inFlight.current = true
      setBusy(action)
      setPhase('checking')
      setNotice(null)

      let policy: LocationPolicy
      try {
        policy = await attendanceApi.fetchLocationPolicy()
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          inFlight.current = false
          await refresh()
          return
        }
        finish({ kind: 'error', retry: action, text: '無法確認定位設定，這次沒有打卡。請稍後再試。' })
        return
      }

      let location: ClockLocation | undefined
      if (requiresLocation(action, policy)) {
        const located = await locate(action, policy)
        if (located === 'abandoned') {
          inFlight.current = false // 已離開頁面：晚到的定位結果不送出、不更新畫面
          return
        }
        if ('kind' in located) {
          if (located.retry === undefined) await reload() // 操作已不成立：顯示最新狀態
          finish(located)
          return
        }
        location = located
      }

      if (mounted.current) setPhase('submitting')
      let outcome: Notice
      try {
        await (action === 'in' ? attendanceApi.clockIn(location) : attendanceApi.clockOut(location))
        outcome = { kind: 'success', text: action === 'in' ? '上班打卡成功。' : '下班打卡成功。' }
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          inFlight.current = false
          await refresh() // 登入已失效：回登入頁
          return
        }
        outcome = describeClockError(error, action)
      }

      await reload() // 無論結果如何，都以後端的真實狀態為準
      onChanged?.()
      finish(outcome)
    },
    [reload, refresh, onChanged, finish, locate],
  )

  return {
    state,
    busy,
    phase,
    notice,
    retry,
    reload,
    clockIn: () => perform('in'),
    clockOut: () => perform('out'),
    perform,
  }
}
