// R3：登出失敗情境（對應審查項目 R3）。與 smoke.mjs 相同的前置條件與環境變數。
// 用 puppeteer 的請求攔截「受控地」讓 /api/auth/logout 或 /api/auth/me 失敗，
// 驗證畫面提示、載入狀態結束，以及沒有未處理的 Promise 拒絕。真實後端仍在運作。
// 執行：cd frontend; npm run e2e:logout
import { BASE, createRunner, launchBrowser, loginAs, readSeedPassword, trackPageErrors } from './helpers.mjs'

const PW = readSeedPassword()
const { check, finish } = createRunner()
const browser = await launchBrowser()

const json = (status, body) => ({ status, contentType: 'application/json', body: JSON.stringify(body) })

/** 每個情境用全新的瀏覽器環境（Cookie 不互相影響），並可動態切換 logout／me 的行為。 */
async function newScenario() {
  const context = await browser.createBrowserContext()
  const page = await context.newPage()
  page.setDefaultTimeout(10_000)
  const tracker = await trackPageErrors(page)
  const mode = { logout: 'pass', me: 'pass', logoutDelayMs: 0 }

  await page.setRequestInterception(true)
  page.on('request', async (request) => {
    const url = new URL(request.url())
    if (url.pathname === '/api/auth/logout' && request.method() === 'POST' && mode.logout !== 'pass') {
      if (mode.logoutDelayMs) await new Promise((r) => setTimeout(r, mode.logoutDelayMs))
      if (mode.logout === 'abort') return request.abort('failed')
      if (mode.logout === '403') return request.respond(json(403, { code: 'CSRF_REJECTED', message: '安全驗證失敗' }))
      if (mode.logout === '500') return request.respond(json(500, { code: 'INTERNAL_ERROR', message: '系統發生錯誤' }))
    }
    if (url.pathname === '/api/auth/me' && request.method() === 'GET' && mode.me !== 'pass') {
      if (mode.me === 'abort') return request.abort('failed')
      if (mode.me === '401') return request.respond(json(401, { code: 'UNAUTHENTICATED', message: '請先登入' }))
    }
    return request.continue()
  })

  await loginAs(page, 'employee', PW)
  return { context, page, mode, tracker }
}

const bannerText = (page) => page.$eval('.banner--error', (el) => el.textContent)
const logoutButton = (page) => page.$('.topbar .btn')
const buttonState = (page) => page.$eval('.topbar .btn', (b) => ({ label: b.textContent, disabled: b.disabled }))

async function assertNoUnhandled(name, { tracker }) {
  const rejections = await tracker.unhandledRejections()
  check(`${name}：沒有未處理的 Promise 拒絕`, rejections.length === 0, rejections.join(' | '))
  check(`${name}：沒有未捕捉的 JS 例外`, tracker.pageErrors.length === 0, tracker.pageErrors.join(' | '))
}

try {
  // ---- A：登出回 403，但 /me 確認仍是登入 ----
  {
    const s = await newScenario()
    s.mode.logout = '403'
    s.mode.logoutDelayMs = 400
    await (await logoutButton(s.page)).click()
    await s.page.waitForFunction(() => document.querySelector('.topbar .btn')?.textContent === '登出中…')
    check('A 登出進行中：按鈕顯示「登出中…」並停用', (await buttonState(s.page)).disabled)

    await s.page.waitForSelector('.banner--error')
    const text = await bannerText(s.page)
    check('A 403：顯示可讀的失敗提示，且說明仍在登入狀態', text.includes('登出失敗') && text.includes('安全驗證') && text.includes('仍在登入狀態'), text)
    check('A 403：仍停留在首頁（以 /me 確認的真實狀態為準，不假裝已登出）', (await s.page.$('.topbar')) !== null && (await s.page.$('.login-card')) === null)
    const state = await buttonState(s.page)
    check('A 403：載入狀態已結束，按鈕可再試（標示「重試登出」）', !state.disabled && state.label === '重試登出', JSON.stringify(state))

    s.mode.logout = 'pass'
    s.mode.logoutDelayMs = 0
    await (await logoutButton(s.page)).click()
    await s.page.waitForSelector('.login-card')
    check('A 403：排除故障後重試成功，回到登入頁', true)
    await assertNoUnhandled('A', s)
    await s.context.close()
  }

  // ---- B：登出時網路中斷 ----
  {
    const s = await newScenario()
    s.mode.logout = 'abort'
    await (await logoutButton(s.page)).click()
    await s.page.waitForSelector('.banner--error')
    const text = await bannerText(s.page)
    check('B 網路錯誤：顯示無法連線的提示且仍在登入狀態', text.includes('無法連線') && text.includes('仍在登入狀態'), text)
    check('B 網路錯誤：按鈕恢復可點擊', !(await buttonState(s.page)).disabled)
    await assertNoUnhandled('B', s)
    await s.context.close()
  }

  // ---- C：登出回 500 ----
  {
    const s = await newScenario()
    s.mode.logout = '500'
    await (await logoutButton(s.page)).click()
    await s.page.waitForSelector('.banner--error')
    const text = await bannerText(s.page)
    check('C 5xx：顯示伺服器暫時無法處理的提示且仍在登入狀態', text.includes('伺服器暫時無法處理') && text.includes('仍在登入狀態'), text)
    check('C 5xx：按鈕恢復可點擊', !(await buttonState(s.page)).disabled)
    await assertNoUnhandled('C', s)
    await s.context.close()
  }

  // ---- D：登出請求報錯，但 /me 確認已不是登入（例如 Session 剛好過期）----
  {
    const s = await newScenario()
    s.mode.logout = '500'
    s.mode.me = '401'
    await (await logoutButton(s.page)).click()
    await s.page.waitForSelector('.login-card')
    check('D 登出請求失敗但 /me 回 401：以後端確認的狀態為準，回到登入頁', true)
    await assertNoUnhandled('D', s)
    await s.context.close()
  }

  // ---- E：登出失敗且無法確認狀態（/me 也連不上）→ unavailable ----
  {
    const s = await newScenario()
    s.mode.logout = '403'
    s.mode.me = 'abort'
    await (await logoutButton(s.page)).click()
    await s.page.waitForSelector('.center-message [role=alert]')
    const text = await s.page.$eval('.center-message [role=alert]', (el) => el.textContent)
    check('E 無法確認狀態：顯示 unavailable 畫面（不是登入頁，也不假設已登出）', text.length > 0 && (await s.page.$('.login-card')) === null, text)
    check('E 無法確認狀態：提供重試按鈕', (await s.page.$('.center-message .btn')) !== null)

    s.mode.logout = 'pass'
    s.mode.me = 'pass'
    await s.page.click('.center-message .btn')
    await s.page.waitForSelector('.topbar')
    check('E 重試後向後端確認：Session 仍有效，回到首頁', true)
    await assertNoUnhandled('E', s)
    await s.context.close()
  }
} catch (error) {
  check('腳本執行未中斷', false, String(error))
} finally {
  await browser.close()
}

console.log(`(base: ${BASE})`)
finish()
