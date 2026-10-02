// T03.2：公司範圍定位打卡與管理員設定的瀏覽器端到端驗證，在【隔離堆疊】執行（見 isolated-stack.mjs）：
// 另一個後端（8081，測試資料庫）＋另一個前端（5174）＋本次專用帳號；隔離後端啟動時把測試庫的定位設定恢復成預設（關閉）。
// 不會碰你的開發資料庫、開發帳號或出勤。
//
// 定位以兩種方式模擬（都只是「瀏覽器模擬」，不等於手機實機 GPS 驗證）：
//   1. 注入可控制的假 navigator.geolocation：控制成功／拒絕／逾時／無法取得／不支援／延遲，並記錄呼叫次數與參數
//   2. Chrome DevTools 的定位模擬（overridePermissions＋setGeolocation）：走瀏覽器真正的 Geolocation API
// 公司中心使用測試座標 (25.0, 121.5)，不是任何真實公司的位置。
// 執行：cd frontend; npm run e2e:geofence
import path from 'node:path'
import { OUT, createRunner, launchBrowser, loginAs } from './helpers.mjs'
import { startIsolatedStack } from './isolated-stack.mjs'
import { bodyText, button, click, hasButton, isDisabled, newPage, noHorizontalOverflow, setValue, simulateReturnToWindow, todayStatus, waitStatus, waitText } from './ui.mjs'

const { check, finish } = createRunner()
let stack
let browser

const CENTER = { latitude: 25.0, longitude: 121.5 }
const METERS_PER_DEGREE = (6_371_008.8 * Math.PI) / 180
const northOf = (meters) => ({ latitude: CENTER.latitude + meters / METERS_PER_DEGREE, longitude: CENTER.longitude })
const DESKTOP = { width: 1200, height: 900 }
const MOBILE = { width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true }

async function assertNoUnhandled(name, trackers) {
  const rejections = (await Promise.all(trackers.map((t) => t.unhandledRejections()))).flat()
  const errors = trackers.flatMap((t) => t.pageErrors)
  check(`${name}：沒有未處理的 Promise 拒絕與 JS 例外`, rejections.length === 0 && errors.length === 0, [...rejections, ...errors].join(' | '))
}

/** 在頁面載入前注入假的 navigator.geolocation，由 window.__geo 控制。 */
async function installFakeGeolocation(page) {
  await page.evaluateOnNewDocument(() => {
    const ctl = { mode: 'success', latitude: 0, longitude: 0, accuracy: 10, delayMs: 0, calls: 0, watchCalls: 0, options: null, insecure: false }
    globalThis.__geo = ctl
    const fake = {
      getCurrentPosition(success, error, options) {
        ctl.calls++
        ctl.options = options
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
    Object.defineProperty(Navigator.prototype, 'geolocation', { configurable: true, get: () => (ctl.mode === 'unsupported' ? undefined : fake) })
    Object.defineProperty(window, 'isSecureContext', { configurable: true, get: () => !ctl.insecure })
  })
}

const geo = (page, patch) => page.evaluate((p) => Object.assign(globalThis.__geo, p), patch)
const geoState = (page) => page.evaluate(() => ({ ...globalThis.__geo }))

/** 記錄打卡 POST 的次數。 */
function countClockRequests(page) {
  const counts = { in: 0, out: 0, policy: 0 }
  page.on('request', (r) => {
    const p = new URL(r.url()).pathname
    if (r.method() === 'POST' && p === '/api/attendance/clock-in') counts.in++
    if (r.method() === 'POST' && p === '/api/attendance/clock-out') counts.out++
    if (p === '/api/attendance/location-policy') counts.policy++
  })
  return counts
}

const clockNotice = (page) => page.$eval('[data-testid=clock-notice]', (el) => el.textContent).catch(() => '')
const waitClockNotice = (page, text) =>
  page.waitForFunction((t) => document.querySelector('[data-testid=clock-notice]')?.textContent.includes(t), {}, text)
const noPhase = (page) => page.waitForFunction(() => !document.querySelector('[data-testid=clock-phase]'))

async function employeePage(context, user, viewport = DESKTOP) {
  const ui = await newPage(context, { viewport })
  await installFakeGeolocation(ui.page)
  const counts = countClockRequests(ui.page)
  await loginAs(ui.page, user.username, user.password, stack.baseUrl)
  await ui.page.waitForSelector('[data-testid=today-status]')
  return { ...ui, counts }
}

async function adminSettingsPage(context, viewport = DESKTOP) {
  const ui = await newPage(context, { viewport })
  await loginAs(ui.page, stack.users.admin.username, stack.users.admin.password, stack.baseUrl)
  await ui.page.click('a[href="#/admin/geofence"]')
  await ui.page.waitForSelector('[data-testid=geofence-form]')
  return ui
}

/** 以管理員身分直接呼叫設定 API（用來在員工操作「途中」改設定）。 */
async function putSettingsViaApi(page, patch, reason) {
  return page.evaluate(
    async (p, r) => {
      const token = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '')
      const current = await (await fetch('/api/admin/geofence')).json()
      const body = { enabled: current.enabled, latitude: current.latitude, longitude: current.longitude, radiusMeters: current.radiusMeters, scope: current.scope, ...p, expectedVersion: current.version, reason: r }
      const res = await fetch('/api/admin/geofence', { method: 'PUT', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: JSON.stringify(body) })
      return res.status
    },
    patch,
    reason,
  )
}

try {
  stack = await startIsolatedStack()
  console.log(`隔離堆疊已啟動：${stack.baseUrl}（測試資料庫；本次專用帳號 ${stack.users.a.username} 等）`)
  browser = await launchBrowser()
  const { a: userA, b: userB, c: userC, admin: userAdmin } = stack.users

  // ================= G0：定位關閉時，不呼叫定位 API，沿用原本打卡 =================
  const ctxA = await browser.createBrowserContext()
  const A = await employeePage(ctxA, userA)
  {
    await waitStatus(A.page, '尚未上班')
    check('G0 定位關閉：卡片說明「打卡不需要定位」', (await A.page.$eval('[data-testid=location-policy]', (el) => el.dataset.required)) === 'none')
    check('G0 登入與進入首頁時沒有取得位置', (await geoState(A.page)).calls === 0)
    await click(A.page, '上班打卡')
    await waitStatus(A.page, '工作中')
    const s = await geoState(A.page)
    check('G0 定位關閉時上班打卡成功，且完全沒有呼叫 getCurrentPosition／watchPosition', s.calls === 0 && s.watchCalls === 0 && A.counts.in === 1, JSON.stringify({ calls: s.calls, posts: A.counts.in }))
  }

  // ================= G1／G2：管理員設定（驗證、確認畫面、歷程）與過期提交 =================
  const ctxAdmin = await browser.createBrowserContext()
  const ctxAdmin2 = await browser.createBrowserContext()
  const admin = await adminSettingsPage(ctxAdmin)
  const admin2 = await adminSettingsPage(ctxAdmin2) // 另一位管理員在修改前就打開了頁面
  {
    const page = admin.page
    check('G1 ADMIN 看得到「定位打卡設定」入口', (await page.$('a[href="#/admin/geofence"]')) !== null)
    const initial = await page.evaluate(() => ({
      enabled: document.querySelector('#geo-enabled').checked,
      lat: document.querySelector('#geo-lat').value,
      lon: document.querySelector('#geo-lon').value,
      radius: document.querySelector('#geo-radius').value,
      scope: document.querySelector('#geo-scope').value,
    }))
    check('G1 初始狀態：關閉、公司中心未設定、半徑 100、上下班都限制', !initial.enabled && initial.lat === '' && initial.lon === '' && initial.radius === '100' && initial.scope === 'BOTH', JSON.stringify(initial))

    await page.click('#geo-enabled')
    await setValue(page, '#geo-reason', `E2E 啟用 ${stack.runId}`)
    await click(page, '儲存設定')
    await waitText(page, '請先填寫公司中心')
    check('G1 未設定公司中心就啟用 → 前端擋下，沒有開啟確認畫面', (await page.$('[role=dialog]')) === null)
    await setValue(page, '#geo-lat', '25.0')
    await click(page, '儲存設定')
    await waitText(page, '同時填寫或同時留空')
    check('G1 只填緯度 → 「緯度與經度必須同時填寫」', true)
    await setValue(page, '#geo-lon', '1e2')
    await click(page, '儲存設定')
    await waitText(page, '十進位數字')
    check('G1 經度用 1e2 這種寫法 → 要求十進位數字', true)
    await setValue(page, '#geo-lon', '200')
    await click(page, '儲存設定')
    await waitText(page, '經度必須在 -180 到 180 之間')
    check('G1 經度 200 → 範圍錯誤', true)
    await setValue(page, '#geo-lon', String(CENTER.longitude))
    await setValue(page, '#geo-radius', '5')
    await click(page, '儲存設定')
    await waitText(page, '半徑請輸入 10 到 5000')
    check('G1 半徑 5 → 範圍錯誤（限制值來自後端）', true)
    await setValue(page, '#geo-radius', '100')
    await click(page, '儲存設定')
    await page.waitForSelector('[role=dialog]')
    const diff = await page.$$eval('[data-testid=geofence-diff] tbody tr', (rows) => rows.map((r) => [...r.cells].map((c) => c.textContent)))
    check('G1 確認畫面列出修改前後：關閉→啟用、中心 未設定→25, 121.5、半徑、適用操作',
      JSON.stringify(diff[0]) === JSON.stringify(['定位限制', '關閉', '啟用']) && diff[1][1] === '未設定' && diff[1][2] === '25, 121.5' && diff[2][2] === '100 公尺' && diff[3][2] === '上下班都限制',
      JSON.stringify(diff))
    await page.screenshot({ path: path.join(OUT, 'geofence-01-admin-confirm.png') })
    await click(page, '確認儲存')
    await waitText(page, '已儲存定位設定')
    await page.waitForFunction(() => document.querySelector('[data-testid=geofence-updated]')?.textContent.includes('E2E 管理員'))
    check('G1 儲存後顯示最近修改人與時間', (await page.$eval('[data-testid=geofence-updated]', (el) => el.textContent)).includes('E2E 管理員'))
    await page.waitForFunction((r) => document.querySelector('[data-testid=geofence-history]')?.textContent.includes(r), {}, `E2E 啟用 ${stack.runId}`)
    const historyText = await page.$eval('[data-testid=geofence-history] li', (el) => el.textContent)
    check('G1 設定歷程出現本次修改：原因、修改人、修改前（關閉／未設定）與修改後（啟用）',
      historyText.includes('E2E 管理員') && historyText.includes('修改前：關閉／中心 未設定') && historyText.includes('修改後：啟用／中心 25, 121.5'), historyText)

    // G2：另一位管理員用修改前載入的畫面提交
    await admin2.page.bringToFront()
    await setValue(admin2.page, '#geo-radius', '300')
    await setValue(admin2.page, '#geo-reason', '用舊畫面修改')
    await click(admin2.page, '儲存設定')
    await admin2.page.waitForSelector('[role=dialog]')
    await click(admin2.page, '確認儲存')
    await admin2.page.waitForSelector('[data-testid=geofence-conflict]')
    check('G2 過期提交 → 顯示「已被其他管理員更新，沒有儲存」', (await admin2.page.$eval('[data-testid=geofence-conflict]', (el) => el.textContent)).includes('沒有儲存'))
    await click(admin2.page, '重新載入最新設定')
    await admin2.page.waitForFunction(() => document.querySelector('#geo-enabled')?.checked === true)
    const reloaded = await admin2.page.$eval('#geo-radius', (el) => el.value)
    check('G2 重新載入後看到較新的設定（啟用、半徑 100），舊提交沒有覆蓋', reloaded === '100', reloaded)
    await assertNoUnhandled('G1／G2', [admin.tracker, admin2.tracker])
  }

  // ================= G3：設定在員工畫面打開後才啟用 → 按下時取得最新設定，改為要求定位 =================
  {
    await A.page.bringToFront()
    check('G3 員工 A 的畫面還沒重新整理（仍顯示不需要定位）', (await A.page.$eval('[data-testid=location-policy]', (el) => el.dataset.required)) === 'none')
    await geo(A.page, { mode: 'success', ...northOf(40), accuracy: 12 })
    await click(A.page, '下班打卡')
    await waitStatus(A.page, '今日已完成')
    check('G3 按下時重新取得設定 → 這次有呼叫定位、帶位置送出並成功', (await geoState(A.page)).calls === 1 && A.counts.out === 1)
    check('G3 打卡後卡片更新為「需要定位」的說明', (await A.page.$eval('[data-testid=location-policy]', (el) => el.dataset.required)) === 'inout')
    await assertNoUnhandled('G3', [A.tracker])
  }

  // ================= G4：定位失敗的各種情況、精度不足與重試、範圍外、快速重複點擊 =================
  const ctxC = await browser.createBrowserContext()
  const C = await employeePage(ctxC, userC)
  {
    const page = C.page
    await waitStatus(page, '尚未上班')
    const policyText = await page.$eval('[data-testid=location-policy]', (el) => el.textContent)
    check('G4 卡片事先說明：上下班需要定位、允許半徑 100 公尺、精度 50 公尺以內', policyText.includes('上班、下班打卡需要定位') && policyText.includes('100 公尺') && policyText.includes('50 公尺'), policyText)
    check('G4 載入頁面時沒有取得位置', (await geoState(page)).calls === 0)

    const failures = [
      ['denied', '拒絕了定位權限'],
      ['timeout', '取得位置逾時'],
      ['unavailable', '目前無法取得位置'],
      ['unsupported', '不支援定位'],
    ]
    for (const [mode, text] of failures) {
      await geo(page, { mode })
      await click(page, '上班打卡')
      await waitClockNotice(page, text)
      await noPhase(page)
      check(`G4 定位「${mode}」→ 顯示原因、沒有送出打卡、按鈕與 loading 恢復`,
        C.counts.in === 0 && (await todayStatus(page)) === '尚未上班' && !(await isDisabled(page, '上班打卡')) && (await hasButton(page, '重試上班打卡')))
    }
    await geo(page, { mode: 'success', insecure: true })
    await click(page, '上班打卡')
    await waitClockNotice(page, '不是安全來源')
    check('G4 非安全來源（HTTP 區網位址等）→ 說明需要 HTTPS，沒有送出', C.counts.in === 0)
    await geo(page, { insecure: false })

    const options = (await geoState(page)).options
    check('G4 定位參數：enableHighAccuracy=true、maximumAge=0、timeout=15000', options?.enableHighAccuracy === true && options?.maximumAge === 0 && options?.timeout === 15000, JSON.stringify(options))

    await geo(page, { mode: 'success', ...northOf(20), accuracy: 80 })
    await click(page, '上班打卡')
    await waitClockNotice(page, '定位精度不足')
    check('G4 精度 80 公尺 → 前端依後端提供的上限擋下，沒有送出', C.counts.in === 0)
    await geo(page, { accuracy: 8 })
    await click(page, '重試上班打卡')
    await waitStatus(page, '工作中')
    check('G4 按「重試」重新定位（精度 8）→ 上班成功', C.counts.in === 1)

    await geo(page, { ...northOf(300), accuracy: 10 })
    await click(page, '下班打卡')
    await waitClockNotice(page, '不在公司範圍內')
    const outside = await clockNotice(page)
    check('G4 範圍外（300 公尺）→ 顯示後端計算的距離與允許半徑；仍是工作中', outside.includes('約 300 公尺') && outside.includes('允許範圍是 100 公尺') && (await todayStatus(page)) === '工作中', outside)
    await page.screenshot({ path: path.join(OUT, 'geofence-02-outside.png') })

    // 快速重複點擊：定位延遲 1.5 秒，同一個事件迴圈內連按兩次
    const callsBefore = (await geoState(page)).calls
    const outBefore = C.counts.out
    await geo(page, { ...northOf(30), accuracy: 10, delayMs: 1500 })
    await page.evaluate(() => {
      const b = [...document.querySelectorAll('button')].find((x) => x.textContent.trim() === '下班打卡')
      b.click()
      b.click()
    })
    await page.waitForSelector('[data-testid=clock-phase][data-phase=locating]')
    check('G4 定位中：顯示「正在取得位置…」，按鈕停用', (await page.$eval('[data-testid=clock-phase]', (el) => el.textContent)).includes('正在取得位置') && (await hasButton(page, '正在取得位置…')))
    await waitStatus(page, '今日已完成')
    check('G4 快速連按兩次 → 只定位一次、只送出一次下班', (await geoState(page)).calls === callsBefore + 1 && C.counts.out === outBefore + 1,
      JSON.stringify({ calls: (await geoState(page)).calls - callsBefore, posts: C.counts.out - outBefore }))
    await geo(page, { delayMs: 0 })
    await assertNoUnhandled('G4', [C.tracker])
  }

  // ================= G5：過期的定位 callback——切頁後不送出；狀態改變後不送出、也不會變成另一個操作 =================
  const ctxB = await browser.createBrowserContext()
  const B = await employeePage(ctxB, userB)
  {
    const page = B.page
    await waitStatus(page, '尚未上班')
    await geo(page, { mode: 'success', ...northOf(10), accuracy: 10, delayMs: 2000 })
    await click(page, '上班打卡')
    await page.waitForSelector('[data-testid=clock-phase][data-phase=locating]')
    await page.click('a[href="#/attendance"]') // 首頁的卡片卸載
    await page.waitForSelector('[data-testid=records-table], [data-testid=records-empty]')
    await new Promise((r) => setTimeout(r, 2800))
    check('G5 定位中切換頁面 → 晚到的定位結果不送出打卡', B.counts.in === 0 && B.counts.out === 0)
    await waitStatus(page, '尚未上班')

    // 定位中（延遲 12 秒），同一帳號在另一個分頁先上班；本頁重新查詢後，晚到的位置不得送出上班，也不得變成下班
    await geo(page, { delayMs: 12_000 })
    await click(page, '上班打卡')
    await page.waitForSelector('[data-testid=clock-phase][data-phase=locating]')
    const other = await newPage(ctxB, { viewport: DESKTOP })
    await installFakeGeolocation(other.page)
    await other.page.goto(stack.baseUrl, { waitUntil: 'networkidle0' })
    await other.page.waitForSelector('[data-testid=today-status]')
    await geo(other.page, { mode: 'success', ...northOf(10), accuracy: 10 })
    await click(other.page, '上班打卡')
    await waitStatus(other.page, '工作中')
    await simulateReturnToWindow(page) // 本頁重新查詢 → 工作中
    await waitClockNotice(page, '今日狀態已經改變')
    check('G5 狀態已改變 → 取消這次「上班打卡」，沒有送出上班或下班', B.counts.in === 0 && B.counts.out === 0 && (await todayStatus(page)) === '工作中')
    await other.page.close()
    await assertNoUnhandled('G5', [B.tracker])
  }

  // ================= G6：定位途中管理員把半徑改小 → 後端用最新的完整設定判斷 =================
  {
    const page = B.page
    await geo(page, { mode: 'success', ...northOf(80), accuracy: 10, delayMs: 2500 })
    await click(page, '下班打卡')
    await page.waitForSelector('[data-testid=clock-phase][data-phase=locating]')
    const status = await putSettingsViaApi(admin.page, { radiusMeters: 50 }, `E2E 縮小半徑 ${stack.runId}`)
    check('G6 員工定位途中，管理員把半徑改為 50（200）', status === 200, String(status))
    await waitClockNotice(page, '不在公司範圍內')
    const text = await clockNotice(page)
    check('G6 送出時後端使用新設定：80 公尺 > 50 → 拒絕，訊息顯示允許範圍 50 公尺', text.includes('允許範圍是 50 公尺') && (await todayStatus(page)) === '工作中', text)
    await geo(page, { ...northOf(20), delayMs: 0 })
    await click(page, '重試下班打卡')
    await waitStatus(page, '今日已完成')
    check('G6 在新範圍內（20 公尺）重試 → 下班成功', B.counts.out === 2)
    await putSettingsViaApi(admin.page, { radiusMeters: 100 }, `E2E 恢復半徑 ${stack.runId}`)
  }

  // ================= G7：員工／主管沒有管理權限 =================
  {
    for (const [label, ui] of [['員工', A], ['主管', B]]) {
      await ui.page.bringToFront()
      check(`G7 ${label}沒有「定位打卡設定」入口`, (await ui.page.$('a[href="#/admin/geofence"]')) === null)
      await ui.page.goto(`${stack.baseUrl}/#/admin/geofence`, { waitUntil: 'networkidle0' })
      await waitText(ui.page, '只有管理員可以使用這個功能')
      const statuses = await ui.page.evaluate(async () => {
        const token = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '')
        const get = (await fetch('/api/admin/geofence')).status
        const history = (await fetch('/api/admin/geofence/history')).status
        const put = (await fetch('/api/admin/geofence', { method: 'PUT', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: '{}' })).status
        return { get, history, put }
      })
      check(`G7 ${label}直接呼叫設定 API → 全部 403`, Object.values(statuses).every((s) => s === 403), JSON.stringify(statuses))
    }
  }

  // ================= G8：範圍外仍可提出申請；核准後下一次打卡仍遵守設定 =================
  {
    const page = C.page
    await page.bringToFront()
    await page.goto(`${stack.baseUrl}/#/attendance`, { waitUntil: 'networkidle0' })
    await waitStatus(page, '今日已完成')
    const callsBefore = (await geoState(page)).calls
    await click(page, '申請撤銷下班打卡')
    await setValue(page, '#reason-text', '還在工作，誤按下班（人在公司外也能提出）')
    await click(page, '送出申請')
    await page.waitForSelector('[data-testid=today-pending]')
    check('G8 申請不需要定位：提出撤銷下班時沒有呼叫定位', (await geoState(page)).calls === callsBefore)

    await admin.page.bringToFront()
    await admin.page.click('a[href="#/admin/attendance"]')
    await admin.page.waitForFunction((n) => [...document.querySelectorAll('li.request-item')].some((x) => x.textContent.includes(n)), {}, `EC-${stack.runId}`)
    await admin.page.evaluate((n) => {
      const li = [...document.querySelectorAll('li.request-item')].find((x) => x.textContent.includes(n))
      ;[...li.querySelectorAll('button')].find((b) => b.textContent.trim() === '核准').click()
    }, `EC-${stack.runId}`)
    await admin.page.waitForSelector('[role=dialog]')
    await click(admin.page, '確認核准')
    await waitText(admin.page, '已核准')

    await page.bringToFront()
    let working = false
    for (let i = 0; i < 6 && !working; i++) {
      await simulateReturnToWindow(page)
      working = await page.waitForFunction(() => document.querySelector('[data-testid=today-status]')?.textContent === '工作中', { timeout: 4000 }).then(() => true, () => false)
    }
    check('G8 核准後恢復工作中（申請流程照常）', working)
    await geo(page, { mode: 'success', ...northOf(500), accuracy: 10 })
    await click(page, '下班打卡')
    await waitClockNotice(page, '不在公司範圍內')
    check('G8 核准後的下一次實際打卡仍遵守定位設定（500 公尺 → 拒絕）', (await todayStatus(page)) === '工作中')
  }

  // ================= G9：管理員自己打卡也受限制（Chrome 真正的 Geolocation API＋DevTools 定位模擬） =================
  {
    const ctx = await browser.createBrowserContext()
    await ctx.overridePermissions(stack.baseUrl, ['geolocation'])
    const ui = await newPage(ctx, { viewport: DESKTOP })
    await loginAs(ui.page, userAdmin.username, userAdmin.password, stack.baseUrl)
    await waitStatus(ui.page, '尚未上班')
    await ui.page.setGeolocation({ ...northOf(400), accuracy: 15 })
    await click(ui.page, '上班打卡')
    await waitClockNotice(ui.page, '不在公司範圍內')
    check('G9 管理員在 400 公尺外 → 一樣被拒絕（沒有繞過權限）', (await todayStatus(ui.page)) === '尚未上班')
    await ui.page.setGeolocation({ ...northOf(30), accuracy: 15 })
    await click(ui.page, '重試上班打卡')
    await waitStatus(ui.page, '工作中')
    check('G9 透過瀏覽器真正的 Geolocation API（DevTools 模擬 30 公尺）→ 上班成功', true)
    await assertNoUnhandled('G9', [ui.tracker])
    await ctx.close()
  }

  // ================= G10：手機尺寸 =================
  {
    const ctx = await browser.createBrowserContext()
    const ui = await adminSettingsPage(ctx, MOBILE)
    check('G10 手機：定位設定頁沒有橫向捲動', await noHorizontalOverflow(ui.page))
    await setValue(ui.page, '#geo-radius', '120')
    await setValue(ui.page, '#geo-reason', '手機上調整半徑')
    await (await button(ui.page, '儲存設定')).tap()
    await ui.page.waitForSelector('[role=dialog]')
    const fits = await ui.page.$eval('.modal', (el) => el.getBoundingClientRect().width <= window.innerWidth && el.getBoundingClientRect().left >= 0)
    check('G10 手機：確認畫面完整顯示、沒有橫向捲動', fits && (await noHorizontalOverflow(ui.page)))
    await ui.page.screenshot({ path: path.join(OUT, 'geofence-03-mobile-confirm.png') })
    await (await button(ui.page, '確認儲存')).tap()
    await waitText(ui.page, '已儲存定位設定')
    check('G10 手機：管理員可完成儲存', true)

    const ctxM = await browser.createBrowserContext()
    const emp = await newPage(ctxM, { viewport: MOBILE })
    await installFakeGeolocation(emp.page)
    await loginAs(emp.page, userA.username, userA.password, stack.baseUrl)
    await emp.page.waitForSelector('[data-testid=location-policy]')
    check('G10 手機：員工首頁（含定位說明）沒有橫向捲動', await noHorizontalOverflow(emp.page))
    const policy = await emp.page.$eval('[data-testid=location-policy]', (el) => el.textContent)
    check('G10 手機：定位說明顯示新的半徑 120 公尺', policy.includes('120 公尺'), policy)
    await emp.page.screenshot({ path: path.join(OUT, 'geofence-04-mobile-employee.png') })
    await assertNoUnhandled('G10', [ui.tracker, emp.tracker])
    await ctx.close()
    await ctxM.close()
  }

  check('最後確認：沒有任何頁面呼叫 watchPosition', (await geoState(C.page)).watchCalls === 0 && (await geoState(A.page)).watchCalls === 0)
  await assertNoUnhandled('全程', [A.tracker, B.tracker, C.tracker, admin.tracker, admin2.tracker])
  check('沒有把使用者帶回登入頁（定位錯誤、403、範圍外都不是登出）', (await bodyText(C.page)).includes('TeamPortal') && (await C.page.$('.login-card')) === null)
} catch (error) {
  check('腳本執行未中斷', false, String(error?.stack ?? error))
} finally {
  await browser?.close()
  stack?.stop()
}

finish()
