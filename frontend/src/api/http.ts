/**
 * 後端錯誤回應 { code, message } 對應的例外。status 為 0 代表根本連不上伺服器。
 * details：錯誤本文的其他欄位（例如 OUTSIDE_GEOFENCE 的 distanceMeters／radiusMeters）。
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details: Readonly<Record<string, unknown>>

  constructor(status: number, code: string, message: string, details: Record<string, unknown> = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
  }
}

const SAFE_METHODS = ['GET', 'HEAD', 'OPTIONS']

function readCookie(name: string): string | null {
  const prefix = `${name}=`
  const entry = document.cookie.split('; ').find((c) => c.startsWith(prefix))
  return entry ? decodeURIComponent(entry.slice(prefix.length)) : null
}

interface RequestOptions {
  method?: string
  body?: unknown
}

/**
 * 所有後端呼叫的唯一入口。
 * - 同源請求，瀏覽器自動帶 HttpOnly 的 JSESSIONID（JavaScript 讀不到，也不需要讀）。
 * - 寫入請求自動附上 X-XSRF-TOKEN。**每次即時從 Cookie 讀**，不快取：
 *   登入／登出後後端會換發新 token，快取舊值會讓下一個寫入被 403 拒絕。
 */
export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = { Accept: 'application/json' }

  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (!SAFE_METHODS.includes(method)) {
    const token = readCookie('XSRF-TOKEN')
    if (token) {
      headers['X-XSRF-TOKEN'] = token
    }
  }

  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers,
      credentials: 'same-origin',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', '無法連線到伺服器，請確認後端已啟動')
  }

  if (!response.ok) {
    let code = 'UNKNOWN'
    let details: Record<string, unknown> = {}
    // 502/503/504 通常是代理層（開發時的 Vite）連不到後端，此時回應不是後端的 JSON
    let message =
      response.status >= 502 && response.status <= 504
        ? '伺服器暫時無法使用，請確認後端已啟動'
        : `請求失敗（HTTP ${response.status}）`
    try {
      const data = (await response.json()) as { code?: string; message?: string } & Record<string, unknown>
      code = data.code ?? code
      message = data.message ?? message
      details = data
    } catch {
      // 回應不是 JSON（例如代理層錯誤）：維持預設訊息
    }
    throw new ApiError(response.status, code, message, details)
  }

  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}
