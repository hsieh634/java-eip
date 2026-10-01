// T03：打卡的瀏覽器端到端驗證，在【隔離堆疊】執行（見 isolated-stack.mjs）：
// 另一個後端（8081，測試資料庫）＋另一個前端（5174）＋本次專用的新員工。不會碰你的開發帳號與出勤，
// 也不需要清空資料即可重跑。需要 Java／Maven 與資料庫容器（測試資料庫 renewflow_test）可用。
// 執行：cd frontend; npm run e2e:attendance
import path from 'node:path'
import { OUT, createRunner, launchBrowser, loginAs, trackPageErrors } from './helpers.mjs'
import { startIsolatedStack } from './isolated-stack.mjs'

const { check, finish } = createRunner()
let stack
let browser

const json = (status, body) => ({ status, contentType: 'application/json', body: JSON.stringify(body) })

/** 依標籤文字找按鈕（打卡按鈕在處理中文字會變，所以每次重新找）。 */
async function button(page, label) {
  const handle = await page.evaluateHandle(
    (l) => [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === l) ?? null,
    label,
  )
  return handle.asElement()
}
const isDisabled = async (page, label) => (await button(page, label)).evaluate((b) => b.disabled)
const todayStatus = (page) => page.$eval('[data-testid=today-status]', (el) => el.textContent)
const waitStatus = (page, text) =>
  page.waitForFunction((t) => document.querySelector('[data-testid=today-status]')?.textContent === t, {}, text)
const waitNotice = (page, includes) =>
  page.waitForFunction((t) => [...document.querySelectorAll('.notice')].some((n) => n.textContent.includes(t)), {}, includes)
const minutesOfDay = (hms) => {
  const [h, m] = hms.split(':').map(Number)
  return h * 60 + m
}
const taipeiNowMinutes = () =>
  minutesOfDay(new Intl.DateTimeFormat('zh-TW', { timeZone: 'Asia/Taipei', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date()))

/** 在 React 受控的 input 上設定值並觸發事件。 */
async function setInput(page, selector, value) {
  await page.$eval(selector, (el, v) => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    setter.call(el, v)
    el.dispatchEvent(new Event('input', { bubbles: true }))
  }, value)
}

async function newPage(context, { viewport } = {}) {
  const page = await context.newPage()
  page.setDefaultTimeout(15_000)
  if (viewport) await page.setViewport(viewport)
  const tracker = await trackPageErrors(page)
  return { page, tracker }
}

async function assertNoUnhandled(name, trackers) {
  const rejections = (await Promise.all(trackers.map((t) => t.unhandledRejections()))).flat()
  const errors = trackers.flatMap((t) => t.pageErrors)
  check(`${name}：沒有未處理的 Promise 拒絕與 JS 例外`, rejections.length === 0 && errors.length === 0, [...rejections, ...errors].join(' | '))
}

try {
  stack = await startIsolatedStack()
  console.log(`隔離堆疊已啟動：${stack.baseUrl}（測試資料庫；本次專用員工 ${stack.users.a.username} 等）`)
  browser = await launchBrowser()
  const base = stack.baseUrl

  // ================= 品牌 =================
  {
    const context = await browser.createBrowserContext()
    const { page, tracker } = await newPage(context)
    await page.goto(base, { waitUntil: 'networkidle0' })
    check('登入頁品牌：TeamPortal／企業員工入口平台', (await page.$eval('.brand', (el) => el.textContent)).includes('TeamPortal') && (await page.$eval('.brand', (el) => el.textContent)).includes('企業員工入口平台'))
    check('瀏覽器分頁標題含 TeamPortal', (await page.title()).includes('TeamPortal'))
    check('favicon 已設定', (await page.$eval('link[rel=icon]', (el) => el.getAttribute('href'))) === '/favicon.svg')
    await assertNoUnhandled('品牌', [tracker])
    await context.close()
  }

  // ================= S1：員工 A 的完整打卡流程（桌面） =================
  {
    const context = await browser.createBrowserContext()
    const { page, tracker } = await newPage(context, { viewport: { width: 1200, height: 800 } })
    await loginAs(page, stack.users.a.username, stack.users.a.password, base)
    await waitStatus(page, '尚未上班')
    check('S1 首頁今日打卡：尚未上班，上班可按、下班停用', !(await isDisabled(page, '上班打卡')) && (await isDisabled(page, '下班打卡')))
    check('S1 側邊選單「出勤打卡」已啟用；工時／請假／設定仍為尚未啟用', (await page.$('a[href="#/attendance"]')) !== null && (await page.$$eval('.nav-item:disabled', (els) => els.length)) === 3)
    check('S1 顯示白班與跨日限制說明', (await page.$eval('.card--attendance', (el) => el.textContent)).includes('不支援跨日班'))
    await page.screenshot({ path: path.join(OUT, 'attendance-01-home-not-started.png') })

    await (await button(page, '上班打卡')).click()
    await waitStatus(page, '工作中')
    const inTime = await page.$eval('[data-testid=clock-in-time]', (el) => el.textContent)
    check('S1 上班後狀態為工作中，並顯示台北時間', /^\d\d:\d\d:\d\d$/.test(inTime) && Math.abs(minutesOfDay(inTime) - taipeiNowMinutes()) <= 3, inTime)
    check('S1 上班後：上班停用、下班可按；顯示成功提示', (await isDisabled(page, '上班打卡')) && !(await isDisabled(page, '下班打卡')) && (await page.$$eval('.notice--success', (e) => e.length)) === 1)

    await page.click('a[href="#/attendance"]')
    await page.waitForSelector('[data-testid=records-table]')
    check('S1 出勤頁：網址 hash 為 #/attendance，選單高亮', page.url().endsWith('#/attendance') && (await page.$eval('a[href="#/attendance"]', (el) => el.getAttribute('aria-current'))) === 'page')
    check('S1 出勤頁：預設本月，列出今日一筆「工作中」', (await page.$$eval('[data-testid=records-table] tbody tr', (r) => r.length)) === 1 && (await page.$eval('.status-chip', (el) => el.textContent)) === '工作中')

    await (await button(page, '下班打卡')).click()
    await waitStatus(page, '今日已完成')
    await page.waitForFunction(() => document.querySelector('.status-chip')?.textContent === '已完成')
    check('S1 下班後：今日卡片與紀錄列表同步更新為已完成，兩顆按鈕都停用', (await isDisabled(page, '上班打卡')) && (await isDisabled(page, '下班打卡')))
    await page.screenshot({ path: path.join(OUT, 'attendance-02-page-completed.png') })

    await page.reload({ waitUntil: 'networkidle0' })
    await waitStatus(page, '今日已完成')
    check('S1 重新整理後仍停留在出勤頁且狀態正確（hash 保留）', page.url().endsWith('#/attendance') && (await page.$('[data-testid=records-table]')) !== null)
    await page.goBack()
    await page.waitForSelector('main h1')
    check('S1 瀏覽器「上一頁」回到首頁', (await page.$eval('main h1', (el) => el.textContent)) === '首頁')

    // ---- 日期範圍 UI（A 已有今日一筆）----
    await page.click('a[href="#/attendance"]')
    await page.waitForSelector('#range-from')
    await setInput(page, '#range-from', '2000-01-01')
    await setInput(page, '#range-to', '2000-01-31')
    await (await button(page, '查詢')).click()
    await page.waitForSelector('[data-testid=records-empty]')
    check('S1 日期範圍：該區間沒有紀錄時顯示明確的空狀態', (await page.$eval('[data-testid=records-empty]', (el) => el.textContent)).includes('沒有出勤紀錄'))
    await setInput(page, '#range-from', '2000-02-10')
    await setInput(page, '#range-to', '2000-02-01')
    await (await button(page, '查詢')).click()
    await page.waitForFunction(() => [...document.querySelectorAll('[role=alert]')].some((e) => e.textContent.includes('起日不可晚於迄日')))
    check('S1 日期範圍：起日大於迄日 → 清楚的錯誤提示', true)
    await setInput(page, '#range-from', '2026-01-01')
    await setInput(page, '#range-to', '2026-12-31')
    await (await button(page, '查詢')).click()
    await page.waitForFunction(() => [...document.querySelectorAll('[role=alert]')].some((e) => e.textContent.includes('最多 31 天')))
    check('S1 日期範圍：超過 31 天 → 清楚的錯誤提示', true)
    // 後端也會擋（前端檢查被繞過時）：直接送 32 天
    const direct = await page.evaluate(async () => (await fetch('/api/attendance/me?from=2026-01-01&to=2026-02-01')).status)
    check('S1 日期範圍：繞過前端直送 32 天，後端回 400', direct === 400, String(direct))
    await assertNoUnhandled('S1', [tracker])
    await context.close()
  }

  // ================= S2：員工 B——重複打卡（409）與回應遺失 =================
  {
    const context = await browser.createBrowserContext()
    const first = await newPage(context, { viewport: { width: 1200, height: 800 } })
    const page1 = first.page
    const mode = { clockOut: 'pass' }
    await page1.setRequestInterception(true)
    page1.on('request', async (request) => {
      const url = new URL(request.url())
      if (url.pathname === '/api/attendance/clock-out' && request.method() === 'POST' && mode.clockOut === 'lost-response') {
        // 回應遺失：請求其實送達並被伺服器處理，但瀏覽器收不到回應
        const cookies = await page1.cookies()
        const headers = { ...request.headers(), cookie: cookies.map((c) => `${c.name}=${c.value}`).join('; ') }
        delete headers.host
        delete headers['content-length']
        await fetch(request.url(), { method: 'POST', headers })
        return request.abort('failed')
      }
      return request.continue()
    })
    await loginAs(page1, stack.users.b.username, stack.users.b.password, base)
    await waitStatus(page1, '尚未上班')

    // 另一個分頁（同一個登入）先打上班卡
    const second = await newPage(context, { viewport: { width: 1200, height: 800 } })
    await second.page.bringToFront()
    await second.page.goto(`${base}/#/attendance`, { waitUntil: 'networkidle0' })
    await waitStatus(second.page, '尚未上班')
    await (await button(second.page, '上班打卡')).click()
    await waitStatus(second.page, '工作中')

    // 第一個分頁仍顯示「尚未上班」且按鈕可按（過期的畫面）→ 真正的重複上班
    // （背景分頁在無頭瀏覽器中會被節流，互動前先切到前景）
    await page1.bringToFront()
    check('S2 過期的分頁仍顯示可按的上班按鈕', (await todayStatus(page1)) === '尚未上班' && !(await isDisabled(page1, '上班打卡')))
    await (await button(page1, '上班打卡')).click()
    await waitNotice(page1, '已經上班打卡')
    await waitStatus(page1, '工作中')
    check('S2 重複上班：顯示後端的 409 訊息，並刷新為最新狀態（工作中）', (await page1.$eval('.notice--error', (el) => el.textContent)).includes('已更新為最新狀態'))
    check('S2 重複上班後沒有被導回登入頁', (await page1.$('.login-card')) === null)

    // 回應遺失的下班
    mode.clockOut = 'lost-response'
    await (await button(page1, '下班打卡')).click()
    await waitNotice(page1, '無法確認打卡結果')
    await waitStatus(page1, '今日已完成')
    const outTime = await page1.$eval('[data-testid=clock-out-time]', (el) => el.textContent)
    check('S2 回應遺失：畫面不假設結果，重新查詢後顯示伺服器實際已下班', /^\d\d:\d\d:\d\d$/.test(outTime), outTime)
    check('S2 回應遺失後：按鈕狀態恢復一致（皆停用）', (await isDisabled(page1, '上班打卡')) && (await isDisabled(page1, '下班打卡')))
    await assertNoUnhandled('S2', [first.tracker, second.tracker])
    await context.close()
  }

  // ================= S3：員工 C——5xx、網路中斷、登入失效（401） =================
  {
    const context = await browser.createBrowserContext()
    const first = await newPage(context, { viewport: { width: 1200, height: 800 } })
    const page1 = first.page
    const mode = { clockIn: 'pass' }
    await page1.setRequestInterception(true)
    page1.on('request', (request) => {
      const url = new URL(request.url())
      if (url.pathname === '/api/attendance/clock-in' && request.method() === 'POST') {
        if (mode.clockIn === '500') return request.respond(json(500, { code: 'INTERNAL_ERROR', message: '系統發生錯誤' }))
        if (mode.clockIn === 'abort') return request.abort('failed')
      }
      return request.continue()
    })
    await loginAs(page1, stack.users.c.username, stack.users.c.password, base)
    await waitStatus(page1, '尚未上班')

    mode.clockIn = '500'
    await (await button(page1, '上班打卡')).click()
    await waitNotice(page1, '無法確認打卡結果')
    check('S3 5xx：顯示無法確認的提示，且不會導回登入頁（不是登入問題）', (await page1.$('.login-card')) === null && (await page1.$('.topbar')) !== null)
    check('S3 5xx：重新查詢後狀態仍是尚未上班，按鈕恢復可按', (await todayStatus(page1)) === '尚未上班' && !(await isDisabled(page1, '上班打卡')))

    mode.clockIn = 'abort'
    await (await button(page1, '上班打卡')).click()
    await page1.waitForFunction(() => [...document.querySelectorAll('.notice--error')].length === 1)
    check('S3 網路中斷：同樣顯示無法確認，狀態仍是尚未上班，可再試', (await todayStatus(page1)) === '尚未上班' && !(await isDisabled(page1, '上班打卡')))

    // 登入失效：同一個登入在另一個分頁登出，這個分頁再打卡 → 真實的 401
    mode.clockIn = 'pass'
    const second = await newPage(context, { viewport: { width: 1200, height: 800 } })
    await second.page.bringToFront()
    await second.page.goto(base, { waitUntil: 'networkidle0' })
    await second.page.waitForSelector('.topbar .btn')
    await second.page.click('.topbar .btn')
    await second.page.waitForSelector('.login-card')
    await page1.bringToFront()
    await (await button(page1, '上班打卡')).click()
    await page1.waitForSelector('.login-card')
    check('S3 登入失效（401）：回到登入頁', true)
    await assertNoUnhandled('S3', [first.tracker, second.tracker])
    await context.close()
  }

  // ================= S4：手機尺寸（員工 C 重新登入，完整操作） =================
  {
    const context = await browser.createBrowserContext()
    const { page, tracker } = await newPage(context, { viewport: { width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true } })
    await loginAs(page, stack.users.c.username, stack.users.c.password, base)
    await waitStatus(page, '尚未上班')
    const noOverflow = () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)
    check('S4 手機：首頁沒有橫向捲動', await noOverflow())
    const clockInButton = await button(page, '上班打卡')
    const height = await clockInButton.evaluate((b) => b.getBoundingClientRect().height)
    check('S4 手機：打卡按鈕高度 ≥ 44px（容易點擊）', height >= 44, `${height}px`)
    await page.screenshot({ path: path.join(OUT, 'attendance-03-mobile-home.png') })

    await clockInButton.tap()
    await waitStatus(page, '工作中')
    check('S4 手機：點擊上班打卡成功', true)

    await (await page.$('a[href="#/attendance"]')).tap()
    await page.waitForSelector('[data-testid=records-table]')
    check('S4 手機：出勤頁沒有橫向捲動', await noOverflow())
    check('S4 手機：紀錄表改為卡片式（表頭隱藏、每格有標籤）', (await page.$eval('.records-table thead', (el) => getComputedStyle(el).position)) === 'absolute' && (await page.$eval('.records-table td', (el) => el.getAttribute('data-label'))) === '日期')
    await page.screenshot({ path: path.join(OUT, 'attendance-04-mobile-records.png') })

    await (await button(page, '下班打卡')).tap()
    await waitStatus(page, '今日已完成')
    check('S4 手機：點擊下班打卡成功，狀態為今日已完成', true)
    await assertNoUnhandled('S4', [tracker])
    await context.close()
  }
} catch (error) {
  check('腳本執行未中斷', false, String(error?.stack ?? error))
} finally {
  await browser?.close()
  stack?.stop()
}

finish()
