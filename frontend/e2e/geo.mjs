// E2E 共用：可控制的假 navigator.geolocation（geofence.mjs、geofence-races.mjs 使用）。
// 只是「瀏覽器模擬」，證明程式如何處理各種回應；不能證明真實裝置的定位品質。
//
// window.__geo 控制：
//   mode：success／denied／timeout／unavailable／unsupported／manual
//   manual：不自動回覆，呼叫保留在 pending，由測試以 resolveGeo()／rejectGeo() 決定何時、如何回覆（確定性的競態測試）
//   delayMs：success 等模式的延遲；calls／watchCalls／options：呼叫紀錄；insecure：模擬非安全來源

/** 在頁面載入前注入假的定位（之後每次換頁都會重新注入）。 */
export async function installFakeGeolocation(page) {
  await page.evaluateOnNewDocument(() => {
    const ctl = { mode: 'success', latitude: 0, longitude: 0, accuracy: 10, delayMs: 0, calls: 0, watchCalls: 0, options: null, insecure: false, pending: [] }
    globalThis.__geo = ctl
    const fake = {
      getCurrentPosition(success, error, options) {
        ctl.calls++
        ctl.options = options
        if (ctl.mode === 'manual') {
          ctl.pending.push({ success, error })
          return
        }
        const mode = ctl.mode
        const position = { coords: { latitude: ctl.latitude, longitude: ctl.longitude, accuracy: ctl.accuracy }, timestamp: Date.now() }
        setTimeout(() => {
          if (mode === 'success') success(position)
          else error({ code: { denied: 1, unavailable: 2, timeout: 3 }[mode], message: mode })
        }, ctl.delayMs)
      },
      watchPosition() {
        ctl.watchCalls++
        return 0
      },
      clearWatch() {},
    }
    // 手動模式：由測試決定第一個等待中的呼叫成功或失敗
    ctl.resolveFirst = () => {
      const p = ctl.pending.shift()
      p?.success({ coords: { latitude: ctl.latitude, longitude: ctl.longitude, accuracy: ctl.accuracy }, timestamp: Date.now() })
      return Boolean(p)
    }
    ctl.rejectFirst = (code) => {
      const p = ctl.pending.shift()
      p?.error({ code, message: 'manual' })
      return Boolean(p)
    }
    Object.defineProperty(Navigator.prototype, 'geolocation', { configurable: true, get: () => (ctl.mode === 'unsupported' ? undefined : fake) })
    Object.defineProperty(window, 'isSecureContext', { configurable: true, get: () => !ctl.insecure })
  })
}

export const geo = (page, patch) => page.evaluate((p) => Object.assign(globalThis.__geo, p), patch)
export const geoState = (page) => page.evaluate(() => ({ ...globalThis.__geo, pending: globalThis.__geo.pending.length }))
export const resolveGeo = (page) => page.evaluate(() => globalThis.__geo.resolveFirst())
export const rejectGeo = (page, code = 1) => page.evaluate((c) => globalThis.__geo.rejectFirst(c), code)

/** 測試用公司中心（虛構座標）與「中心正北方 meters 公尺」的位置。 */
export const CENTER = { latitude: 25.0, longitude: 121.5 }
const METERS_PER_DEGREE = (6_371_008.8 * Math.PI) / 180
export const northOf = (meters) => ({ latitude: CENTER.latitude + meters / METERS_PER_DEGREE, longitude: CENTER.longitude })
