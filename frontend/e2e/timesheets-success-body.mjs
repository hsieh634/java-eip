// BUG-TP-001：真正隔離後端寫入＋瀏覽器成功本文故障；json() 讀取失敗為受控替身。
import { createRunner, launchBrowser, loginAs } from './helpers.mjs'
import { startIsolatedStack } from './isolated-stack.mjs'
import { newPage, selectByText, setValue, taipeiDate } from './ui.mjs'
const { check, finish } = createRunner()
let stack, browser
const call = (page, method, path, body) => page.evaluate(async (m, p, b) => {
  const token = decodeURIComponent(document.cookie.split('; ').find(x => x.startsWith('XSRF-TOKEN='))?.slice(11) ?? '')
  const r = await fetch(p, { method: m, headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: b ? JSON.stringify(b) : undefined })
  return { status: r.status, body: await r.json() }
}, method, path, body)
try {
  stack = await startIsolatedStack()
  browser = await launchBrowser()
  const context = await browser.createBrowserContext()
  let { page } = await newPage(context, { viewport: { width: 1200, height: 900 } })
  await loginAs(page, stack.users.admin.username, stack.users.admin.password, stack.baseUrl)
  const code = `BODY_${stack.runId.toUpperCase()}`
  const project = await call(page, 'POST', '/api/admin/projects', { code, name: '成功本文回歸', description: null })
  check('專用專案建立', project.status === 201)
  const employeeContext = await browser.createBrowserContext()
  ;({ page } = await newPage(employeeContext, { viewport: { width: 1200, height: 900 } }))
  await loginAs(page, stack.users.a.username, stack.users.a.password, stack.baseUrl)
  await page.click('a[href="#/timesheets"]')
  await page.waitForSelector('[data-testid=entry-form]')
  for (const mode of ['json', 'read', 'structure', 'mismatch']) {
    const bodies = []
    await page.evaluate((mode) => {
      const original = window.fetch
      window.fetch = async (...args) => {
        const result = await original(...args)
        if (args[0] === '/api/timesheets/me' && args[1]?.method === 'POST' && result.ok) {
          window.fetch = original
          if (mode === 'read') result.json = async () => { throw new TypeError('controlled body read failure') }
          else if (mode === 'json') result.json = async () => JSON.parse('{broken')
          else if (mode === 'structure') result.json = async () => ({ id: 1 })
          else {
            const json = result.json.bind(result)
            result.json = async () => ({ ...await json(), minutes: 1440 })
          }
        }
        return result
      }
    }, mode)
    const track = r => { if (r.method() === 'POST' && new URL(r.url()).pathname === '/api/timesheets/me') bodies.push(r.postData()) }
    page.on('request', track)
    await setValue(page, '#ts-date', taipeiDate(-1))
    await selectByText(page, '#ts-project', code)
    await setValue(page, '#ts-hours', '1.5')
    const description = `BUG-TP-001 ${mode} ${stack.runId}`
    await setValue(page, '#ts-description', description)
    await page.$eval('[data-testid=entry-form] button[type=submit]', b => b.click())
    await page.waitForFunction(() => document.querySelector('[data-testid=entry-form]')?.getAttribute('data-phase') !== 'submitting')
    const locked = await page.$eval('[data-testid=entry-form]', f => f.dataset.phase === 'unknown' && f.dataset.unconfirmed === 'true' && f.querySelector('fieldset').disabled)
    check(`${mode}：成功本文不可用維持原快照與鎖定`, locked)
    const rows = await call(page, 'GET', '/api/timesheets/me')
    check(`${mode}：真實後端已建立一筆`, rows.body.entries.filter(e => e.description === description).length === 1)
    await page.waitForSelector('[data-testid=timesheet-table] button')
    check(`${mode}：不能切換修改其他紀錄而遺失原追蹤`, await page.$$eval('[data-testid=timesheet-table] button', buttons => buttons.every(b => b.disabled)))
    await page.$eval('[data-testid=entry-form] button[type=submit]', b => b.click())
    await page.waitForFunction(() => document.querySelector('[data-testid=entry-form]')?.getAttribute('data-phase') === 'editing')
    check(`${mode}：重送原 requestId 與完整原本文`, bodies.length === 2 && bodies[0] === bodies[1])
    const confirmed = await call(page, 'GET', '/api/timesheets/me')
    check(`${mode}：沒有重複新增`, confirmed.body.entries.filter(e => e.description === description).length === 1)
    page.off('request', track)
  }
} catch (error) {
  check('腳本完整執行', false, String(error?.message ?? error).split('\n')[0])
} finally {
  if (browser) await browser.close()
  if (stack) await stack.stop()
  finish()
}
