import type { GeofenceLimits, GeofenceScope, GeofenceValues } from '../api/adminGeofence'

export const SCOPE_LABEL: Record<GeofenceScope, string> = {
  CLOCK_IN: '只限制上班',
  CLOCK_OUT: '只限制下班',
  BOTH: '上下班都限制',
}

/** 表單上的原始輸入（字串），送出前才轉成數值。 */
export interface GeofenceForm {
  enabled: boolean
  latitude: string
  longitude: string
  radius: string
  scope: GeofenceScope
}

export const toForm = (v: GeofenceValues): GeofenceForm => ({
  enabled: v.enabled,
  latitude: v.latitude === null ? '' : String(v.latitude),
  longitude: v.longitude === null ? '' : String(v.longitude),
  radius: String(v.radiusMeters),
  scope: v.scope,
})

// 只接受一般十進位寫法（例如 25.033964、-0.5、0）；不接受 1e3、0x10、Infinity 這類 Number() 也會吃的寫法
const DECIMAL = /^-?\d+(\.\d+)?$/
const INTEGER = /^\d+$/

/**
 * 前端驗證（後端仍會完整驗證）。成功回傳數值；失敗回傳給人看的訊息。
 * 重點：座標 0 是合法值，所以用「是否為空字串」判斷有沒有填，不用真假值。
 */
export function parseForm(form: GeofenceForm, limits: GeofenceLimits): { ok: true; values: GeofenceValues } | { ok: false; message: string } {
  const lat = form.latitude.trim()
  const lon = form.longitude.trim()
  if ((lat === '') !== (lon === '')) return { ok: false, message: '緯度與經度必須同時填寫或同時留空。' }
  let latitude: number | null = null
  let longitude: number | null = null
  if (lat !== '') {
    if (!DECIMAL.test(lat) || !DECIMAL.test(lon)) return { ok: false, message: '緯度與經度請輸入十進位數字（例如 25.033964）。' }
    latitude = Number(lat)
    longitude = Number(lon)
    if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90) return { ok: false, message: '緯度必須在 -90 到 90 之間。' }
    if (!Number.isFinite(longitude) || longitude < -180 || longitude > 180) return { ok: false, message: '經度必須在 -180 到 180 之間。' }
  }
  const radiusText = form.radius.trim()
  const radius = Number(radiusText)
  if (!INTEGER.test(radiusText) || radius < limits.radiusMin || radius > limits.radiusMax) {
    return { ok: false, message: `半徑請輸入 ${limits.radiusMin} 到 ${limits.radiusMax} 之間的整數（公尺）。` }
  }
  if (form.enabled && latitude === null) return { ok: false, message: '啟用定位限制前，請先填寫公司中心的緯度與經度。' }
  return { ok: true, values: { enabled: form.enabled, latitude, longitude, radiusMeters: radius, scope: form.scope } }
}

export const sameValues = (a: GeofenceValues, b: GeofenceValues) =>
  a.enabled === b.enabled && a.latitude === b.latitude && a.longitude === b.longitude && a.radiusMeters === b.radiusMeters && a.scope === b.scope

export const describeCenter = (v: GeofenceValues) => (v.latitude === null || v.longitude === null ? '未設定' : `${v.latitude}, ${v.longitude}`)
