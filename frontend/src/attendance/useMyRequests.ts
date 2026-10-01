import { useCallback, useEffect, useRef, useState } from 'react'
import * as requestsApi from '../api/attendanceRequests'
import type { AttendanceRequest } from '../api/attendanceRequests'
import { ApiError } from '../api/http'
import { useAuth } from '../auth/useAuth'
import { useAutoRefresh } from './useAutoRefresh'

export type RequestsState =
  | { kind: 'loading' }
  | { kind: 'ready'; requests: AttendanceRequest[] }
  | { kind: 'error'; message: string }

const POLL_WHILE_PENDING_MS = 30_000

/**
 * 我的申請清單。
 * - 有待審申請時每 30 秒重新查詢，管理員核准／退回後畫面會自己更新。
 * - 原本待審的申請變成其他狀態（被核准、退回…）時呼叫 onResolved，讓外層重新查詢出勤資料。
 * - 401 → 回登入頁；其他錯誤顯示訊息與重試。
 */
export function useMyRequests(onResolved?: () => void) {
  const { refresh: refreshAuth } = useAuth()
  const [state, setState] = useState<RequestsState>({ kind: 'loading' })
  const previousPending = useRef<number[]>([])
  const resolvedCallback = useRef(onResolved)
  useEffect(() => {
    resolvedCallback.current = onResolved
  })

  const apply = useCallback((requests: AttendanceRequest[]) => {
    const pending = requests.filter((r) => r.status === 'PENDING').map((r) => r.id)
    const resolved = previousPending.current.some((id) => !pending.includes(id))
    previousPending.current = pending
    setState({ kind: 'ready', requests })
    if (resolved) resolvedCallback.current?.()
  }, [])

  const settleError = useCallback(
    (error: unknown) => {
      if (error instanceof ApiError && error.status === 401) {
        void refreshAuth()
        return
      }
      setState({ kind: 'error', message: error instanceof ApiError ? error.message : '發生未預期的錯誤' })
    },
    [refreshAuth],
  )

  const reload = useCallback(async () => {
    try {
      apply(await requestsApi.fetchMyRequests())
    } catch (error) {
      settleError(error)
    }
  }, [apply, settleError])

  useEffect(() => {
    let active = true
    requestsApi.fetchMyRequests().then(
      (requests) => active && apply(requests),
      (error: unknown) => active && settleError(error),
    )
    return () => {
      active = false
    }
  }, [apply, settleError])

  const hasPending = state.kind === 'ready' && state.requests.some((r) => r.status === 'PENDING')
  useAutoRefresh(() => void reload(), hasPending ? POLL_WHILE_PENDING_MS : null)

  return { state, reload, hasPending }
}
