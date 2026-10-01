// 瀏覽器端冒煙測試（對應 docs/renewflow/t01-t02/specification.md 的 S-17）。
// 用 puppeteer-core 驅動「本機已安裝」的 Edge／Chrome（不下載瀏覽器）。
//
// 前置條件：資料庫、後端（dev profile）、前端開發伺服器都已啟動，且 dev 帳號已建立。
// 執行：cd frontend; npm run e2e
//
// 環境變數（皆可選）：
//   E2E_BASE_URL                 預設 http://localhost:5173
//   BROWSER_PATH                 瀏覽器執行檔；未設定時自動尋找 Edge／Chrome
//   RENEWFLOW_DEV_SEED_PASSWORD  開發帳號密碼；未設定時讀專案根目錄 .env
import path from 'node:path'
import { BASE, OUT, createRunner, launchBrowser, readSeedPassword, trackPageErrors } from './helpers.mjs'

const PW = readSeedPassword()
const { check, finish } = createRunner()

const browser = await launchBrowser()
const page = await browser.newPage()
await page.setViewport({ width: 1200, height: 800 })
const tracker = await trackPageErrors(page)

const text = (selector) => page.$eval(selector, (el) => el.textContent)

try {
  // 1. 未登入 → 登入頁
  await page.goto(BASE, { waitUntil: 'networkidle0' })
  check('未登入看到登入頁', (await text('h1')) === '登入')
  check('登入頁品牌為 TeamPortal，分頁標題含 TeamPortal', (await text('.brand')).includes('TeamPortal') && (await page.title()).includes('TeamPortal'))
  await page.screenshot({ path: path.join(OUT, '01-login.png') })

  // 2. 空欄位時送出鈕停用
  check('帳密空白時登入鈕停用', await page.$eval('button[type=submit]', (b) => b.disabled))

  // 3. 錯誤密碼
  await page.type('#username', 'employee')
  await page.type('#password', 'definitely-wrong')
  await page.click('button[type=submit]')
  await page.waitForFunction(() => document.querySelector('.form-error')?.textContent)
  check('錯誤密碼顯示錯誤訊息', (await text('.form-error')) === '帳號或密碼錯誤')
  check('失敗後密碼欄位被清空', (await page.$eval('#password', (i) => i.value)) === '')
  await page.screenshot({ path: path.join(OUT, '02-login-error.png') })

  // 4. 正確登入
  await page.type('#password', PW)
  await page.click('button[type=submit]')
  await page.waitForSelector('.topbar')
  check('登入成功進入首頁', (await text('main h1')) === '首頁')
  check('頂欄顯示使用者姓名與角色', (await text('.topbar__name')) === '開發員工' && (await text('.badge')) === '員工')
  check('「我的資訊」顯示員工編號', (await page.$eval('.cards', (el) => el.innerText)).includes('D0003'))
  const pendingTexts = await page.$$eval('.card--pending', (els) => els.map((e) => e.innerText))
  check('未啟用功能標示「尚未啟用」且無統計數字', pendingTexts.length === 2 && pendingTexts.every((t) => t.includes('尚未啟用') && !/\d+\s*(小時|筆|件)/.test(t)))
  check('側邊選單：出勤打卡已啟用，其餘未啟用項目為 disabled', (await page.$$eval('.nav-item:disabled', (els) => els.length)) === 3)
  // 今日打卡卡片顯示真實資料（只讀取，不打卡）：狀態必為三種之一
  await page.waitForSelector('[data-testid=today-status]') // 卡片資料是非同步載入的
  const attendanceStatus = await page.$eval('[data-testid=today-status]', (el) => el.textContent)
  check('首頁今日打卡卡片顯示後端狀態', ['尚未上班', '工作中', '今日已完成'].includes(attendanceStatus), attendanceStatus)
  await page.screenshot({ path: path.join(OUT, '03-home.png') })

  // 5. Cookie：JSESSIONID 對 JavaScript 不可見（HttpOnly）；XSRF-TOKEN 必須可讀
  const visibleCookies = await page.evaluate(() => document.cookie)
  check('JavaScript 讀不到 JSESSIONID（HttpOnly）', !visibleCookies.includes('JSESSIONID'))
  check('JavaScript 讀得到 XSRF-TOKEN', visibleCookies.includes('XSRF-TOKEN='))

  // 6. 重新整理仍是登入狀態
  await page.reload({ waitUntil: 'networkidle0' })
  check('重新整理後仍在首頁', (await text('main h1')) === '首頁')

  // 7. 登出
  await page.click('.topbar .btn')
  await page.waitForSelector('.login-card')
  check('登出後回到登入頁', (await text('h1')) === '登入')
  check('登出後 /api/auth/me 為 401', (await page.evaluate(async () => (await fetch('/api/auth/me')).status)) === 401)

  // 8. 登出後不重新整理即可再次登入（CSRF token 已更新）
  await page.type('#username', 'manager')
  await page.type('#password', PW)
  await page.click('button[type=submit]')
  await page.waitForSelector('.topbar')
  check('登出後可直接再次登入（不同角色）', (await text('.badge')) === '主管')
  await page.click('.topbar .btn')
  await page.waitForSelector('.login-card')

  const rejections = await tracker.unhandledRejections()
  check('頁面沒有未捕捉的 JS 例外與未處理的 Promise 拒絕', tracker.pageErrors.length === 0 && rejections.length === 0, [...tracker.pageErrors, ...rejections].join(' | '))
} catch (error) {
  check('腳本執行未中斷', false, String(error))
} finally {
  await browser.close()
}

finish()
