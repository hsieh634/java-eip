// E2E 共用的畫面操作小工具（打卡與更正的腳本使用）。
import { trackPageErrors } from './helpers.mjs'

/** 依標籤文字找按鈕（按鈕文字會隨處理中改變，所以每次重新找）。找不到回傳 null。 */
export async function button(page, label) {
  const handle = await page.evaluateHandle(
    (l) => [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === l) ?? null,
    label,
  )
  return handle.asElement()
}

export async function click(page, label) {
  await page.waitForFunction((l) => [...document.querySelectorAll('button')].some((b) => b.textContent.trim() === l && !b.disabled), {}, label)
  await (await button(page, label)).click()
}

export const isDisabled = async (page, label) => (await button(page, label)).evaluate((b) => b.disabled)
export const hasButton = async (page, label) => (await button(page, label)) !== null
export const todayStatus = (page) => page.$eval('[data-testid=today-status]', (el) => el.textContent)
export const waitStatus = (page, text) =>
  page.waitForFunction((t) => document.querySelector('[data-testid=today-status]')?.textContent === t, {}, text)
export const waitText = (page, text) => page.waitForFunction((t) => document.body.innerText.includes(t), {}, text)
export const bodyText = (page) => page.evaluate(() => document.body.innerText)

/** 在 React 受控的 input／textarea／select 上設定值並觸發事件。 */
export async function setValue(page, selector, value) {
  await page.$eval(selector, (el, v) => {
    const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : el.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v)
    el.dispatchEvent(new Event(el.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true }))
  }, value)
}

/** 依選項顯示文字挑選 <select>。 */
export async function selectByText(page, selector, text) {
  // 選項是非同步載入的，先等它出現
  await page.waitForFunction((s, t) => [...(document.querySelector(s)?.options ?? [])].some((o) => o.textContent.includes(t)), {}, selector, text)
  const value = await page.$eval(selector, (el, t) => [...el.options].find((o) => o.textContent.includes(t)).value, text)
  await setValue(page, selector, value)
}

export async function newPage(context, { viewport } = {}) {
  const page = await context.newPage()
  page.setDefaultTimeout(15_000)
  if (viewport) await page.setViewport(viewport)
  const tracker = await trackPageErrors(page)
  return { page, tracker }
}

/** 台北時區的日期（YYYY-MM-DD），offsetDays 為相對今天的天數（可為負）。 */
export function taipeiDate(offsetDays = 0) {
  const fmt = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Taipei', year: 'numeric', month: '2-digit', day: '2-digit' })
  return fmt.format(new Date(Date.now() + offsetDays * 86_400_000))
}

export const noHorizontalOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)

/** 在頁面上模擬「回到視窗」：觸發 focus 與 visibilitychange。 */
export async function simulateReturnToWindow(page) {
  await page.bringToFront()
  await page.evaluate(() => {
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange'))
  })
}

/** 更正表單：設定日期後，等表單向後端取回「該日目前資料」並完成預填，再填其他欄位（否則會被預填覆蓋）。 */
export async function setCorrectionDate(page, date) {
  await setValue(page, '#corr-date', date)
  await new Promise((r) => setTimeout(r, 200))
  await page.waitForFunction(() => document.querySelector('[data-testid=corr-original]')?.dataset.state === 'ready')
}
