// R4：密碼 UTF-8 位元組上限（前端提示＋後端強制）。與 smoke.mjs 相同的前置條件與環境變數。
// 執行：cd frontend; npm run e2e:password
import { BASE, createRunner, launchBrowser, trackPageErrors } from './helpers.mjs'

const { check, finish } = createRunner()
const browser = await launchBrowser()
const page = await browser.newPage()
page.setDefaultTimeout(10_000)
const tracker = await trackPageErrors(page)

const hintVisible = async () => (await page.$('#password-hint')) !== null
const submitDisabled = () => page.$eval('button[type=submit]', (b) => b.disabled)
const passwordValue = () => page.$eval('#password', (i) => i.value)

async function typePassword(value) {
  await page.$eval('#password', (i) => { i.value = '' })
  // 以 React 可感知的方式清空後輸入
  await page.click('#password', { clickCount: 3 })
  await page.keyboard.press('Backspace')
  await page.type('#password', value)
}

try {
  await page.goto(BASE, { waitUntil: 'networkidle0' })
  await page.type('#username', 'employee')

  await typePassword('中'.repeat(24))
  check('中文 24 字（72 bytes）：不顯示提示，可送出', !(await hintVisible()) && !(await submitDisabled()))

  await typePassword('中'.repeat(24) + '文')
  check('中文 25 字（75 bytes）：顯示位元組提示並停用送出', (await hintVisible()) && (await submitDisabled()))
  const hint = await page.$eval('#password-hint', (el) => el.textContent)
  check('提示文字包含目前位元組數 75 與上限 72', hint.includes('75') && hint.includes('72'), hint)
  check('不截斷使用者輸入（欄位仍是 25 個字）', [...(await passwordValue())].length === 25)

  await typePassword('😀'.repeat(18))
  check('emoji 18 個（72 bytes）：不顯示提示', !(await hintVisible()))
  await typePassword('😀'.repeat(18) + 'a')
  check('emoji 18 個＋1 個 ASCII（73 bytes）：顯示提示', await hintVisible())

  await typePassword('a'.repeat(72))
  check('ASCII 72 bytes：不顯示提示', !(await hintVisible()))
  await typePassword('a'.repeat(73))
  check('ASCII 73 bytes：顯示提示並停用送出', (await hintVisible()) && (await submitDisabled()))

  // 繞過前端：直接對後端送 75 bytes 的密碼，證明後端自己會擋（前端提示不能取代後端驗證）
  const direct = await page.evaluate(async (password) => {
    const token = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1]
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': decodeURIComponent(token ?? '') },
      body: JSON.stringify({ username: 'employee', password }),
    })
    return { status: response.status, body: await response.json() }
  }, '中'.repeat(24) + '文')
  check('繞過前端直送 75 bytes：後端回 400 VALIDATION_FAILED', direct.status === 400 && direct.body.code === 'VALIDATION_FAILED', JSON.stringify(direct))

  const rejections = await tracker.unhandledRejections()
  check('沒有未處理的 Promise 拒絕與 JS 例外', rejections.length === 0 && tracker.pageErrors.length === 0)
} catch (error) {
  check('腳本執行未中斷', false, String(error))
} finally {
  await browser.close()
}

finish()
