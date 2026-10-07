// T03.2 第 1 輪複審 R1／R2 的競態回歸測試，在【隔離堆疊】執行（測試資料庫＋本次專用帳號，不碰開發資料）。
//
// 用「受控回應」安排非同步事件的先後，而不是靠 sleep 碰運氣：
//   - 請求攔截：location-policy／me/today 等請求先被扣住，由測試決定何時放行（continue）、偽造回應（respond）或中斷（abort）
//   - 手動定位：假 navigator.geolocation 的 manual 模式，由測試決定定位何時成功或失敗
// 只有「某件事不應該發生」的斷言，需要在事件已確定送達後再等一小段固定時間（settle），讓錯誤的程式有機會犯錯。
//
// R1：等待定位設定（或定位）期間切頁，舊操作不得再定位或送出打卡；舊操作不影響新頁面的新操作。
// R2：今日狀態的舊回應（成功或失敗）不得覆蓋較新的回應；過期的 401 不觸發登入狀態刷新。
// 執行：cd frontend; npm run e2e:races
import { createRunner, launchBrowser, loginAs } from './helpers.mjs'
import { geo, geoState, installFakeGeolocation, northOf, rejectGeo, resolveGeo } from './geo.mjs'
import { startIsolatedStack } from './isolated-stack.mjs'
import { click, isDisabled, newPage, todayStatus, waitStatus, waitText } from './ui.mjs'

const { check, finish } = createRunner()
let stack
let browser
const DESKTOP = { width: 1200, height: 900 }

const POLICY_NOT_REQUIRED = { enabled: false, clockInRequired: false, clockOutRequired: false, radiusMeters: 100, maxAccuracyMeters: 50, version: 0 }
const POLICY_REQUIRED = { enabled: true, clockInRequired: true, clockOutRequired: true, radiusMeters: 100, maxAccuracyMeters: 50, version: 1 }
const json = (status, body) => ({ status, contentType: 'application/json', body: typeof body === 'string' ? body : JSON.stringify(body) })

/** 讓已經送達的回應有時間被（錯誤的）程式處理；只用在「不應該發生」的斷言之前。 */
const settle = (page, ms = 600) => page.evaluate((t) => new Promise((r) => setTimeout(r, t)), ms)

/**
 * 攔截並計數關鍵請求；hold 內的種類會被扣住，等測試決定怎麼回應。
 * 種類：policy（GET location-policy）、today（GET me/today）、in／out（POST 打卡）、me（GET auth/me）
 */
async function controlRequests(page) {
  const ctl = { hold: new Set(), held: [], counts: { policy: 0, today: 0, in: 0, out: 0, me: 0 } }
  await page.setRequestInterception(true)
  page.on('request', (r) => {
    if (r.isInterceptResolutionHandled()) return
    const p = new URL(r.url()).pathname
    const m = r.method()
    let key = null
    if (m === 'GET' && p === '/api/attendance/location-policy') key = 'policy'
    else if (m === 'GET' && p === '/api/attendance/me/today') key = 'today'
    else if (m === 'POST' && p === '/api/attendance/clock-in') key = 'in'
    else if (m === 'POST' && p === '/api/attendance/clock-out') key = 'out'
    else if (m === 'GET' && p === '/api/auth/me') key = 'me'
    if (key) ctl.counts[key]++
    if (key && ctl.hold.has(key)) {
      ctl.held.push({ key, request: r })
      return
    }
    void r.continue()
  })
  /** 等到某種類被扣住的請求累積到 n 個（條件式等待，不是固定 sleep）。 */
  ctl.waitHeld = async (key, n) => {
    const deadline = Date.now() + 15_000
    while (ctl.held.filter((h) => h.key === key).length < n) {
      if (Date.now() > deadline) throw new Error(`等不到第 ${n} 個 ${key} 請求`)
      await new Promise((r) => setTimeout(r, 50))
    }
  }
  /** 取出某種類被扣住的第 index 個（依送出順序）。 */
  ctl.take = (key, index = 0) => {
    const matches = ctl.held.filter((h) => h.key === key)
    const item = matches[index]
    if (!item) throw new Error(`沒有第 ${index} 個被扣住的 ${key}`)
    ctl.held.splice(ctl.held.indexOf(item), 1)
    return item.request
  }
  ctl.heldCount = (key) => ctl.held.filter((h) => h.key === key).length
  /** 回應送達瀏覽器（respond／continue 之後）。 */
  ctl.delivered = (request) => page.waitForResponse((res) => res.request() === request, { timeout: 15_000 })
  return ctl
}

async function employee(context, user) {
  const ui = await newPage(context, { viewport: DESKTOP })
  await installFakeGeolocation(ui.page)
  const ctl = await controlRequests(ui.page)
  await loginAs(ui.page, user.username, user.password, stack.baseUrl)
  await ui.page.waitForSelector('[data-testid=today-status]')
  return { ...ui, ctl }
}

/** 由頁面直接讀一次今日狀態（不經畫面），用來取得「舊的」回應本文。 */
const fetchTodayText = (page) => page.evaluate(async () => (await fetch('/api/attendance/me/today')).text())

/** 同一個登入在另一個分頁帶合法位置上班（讓伺服器真實狀態變成 WORKING）。 */
async function clockInElsewhere(context) {
  const other = await newPage(context, { viewport: DESKTOP })
  await other.page.goto(stack.baseUrl, { waitUntil: 'networkidle0' })
  await other.page.waitForSelector('.topbar')
  const status = await other.page.evaluate(async (loc) => {
    const token = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '')
    const res = await fetch('/api/attendance/clock-in', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
      body: JSON.stringify({ requestId: crypto.randomUUID(), ...loc, accuracy: 8, capturedAt: new Date().toISOString() }),
    })
    return res.status
  }, northOf(20))
  await other.page.close()
  return status
}

/** 等待某個條件；逾時回 false，不中斷腳本（讓每個情境都能各自顯示成功或失敗）。 */
const soft = (promise) => promise.then(() => true, () => false)
/** 從 base 之後新增的請求數。 */
const delta = (ctl, base) => Object.fromEntries(Object.entries(ctl.counts).map(([k, v]) => [k, v - base[k]]))
const posts = (d) => d.in + d.out

const hasNotice = (page) => page.$('[data-testid=clock-notice]').then((h) => h !== null)
const statusErrorShown = (page) => page.evaluate(() => document.body.innerText.includes('無法取得今日打卡狀態'))

try {
  stack = await startIsolatedStack()
  console.log(`隔離堆疊已啟動：${stack.baseUrl}（測試資料庫；本次專用帳號 ${stack.users.a.username} 等）`)
  browser = await launchBrowser()
  const { a: userA, b: userB, c: userC, admin: userAdmin } = stack.users

  // 啟用定位限制（測試座標，上下班都限制）
  {
    const ctx = await browser.createBrowserContext()
    const ui = await newPage(ctx, { viewport: DESKTOP })
    await loginAs(ui.page, userAdmin.username, userAdmin.password, stack.baseUrl)
    const status = await ui.page.evaluate(async (center) => {
      const token = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '')
      const current = await (await fetch('/api/admin/geofence')).json()
      const res = await fetch('/api/admin/geofence', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
        body: JSON.stringify({ enabled: true, latitude: center.latitude, longitude: center.longitude, radiusMeters: 100, scope: 'BOTH', expectedVersion: current.version, reason: '競態測試啟用' }),
      })
      return res.status
    }, { latitude: 25.0, longitude: 121.5 })
    check('準備：啟用定位限制（隔離測試庫）', status === 200, String(status))
    await ctx.close()
  }

  // ================= R1：設定或定位尚未回來時切頁 =================
  const ctxC = await browser.createBrowserContext()
  const C = await employee(ctxC, userC)
  {
    const { page, ctl } = C
    await waitStatus(page, '尚未上班')

    // R1-1：等待定位設定時切頁，之後回「不需定位」
    let base = { ...ctl.counts }
    let calls0 = (await geoState(page)).calls
    ctl.hold.add('policy')
    await click(page, '上班打卡')
    await ctl.waitHeld('policy', 1)
    await page.click('a[href="#/attendance"]') // 首頁的打卡卡片卸載
    await page.waitForSelector('#range-from')
    const r1 = ctl.take('policy')
    await r1.respond(json(200, POLICY_NOT_REQUIRED))
    await ctl.delivered(r1)
    await settle(page)
    let d = delta(ctl, base)
    check('R1-1 設定未回來就切頁，之後回「不需定位」→ 0 次定位、0 次上班／下班 POST',
      (await geoState(page)).calls === calls0 && posts(d) === 0, JSON.stringify({ geoCalls: (await geoState(page)).calls - calls0, ...d }))
    await soft(waitStatus(page, '尚未上班'))
    check('R1-1 新頁面的卡片不受影響：尚未上班、上班按鈕可按、沒有舊操作的提示',
      !(await isDisabled(page, '上班打卡')) && !(await hasNotice(page)))

    // R1-2：同樣情境，之後回「需要定位」
    base = { ...ctl.counts }
    calls0 = (await geoState(page)).calls
    await click(page, '上班打卡')
    await ctl.waitHeld('policy', 1)
    await page.click('a[href="#/"]')
    await page.waitForSelector('main h1')
    const r2 = ctl.take('policy')
    await r2.respond(json(200, POLICY_REQUIRED))
    await ctl.delivered(r2)
    await settle(page)
    d = delta(ctl, base)
    check('R1-2 設定未回來就切頁，之後回「需要定位」→ 0 次定位、0 次 POST',
      (await geoState(page)).calls === calls0 && posts(d) === 0, JSON.stringify({ geoCalls: (await geoState(page)).calls - calls0, ...d }))
    ctl.hold.delete('policy')

    // R1-3：定位已開始後切頁，晚到的成功／失敗都不送出、也不在新頁面顯示舊提示
    await soft(waitStatus(page, '尚未上班'))
    await geo(page, { mode: 'manual', ...northOf(10), accuracy: 8 })
    base = { ...ctl.counts }
    await click(page, '上班打卡')
    await page.waitForFunction(() => globalThis.__geo.pending.length === 1)
    await page.click('a[href="#/attendance"]')
    await page.waitForSelector('#range-from')
    await resolveGeo(page)
    await settle(page)
    check('R1-3 定位中切頁，晚到的「成功」→ 不送出打卡', posts(delta(ctl, base)) === 0, JSON.stringify(delta(ctl, base)))
    await soft(waitStatus(page, '尚未上班'))
    base = { ...ctl.counts }
    await click(page, '上班打卡')
    await page.waitForFunction(() => globalThis.__geo.pending.length === 1)
    await page.click('a[href="#/"]')
    await page.waitForSelector('main h1')
    await rejectGeo(page, 3)
    await settle(page)
    check('R1-3 定位中切頁，晚到的「失敗」→ 不送出、不在新頁面顯示舊操作的錯誤', posts(delta(ctl, base)) === 0 && !(await hasNotice(page)))

    // R1-4：舊操作晚到，不影響重新進入頁面後的新操作（新操作正在定位時，舊操作的設定才回來）
    await soft(waitStatus(page, '尚未上班'))
    base = { ...ctl.counts }
    ctl.hold.add('policy')
    await click(page, '上班打卡') // 舊操作：設定被扣住
    await ctl.waitHeld('policy', 1)
    await page.click('a[href="#/attendance"]')
    await page.waitForSelector('#range-from')
    await soft(waitStatus(page, '尚未上班'))
    await click(page, '上班打卡') // 新操作
    await ctl.waitHeld('policy', 2)
    const newPolicy = ctl.take('policy', 1)
    await newPolicy.continue() // 真實設定：需要定位 → 進入手動定位
    await page.waitForFunction(() => globalThis.__geo.pending.length === 1)
    const oldPolicy = ctl.take('policy', 0)
    await oldPolicy.respond(json(200, POLICY_NOT_REQUIRED))
    await ctl.delivered(oldPolicy)
    await settle(page)
    check('R1-4 舊操作的設定晚到 → 舊操作不送出；新操作仍在定位中（按鈕停用、顯示階段）',
      posts(delta(ctl, base)) === 0 && (await page.$('[data-testid=clock-phase][data-phase=locating]')) !== null, JSON.stringify(delta(ctl, base)))
    ctl.hold.delete('policy')
    await resolveGeo(page)
    await soft(waitStatus(page, '工作中'))
    await soft(page.waitForFunction(() => !document.querySelector('[data-testid=clock-phase]')))
    check('R1-4 新操作正常完成：恰好 1 次上班 POST，工作中、loading 結束',
      delta(ctl, base).in === 1 && (await todayStatus(page)) === '工作中' && !(await isDisabled(page, '下班打卡')), JSON.stringify(delta(ctl, base)))
    await geo(page, { mode: 'success' })
  }

  // ================= R2：今日狀態的舊回應不得覆蓋新回應 =================
  const ctxA = await browser.createBrowserContext()
  const A = await employee(ctxA, userA)
  {
    const { page, ctl } = A
    await waitStatus(page, '尚未上班')
    const staleNotStarted = await fetchTodayText(page)

    // 本頁正在等上班的定位
    await geo(page, { mode: 'manual', ...northOf(10), accuracy: 8 })
    await click(page, '上班打卡')
    await page.waitForFunction(() => globalThis.__geo.pending.length === 1)

    // 先後發出兩次今日查詢（舊 A、新 B），兩者都被扣住
    ctl.hold.add('today')
    await click(page, '重新整理')
    await ctl.waitHeld('today', 1)
    await click(page, '重新整理')
    await ctl.waitHeld('today', 2)
    // 同一位員工在別處上班 → 伺服器狀態變成 WORKING
    check('R2 準備：同一位員工在另一個工作階段上班成功', (await clockInElsewhere(ctxA)) === 200)
    const newer = ctl.take('today', 1)
    await newer.continue()
    await waitStatus(page, '工作中')
    const older = ctl.take('today', 0)
    await older.respond(json(200, staleNotStarted))
    await ctl.delivered(older)
    await settle(page)
    check('R2-1 新查詢先回 WORKING、舊查詢後回 NOT_STARTED → 畫面維持「工作中」', (await todayStatus(page)) === '工作中')
    ctl.hold.delete('today')

    const base = { ...ctl.counts }
    await resolveGeo(page)
    const cancelled = await soft(page.waitForFunction(() => document.querySelector('[data-testid=clock-notice]')?.textContent.includes('今日狀態已經改變')))
    await settle(page)
    check('R2-1 等待中的定位成功回來 → 取消原本的「上班打卡」：上班與下班 POST 都是 0 次',
      cancelled && posts(delta(ctl, base)) === 0, JSON.stringify(delta(ctl, base)))
    await soft(page.waitForFunction(() => !document.querySelector('[data-testid=clock-phase]')))
    await geo(page, { mode: 'success' })
  }

  const ctxB = await browser.createBrowserContext()
  const B = await employee(ctxB, userB)
  {
    const { page, ctl } = B
    await waitStatus(page, '尚未上班')

    // R2-2：舊查詢晚到的錯誤不能覆蓋新的成功
    ctl.hold.add('today')
    await click(page, '重新整理')
    await ctl.waitHeld('today', 1)
    await click(page, '重新整理')
    await ctl.waitHeld('today', 2)
    const ok = ctl.take('today', 1)
    await ok.continue()
    await ctl.delivered(ok)
    const lateError = ctl.take('today', 0)
    await lateError.respond(json(500, { code: 'INTERNAL_ERROR', message: '系統發生錯誤' }))
    await ctl.delivered(lateError)
    await settle(page)
    check('R2-2 舊查詢晚到的 500 不覆蓋新的成功：仍顯示今日狀態、沒有錯誤訊息', !(await statusErrorShown(page)) && (await todayStatus(page)) === '尚未上班')

    // R2-3：新查詢失敗、舊查詢晚到成功 → 不把錯誤偽裝成已取得最新資料；可重試恢復
    await click(page, '重新整理')
    await ctl.waitHeld('today', 1)
    await click(page, '重新整理')
    await ctl.waitHeld('today', 2)
    const failing = ctl.take('today', 1)
    await failing.respond(json(503, { code: 'INTERNAL_ERROR', message: '系統暫時無法使用' }))
    await soft(waitText(page, '無法取得今日打卡狀態'))
    const lateOk = ctl.take('today', 0)
    await lateOk.continue()
    await ctl.delivered(lateOk)
    await settle(page)
    check('R2-3 新查詢失敗後，舊查詢的成功不會蓋掉錯誤（仍顯示錯誤與重試）', await statusErrorShown(page))
    ctl.hold.delete('today')
    if (await statusErrorShown(page)) await click(page, '重試')
    else await click(page, '重新整理')
    check('R2-3 按「重試」→ 恢復顯示最新狀態', (await soft(waitStatus(page, '尚未上班'))) && !(await statusErrorShown(page)))

    // R2-4：過期的 401 不觸發登入狀態刷新；最新請求的 401 依既有流程處理（重新確認登入狀態）
    ctl.hold.add('today')
    await click(page, '重新整理')
    await ctl.waitHeld('today', 1)
    await click(page, '重新整理')
    await ctl.waitHeld('today', 2)
    const fresh = ctl.take('today', 1)
    await fresh.continue()
    await ctl.delivered(fresh)
    const meBefore = ctl.counts.me
    const stale401 = ctl.take('today', 0)
    await stale401.respond(json(401, { code: 'UNAUTHENTICATED', message: '請先登入' }))
    await ctl.delivered(stale401)
    await settle(page)
    check('R2-4 過期請求的 401 → 不刷新登入狀態（沒有呼叫 /api/auth/me）、畫面不變', ctl.counts.me === meBefore && (await todayStatus(page)) === '尚未上班')
    await click(page, '重新整理')
    await ctl.waitHeld('today', 1)
    const latest401 = ctl.take('today', 0)
    await latest401.respond(json(401, { code: 'UNAUTHENTICATED', message: '請先登入' }))
    await ctl.delivered(latest401)
    await page.waitForFunction(() => true)
    const deadline = Date.now() + 10_000
    while (ctl.counts.me === meBefore && Date.now() < deadline) await new Promise((r) => setTimeout(r, 50))
    check('R2-4 最新請求的 401 → 依既有流程重新確認登入狀態（呼叫 /api/auth/me）', ctl.counts.me > meBefore)

    // R2-5：最新請求網路中斷 → 顯示錯誤（不是登出），可重試
    await click(page, '重新整理')
    await ctl.waitHeld('today', 1)
    const aborted = ctl.take('today', 0)
    await aborted.abort('failed')
    await waitText(page, '無法取得今日打卡狀態')
    check('R2-5 最新請求網路中斷 → 顯示錯誤與重試，沒有被帶回登入頁', (await page.$('.login-card')) === null)
    ctl.hold.delete('today')
    await click(page, '重試')
    await waitStatus(page, '尚未上班')
  }

  // R2-6：首次載入與重新查詢重疊（StrictMode 開發模式下首次載入也可能發出兩次）
  {
    const ctx = await browser.createBrowserContext()
    const ui = await newPage(ctx, { viewport: DESKTOP })
    await installFakeGeolocation(ui.page)
    const ctl = await controlRequests(ui.page)
    ctl.hold.add('today')
    await loginAs(ui.page, userB.username, userB.password, stack.baseUrl)
    await ctl.waitHeld('today', 1)
    const initial = ctl.heldCount('today')
    await click(ui.page, '重新整理')
    await ctl.waitHeld('today', initial + 1)
    const refresh = ctl.take('today', initial)
    await refresh.continue()
    await soft(waitStatus(ui.page, '尚未上班'))
    while (ctl.heldCount('today') > 0) {
      const stale = ctl.take('today', 0)
      await stale.respond(json(500, { code: 'INTERNAL_ERROR', message: '系統發生錯誤' }))
      await ctl.delivered(stale)
    }
    ctl.hold.delete('today')
    await settle(ui.page)
    check(`R2-6 首次載入（${initial} 個）比重新查詢晚回、且失敗 → 不覆蓋重新查詢的結果`, !(await statusErrorShown(ui.page)) && (await todayStatus(ui.page)) === '尚未上班')
    await ctx.close()
  }

  // R2-7：打卡後的確認查詢被其他刷新取代 → 以最新有效查詢為準，不自動重送打卡
  {
    const ctx = await browser.createBrowserContext()
    const ui = await newPage(ctx, { viewport: DESKTOP })
    await installFakeGeolocation(ui.page)
    const ctl = await controlRequests(ui.page)
    await loginAs(ui.page, userAdmin.username, userAdmin.password, stack.baseUrl)
    await waitStatus(ui.page, '尚未上班')
    const staleNotStarted = await fetchTodayText(ui.page)
    await geo(ui.page, { mode: 'success', ...northOf(15), accuracy: 8 })
    ctl.hold.add('today')
    await click(ui.page, '上班打卡')
    await ctl.waitHeld('today', 1) // 打卡成功後的確認查詢（舊）
    await click(ui.page, '重新整理')
    await ctl.waitHeld('today', 2) // 使用者自己按的刷新（新）
    const newer = ctl.take('today', 1)
    await newer.continue()
    await soft(waitStatus(ui.page, '工作中'))
    const confirm = ctl.take('today', 0)
    await confirm.respond(json(200, staleNotStarted))
    await ctl.delivered(confirm)
    ctl.hold.delete('today')
    await soft(ui.page.waitForFunction(() => !document.querySelector('[data-testid=clock-phase]')))
    await settle(ui.page)
    check('R2-7 打卡後的確認查詢被較新的刷新取代 → 畫面維持「工作中」、上班 POST 恰好 1 次（不自動重送）',
      (await todayStatus(ui.page)) === '工作中' && ctl.counts.in === 1, JSON.stringify(ctl.counts))
    await ctx.close()
  }

  for (const [name, ui] of [['R1', C], ['R2', A], ['R2 錯誤', B]]) {
    const rejections = await ui.tracker.unhandledRejections()
    check(`${name}：沒有未處理的 Promise 拒絕與 JS 例外`, rejections.length === 0 && ui.tracker.pageErrors.length === 0, [...rejections, ...ui.tracker.pageErrors].join(' | '))
  }
} catch (error) {
  check('腳本執行未中斷', false, String(error?.stack ?? error))
} finally {
  await browser?.close()
  stack?.stop()
}

finish()
