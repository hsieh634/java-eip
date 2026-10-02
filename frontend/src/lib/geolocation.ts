import type { ClockLocation } from '../api/attendance'

/**
 * 只取一次位置（不持續追蹤）。只在按下「需要定位的打卡按鈕」時呼叫，不在登入或開頁時取得員工位置。
 * - enableHighAccuracy：請裝置盡量精確（可能較慢、較耗電）
 * - maximumAge 0：不使用快取的舊位置
 * - timeout 15 秒：依 W3C 規格，等待使用者授權的時間不算在內
 */
export const POSITION_OPTIONS: PositionOptions = { enableHighAccuracy: true, maximumAge: 0, timeout: 15_000 }

export type GeolocationFailureKind = 'INSECURE' | 'UNSUPPORTED' | 'DENIED' | 'UNAVAILABLE' | 'TIMEOUT'

export class GeolocationFailure extends Error {
  readonly kind: GeolocationFailureKind

  constructor(kind: GeolocationFailureKind) {
    super(GEOLOCATION_FAILURE_MESSAGE[kind])
    this.name = 'GeolocationFailure'
    this.kind = kind
  }
}

export const GEOLOCATION_FAILURE_MESSAGE: Record<GeolocationFailureKind, string> = {
  INSECURE: '目前的網址不是安全來源（需要 HTTPS 或 localhost），瀏覽器不允許取得位置，因此無法打卡。',
  UNSUPPORTED: '這個瀏覽器不支援定位，因此無法進行需要定位的打卡。',
  DENIED: '你拒絕了定位權限（或瀏覽器封鎖了定位）。請在瀏覽器的網站設定中允許位置存取後再試。',
  UNAVAILABLE: '目前無法取得位置（訊號不佳或定位服務關閉）。請確認裝置定位已開啟後再試。',
  TIMEOUT: '取得位置逾時（15 秒）。請移到訊號較好的地方後再試。',
}

/** 呼叫瀏覽器取得一次位置，並轉成打卡要送的原始資料。失敗時丟出 {@link GeolocationFailure}。 */
export function getCurrentLocation(): Promise<ClockLocation> {
  if (!window.isSecureContext) return Promise.reject(new GeolocationFailure('INSECURE'))
  if (!('geolocation' in navigator) || !navigator.geolocation) return Promise.reject(new GeolocationFailure('UNSUPPORTED'))
  return new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(
      (position) =>
        resolve({
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          accuracy: position.coords.accuracy,
          capturedAt: new Date(position.timestamp).toISOString(),
        }),
      (error) => {
        // 1 PERMISSION_DENIED（含非安全來源）、2 POSITION_UNAVAILABLE、3 TIMEOUT
        if (error.code === 1) reject(new GeolocationFailure('DENIED'))
        else if (error.code === 3) reject(new GeolocationFailure('TIMEOUT'))
        else reject(new GeolocationFailure('UNAVAILABLE'))
      },
      POSITION_OPTIONS,
    )
  })
}
