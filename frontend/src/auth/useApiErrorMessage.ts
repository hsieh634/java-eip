import { useCallback } from 'react'
import { ApiError } from '../api/http'
import { FormError } from '../lib/formError'
import { useAuth } from './useAuth'

/**
 * 把 API 錯誤轉成給使用者看的訊息，並統一處理 401：
 * - 401 → 更新登入狀態（App 會回到登入頁）
 * - 網路中斷／5xx → 操作結果未確認，提醒使用者重新整理確認，且不會導回登入頁
 * - 其他 4xx（400／403／404／409）→ 直接顯示後端的訊息（已是使用者看得懂的文字）
 */
export function useApiErrorMessage() {
  const { refresh } = useAuth()
  return useCallback(
    (error: unknown): string => {
      if (error instanceof FormError) {
        return error.message
      }
      if (error instanceof ApiError) {
        if (error.status === 401) {
          void refresh()
          return '登入已失效，請重新登入。'
        }
        if (error.status === 0 || error.status >= 500) {
          return '系統暫時無法完成操作，操作結果尚未確認，請稍後重新整理並確認目前狀態。'
        }
        return error.message
      }
      return '發生未預期的錯誤，請稍後再試。'
    },
    [refresh],
  )
}
