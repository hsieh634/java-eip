import { ApiError } from '../api/http'

/** 把登出失敗的原因轉成使用者看得懂的提示。重點是告訴使用者「目前仍是登入狀態」。 */
export function describeLogoutFailure(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 0) {
      return '登出失敗：無法連線到伺服器。你仍在登入狀態，請檢查網路後再試一次。'
    }
    if (error.status === 403) {
      return '登出失敗：安全驗證未通過（頁面可能已過期）。你仍在登入狀態，請再試一次；若持續發生請重新整理頁面。'
    }
    if (error.status >= 500) {
      return '登出失敗：伺服器暫時無法處理。你仍在登入狀態，請稍後再試一次。'
    }
    return `登出失敗：${error.message}。你仍在登入狀態，請再試一次。`
  }
  return '登出失敗。你仍在登入狀態，請再試一次。'
}
