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
 * 「這個 hook 實例目前這一次掛載」的有效期間。卸載（含 React StrictMode 開發模式的模擬卸載）時 alive 變成 false，
 * 而且永遠不會再變回 true——重新掛載會換一個新物件。非同步流程開始時記住當下的物件，回來後用它判斷「我還有效嗎」。
 * 這比單一個 mounted 布林值可靠：StrictMode 會「卸載再掛載」，布林值會重新變成 true，舊流程就分辨不出自己已經過期。
 */
interface Lifetime {
  alive: boolean
}

/** 一次打卡操作。action 在按下時就固定；只有 activeOperation 指向它、且所屬掛載仍有效時，才算有效。 */
interface Operation {
  readonly id: number
  readonly action: Action
  readonly lifetime: Lifetime
}

/**
 * 今日出勤狀態與打卡動作。
 *
 * 核心原則：**任何打卡動作結束後（成功、409、網路中斷、5xx）都重新向後端查詢今日狀態**，
 * 畫面只顯示後端確認的結果，不自己假設「打卡成功」或「失敗」。
 * 例如回應遺失時，伺服器可能其實已經寫入；只有重新查詢才知道真相。
 * 401 → 更新登入狀態（回登入頁）；其他錯誤（含定位拒絕、403、5xx、網路錯誤）只顯示訊息，不會把使用者導回登入頁。
 *
 * 兩條非同步規則（T03.2 第 1 輪複審 R1／R2）：
 * - 今日狀態：所有查詢（首次載入、手動刷新、回到前景、跨日、申請結果、打卡後）都走 {@link load}，
 *   每次發出時取得新的序號，只有「最後發出的那一個」的回應（成功或失敗）會被採用。
 * - 打卡操作：每次操作有自己的識別；設定回來後、呼叫定位前、定位回來後、送出前都確認仍有效。
 *   過期的操作不定位、不送出、不更新畫面、不呼叫 onChanged。已送出的 POST 無法撤回（見 perform 的說明）。
 */
export function useTodayAttendance(onChanged?: () => void) {
  const { refresh } = useAuth()
  const [state, setState] = useState<TodayState>({ kind: 'loading' })
  const [busy, setBusy] = useState<Action | null>(null)
  const [phase, setPhase] = useState<Phase | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)

  // 目前掛載的有效期間（effect 中建立；在 effect 執行前的初始值視為無效）
  const lifetime = useRef<Lifetime>({ alive: false })
  // 進行中的打卡操作（ref：連按時第二次點擊立即看得到，不必等 re-render）
  const activeOperation = useRef<Operation | null>(null)
  const operationSeq = useRef(0)
  // 今日狀態查詢的序號：只採用最後發出的那一次
  const loadSeq = useRef(0)
  // 最新的今日狀態：採用回應時「同步」更新，打卡流程可以立即看到（不必等 effect）
  const latestState = useRef<TodayState>(state)

  useEffect(() => {
    const current: Lifetime = { alive: true }
    lifetime.current = current
    return () => {
      current.alive = false
      activeOperation.current = null
    }
  }, [])

  const isCurrent = useCallback((op: Operation) => op.lifetime.alive && activeOperation.current === op, [])

  /** 採用一個今日狀態：React state 與判斷用的 ref 一起更新。 */
  const applyState = useCallback((next: TodayState) => {
    latestState.current = next
    setState(next)
  }, [])

  /**
   * 查詢今日狀態（唯一入口）。回傳的 Promise 在這次查詢「處理完」時完成（被採用或因過期被丟棄）。
   * 過期（之後又發出了新查詢，或已卸載）的成功與失敗都不採用；401 也只在最新的那一次才觸發登入狀態刷新。
   */
  const load = useCallback((): Promise<void> => {
    const seq = ++loadSeq.current
    const life = lifetime.current
    const isLatest = () => life.alive && seq === loadSeq.current
    return attendanceApi.fetchToday().then(
      (data) => {
        if (isLatest()) applyState({ kind: 'ready', data })
      },
      (error: unknown) => {
        if (!isLatest()) return
        if (error instanceof ApiError && error.status === 401) {
          void refresh()
          return
        }
        applyState({ kind: 'error', message: error instanceof ApiError ? error.message : '發生未預期的錯誤' })
      },
    )
  }, [applyState, refresh])

  // 首次載入（StrictMode 開發模式下會執行兩次；第一次所屬的掛載已失效，回應會被丟棄）
  useEffect(() => {
    void load()
  }, [load])

  // 視窗回到前景、切回分頁時重新查詢；另外每 10 秒檢查「台北的今天」是否已經換日，換日就重新查詢，
  // 避免隔天仍停留在昨天的「已完成」。
  useAutoRefresh(() => void load())
  const shownDate = state.kind === 'ready' ? state.data.workDate : null
  useEffect(() => {
    if (!shownDate) return
    const timer = setInterval(() => {
      if (taipeiToday() !== shownDate) void load()
    }, 10_000)
    return () => clearInterval(timer)
  }, [shownDate, load])

  const retry = useCallback(() => {
    applyState({ kind: 'loading' })
    void load()
  }, [applyState, load])

  /** 結束一次打卡操作：只有仍有效的操作才會還原按鈕、階段與顯示結果；過期的什麼都不做。 */
  const end = useCallback(
    (op: Operation, outcome: Notice | null) => {
      if (!isCurrent(op)) return
      activeOperation.current = null
      if (outcome) setNotice(outcome)
      setBusy(null)
      setPhase(null)
    },
    [isCurrent],
  )

  /**
   * 取得位置。呼叫瀏覽器定位「之前」與定位結果「回來後」都確認操作仍有效（過期 → 'stale'），
   * 並檢查精度上限。回傳位置，或一個說明為什麼不送出的 Notice。
   */
  const locate = useCallback(
    async (op: Operation, policy: LocationPolicy): Promise<ClockLocation | Notice | 'stale'> => {
      if (!isCurrent(op)) return 'stale'
      setPhase('locating')
      let location: ClockLocation
      try {
        location = await getCurrentLocation()
      } catch (error) {
        if (!isCurrent(op)) return 'stale'
        const text = error instanceof GeolocationFailure ? error.message : '取得位置時發生錯誤。'
        return { kind: 'error', retry: op.action, text: `${text}（這次沒有打卡）` }
      }
      if (!isCurrent(op)) return 'stale'
      if (location.accuracy > policy.maxAccuracyMeters) {
        return {
          kind: 'error',
          retry: op.action,
          text: `定位精度不足（約 ${Math.round(location.accuracy)} 公尺，需在 ${policy.maxAccuracyMeters} 公尺以內），這次沒有打卡。請移到訊號較好的地方後重試。`,
        }
      }
      return location
    },
    [isCurrent],
  )

  /**
   * 一次打卡。action 在按下時就決定（存在 Operation 裡），之後不會因畫面刷新而從「上班」變成「下班」。
   * 1. 向後端取得最新的定位要求（取不到就不打卡，也不會改用不限制的模式）
   * 2. 設定回來後確認操作仍有效；需要定位才呼叫瀏覽器定位（呼叫前、回來後都再確認）
   * 3. 送出前最後確認：操作仍有效、已知的最新今日狀態仍允許這個動作
   * 4. 送出打卡；不論成功或失敗都重新查詢今日狀態（網路中斷時不自動重送）
   *
   * 取消的界線：POST 送出「之前」卸載 → 一定不送。POST 送出「之後」卸載 → 前端不再更新畫面，
   * 但無法撤回伺服器可能已完成的寫入（切頁或中止請求都不會讓後端回滾）；重新進入畫面時會查詢真實狀態，且不會自動重送。
   */
  const perform = useCallback(
    async (action: Action) => {
      if (activeOperation.current) return // 防連按：同一時間只有一個操作
      const op: Operation = { id: ++operationSeq.current, action, lifetime: lifetime.current }
      activeOperation.current = op
      setBusy(action)
      setPhase('checking')
      setNotice(null)

      let policy: LocationPolicy
      try {
        policy = await attendanceApi.fetchLocationPolicy()
      } catch (error) {
        if (!isCurrent(op)) return
        if (error instanceof ApiError && error.status === 401) {
          end(op, null)
          await refresh()
          return
        }
        end(op, { kind: 'error', retry: action, text: '無法確認定位設定，這次沒有打卡。請稍後再試。' })
        return
      }
      if (!isCurrent(op)) return // 設定回來時已切頁：不定位、不送出

      let location: ClockLocation | undefined
      if (requiresLocation(action, policy)) {
        const located = await locate(op, policy)
        if (located === 'stale') return
        if ('kind' in located) {
          end(op, located)
          return
        }
        location = located
      }

      // 送出前最後確認（需要定位與不需要定位走同一個檢查）
      if (!isCurrent(op)) return
      if (!stillAllowed(action, latestState.current)) {
        await load() // 顯示最新狀態
        end(op, { kind: 'error', text: `今日狀態已經改變，已取消這次「${ACTION_LABEL[action]}」，請確認目前狀態後再操作。` })
        return
      }

      setPhase('submitting')
      let outcome: Notice
      try {
        await (action === 'in' ? attendanceApi.clockIn(location) : attendanceApi.clockOut(location))
        outcome = { kind: 'success', text: action === 'in' ? '上班打卡成功。' : '下班打卡成功。' }
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          if (!isCurrent(op)) return
          end(op, null)
          await refresh() // 登入已失效：回登入頁
          return
        }
        outcome = describeClockError(error, action)
      }

      await load() // 無論結果如何，都以後端的真實狀態為準（若被更新的查詢取代，以最新的為準）
      if (!isCurrent(op)) return
      onChanged?.()
      end(op, outcome)
    },
    [load, refresh, onChanged, end, locate, isCurrent],
  )

  return {
    state,
    busy,
    phase,
    notice,
    retry,
    reload: load,
    clockIn: () => perform('in'),
    clockOut: () => perform('out'),
    perform,
  }
}
