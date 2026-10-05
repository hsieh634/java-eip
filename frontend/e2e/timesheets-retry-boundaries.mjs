// T04 第 2 輪複審：R1 重試邊界（R1-A、R1-B）的回歸測試，在【隔離堆疊】執行（測試資料庫＋本次專用帳號）。
// R1-A：原提交結果不明時，重試失敗（例如 403、專案停用）只代表「這一次」被拒絕，不能證明原提交沒有成功——
//       快照、requestId 與鎖定都要保留，直到確認建立或明確放棄。
// R1-B：原提交結果不明時，即使啟用專案清單變空，也要能「重新送出原內容」確認。
// 事件順序用請求攔截安排（回應遺失、中斷、偽造 403、空的專案清單），並以 API 確認後端實際資料。
// 執行：cd frontend; npm run e2e:timesheets-retry
import { createRunner, launchBrowser, loginAs } from './helpers.mjs'
import { startIsolatedStack } from './isolated-stack.mjs'
import { hasButton, newPage, selectByText, setValue, taipeiDate } from './ui.mjs'

const { check, finish } = createRunner()
let stack
let browser
const DESKTOP = { width: 1200, height: 900 }
const json = (status, body) => ({ status, contentType: 'application/json', body: typeof body === 'string' ? body : JSON.stringify(body) })
const soft = (p) => p.then(() => true, () => false)

async function scenario(name, fn) {
  try {
    await fn()
  } catch (error) {
    check(`${name}：情境執行完成`, false, String(error?.message ?? error).split('\n')[0])
  }
}

async function controlRequests(page) {
  const ctl = { createBodies: [], mode: { create: [], projects: 'pass' } }
  await page.setRequestInterception(true)
  page.on('request', async (r) => {
    if (r.isInterceptResolutionHandled()) return
    const url = new URL(r.url())
    const m = r.method()
    if (m === 'GET' && url.pathname === '/api/projects' && ctl.mode.projects === 'empty') return r.respond(json(200, { projects: [] }))
    if (m === 'POST' && url.pathname === '/api/timesheets/me') {
      ctl.createBodies.push(r.postData())
      const next = ctl.mode.create.shift() ?? 'pass' // 依序消耗：每次新增請求套用一個模式
      if (next === 'lost-response') {
        // 回應遺失：轉送給伺服器（真的建立），但瀏覽器收到網路錯誤
        const cookies = await page.cookies()
        const headers = { ...r.headers(), cookie: cookies.map((c) => `${c.name}=${c.value}`).join('; ') }
        delete headers.host
        delete headers['content-length']
        const res = await fetch(r.url(), { method: 'POST', headers, body: r.postData() })
        ctl.lastForwardedStatus = res.status
        return r.abort('failed')
      }
      if (next === 'abort') return r.abort('failed') // 沒有到達伺服器
      if (next === 'forbidden') return r.respond(json(403, { code: 'FORBIDDEN', message: '安全驗證失敗，請重新整理頁面' }))
    }
    void r.continue()
  })
  return ctl
}

const value = (page, selector) => page.$eval(selector, (el) => el.value).catch(() => null)
const formValues = async (page) => ({
  date: await value(page, '#ts-date'), project: await value(page, '#ts-project'), hours: await value(page, '#ts-hours'), desc: await value(page, '#ts-description'),
})
const submitButton = (page) => page.$eval('button[type=submit]', (b) => ({ text: b.textContent.trim(), disabled: b.disabled }))
const clickButton = (page, label) =>
  page.evaluate((l) => {
    const b = [...document.querySelectorAll('button')].find((x) => x.textContent.trim() === l)
    if (!b) throw new Error(`找不到按鈕 ${l}`)
    b.click()
  }, label)
const errorText = (page) => page.$eval('[data-testid=entry-error]', (el) => el.textContent).catch(() => '')
/** 等待請求結束（送出按鈕不是「送出中…」）。 */
const waitIdle = (page) => page.waitForFunction(() => document.querySelector('button[type=submit]')?.textContent.trim() !== '送出中…', { timeout: 10_000 })
/** 修正後的「重新送出原內容」；修正前沒有時改按「送出」，讓舊版本也能逐項顯示失敗。 */
const resend = async (page) => clickButton(page, (await hasButton(page, '重新送出原內容')) ? '重新送出原內容' : '送出')

async function fillEntry(page, { date, project, hours, description }) {
  await page.waitForSelector('[data-testid=entry-form][data-mode=new]')
  await setValue(page, '#ts-date', date)
  await selectByText(page, '#ts-project', project)
  await setValue(page, '#ts-hours', hours)
  await setValue(page, '#ts-description', description)
}

async function api(page, method, path, body) {
  return page.evaluate(async (m, p, b) => {
    const token = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '')
    const res = await fetch(p, { method: m, headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: b ? JSON.stringify(b) : undefined })
    return { status: res.status, body: await res.json() }
  }, method, path, body)
}

/** 以員工 A 的身分查後端實際資料（不經畫面）。 */
async function entriesWith(page, text, from, to) {
  const res = await api(page, 'GET', `/api/timesheets/me?from=${from}&to=${to}`)
  return res.body.entries.filter((e) => e.description === text)
}

try {
  stack = await startIsolatedStack()
  console.log(`隔離堆疊已啟動：${stack.baseUrl}（測試資料庫；本次專用帳號 ${stack.users.a.username} 等）`)
  browser = await launchBrowser()
  const base = stack.baseUrl
  const run = stack.runId.toUpperCase()
  const today = taipeiDate(0)
  const d = (n) => taipeiDate(-n)
  const from = d(10)

  const ctxAdmin = await browser.createBrowserContext()
  const admin = await newPage(ctxAdmin, { viewport: DESKTOP })
  await loginAs(admin.page, stack.users.admin.username, stack.users.admin.password, base)
  const created = {}
  for (const key of ['A', 'B', 'C']) {
    created[key] = (await api(admin.page, 'POST', '/api/admin/projects', { code: `RT_${run}_${key}`, name: `重試邊界 ${key}`, description: null })).body
  }
  check('準備：建立三個專案', Object.values(created).every((p) => p.id))
  const deactivate = async (p) => api(admin.page, 'PUT', `/api/admin/projects/${p.id}`, { name: p.name, description: null, active: false, expectedVersion: p.version })

  const ctx = await browser.createBrowserContext()
  const A = await newPage(ctx, { viewport: DESKTOP })
  const ctl = await controlRequests(A.page)
  const page = A.page
  await loginAs(page, stack.users.a.username, stack.users.a.password, base)
  await page.click('a[href="#/timesheets"]')
  await page.waitForSelector('[data-testid=entry-form][data-mode=new]')

  // ================= A：原提交成功、回應遺失 → 重試 403 → 再重試確認 =================
  await scenario('A', async () => {
    const desc = `A 原提交 ${run}`
    ctl.mode.create.push('lost-response', 'forbidden')
    await fillEntry(page, { date: d(1), project: `RT_${run}_A`, hours: '1.5', description: desc })
    await clickButton(page, '送出')
    await page.waitForFunction(() => document.querySelector('[data-testid=entry-error]')?.textContent.includes('無法確認'))
    check('A 後端確實已建立原紀錄（回應遺失前已寫入）', ctl.lastForwardedStatus === 201 && (await entriesWith(page, desc, from, today)).length === 1)
    const original = await formValues(page)

    await resend(page) // 第一次重試 → 403
    await waitIdle(page)
    const afterForbidden = await formValues(page)
    const button = await submitButton(page)
    check('A 403 後離開 loading（按鈕可以再按）', !button.disabled, JSON.stringify(button))
    check('A 403 後仍是「原提交未確認」：主要按鈕仍是「重新送出原內容」，提示同時說明本次原因與仍未確認',
      button.text === '重新送出原內容' && (await errorText(page)).includes('安全驗證失敗') && (await errorText(page)).includes('仍未確認'), `${button.text}｜${await errorText(page)}`)
    check('A 403 後日期、專案、分鐘、內容完全不變', JSON.stringify(afterForbidden) === JSON.stringify(original), JSON.stringify({ original, afterForbidden }))

    await setValue(page, '#ts-hours', '2')
    await setValue(page, '#ts-description', 'A 想改成的新內容')
    check('A 403 後嘗試修改（2 小時、新內容）不被接受', JSON.stringify(await formValues(page)) === JSON.stringify(original))

    await resend(page) // 再次重試 → 真的送到後端 → 已建立
    await soft(page.waitForFunction(() => document.querySelector('[data-testid=timesheet-notice]')?.textContent.includes('已經建立')))
    const bodies = ctl.createBodies.slice(-3)
    check('A 三次送出（原提交、403 的重試、再次重試）的 payload 完全相同（含 requestId）',
      bodies.length === 3 && bodies.every((b) => b === bodies[0]), bodies.map((b) => JSON.parse(b)).map((b) => `${b.requestId.slice(0, 8)}/${b.minutes}/${b.description}`).join(' | '))
    const saved = await entriesWith(page, desc, from, today)
    check('A 後端只有一筆原紀錄（90 分），沒有新內容被誤報為已儲存',
      saved.length === 1 && saved[0].minutes === 90 && (await entriesWith(page, 'A 想改成的新內容', from, today)).length === 0, JSON.stringify(saved))

    // 確認之後，用正常修改流程改成 120 分
    await page.waitForFunction((id) => document.querySelector(`tr[data-entry-id="${id}"]`) !== null, {}, String(saved[0].id))
    await page.click(`tr[data-entry-id="${saved[0].id}"] button`)
    await page.waitForSelector('[data-testid=entry-form][data-mode=edit]')
    await setValue(page, '#ts-hours', '2')
    await clickButton(page, '送出')
    await page.waitForFunction(() => document.querySelector('[data-testid=entry-form][data-mode=new]') !== null)
    check('A 確認後可以用修改流程改成 120 分', (await entriesWith(page, desc, from, today))[0]?.minutes === 120)
  })

  // ================= B：原提交未到達後端 → 專案被停用 → 重試得 PROJECT_INACTIVE =================
  await scenario('B', async () => {
    const desc = `B 原提交 ${run}`
    await page.waitForSelector('[data-testid=entry-form][data-mode=new]')
    ctl.mode.create.push('abort')
    await fillEntry(page, { date: d(2), project: `RT_${run}_B`, hours: '0.75', description: desc })
    await clickButton(page, '送出')
    await page.waitForFunction(() => document.querySelector('[data-testid=entry-error]')?.textContent.includes('無法確認'))
    const original = await formValues(page)
    check('B 準備：管理員停用專案 B', (await deactivate(created.B)).status === 200)

    await resend(page) // 真的送到後端 → 409 PROJECT_INACTIVE
    await waitIdle(page)
    const button = await submitButton(page)
    check('B 重試得到「專案已停用」後仍未確認：按鈕可按、仍是「重新送出原內容」、提示說明仍未確認',
      !button.disabled && button.text === '重新送出原內容' && (await errorText(page)).includes('已停用') && (await errorText(page)).includes('仍未確認'), `${button.text}｜${await errorText(page)}`)
    check('B 快照保留（日期、專案、分鐘、內容不變）', JSON.stringify(await formValues(page)) === JSON.stringify(original), JSON.stringify(await formValues(page)))
    await setValue(page, '#ts-hours', '1')
    check('B 修改嘗試不被接受', (await value(page, '#ts-hours')) === original.hours)
    const bodies = ctl.createBodies.slice(-2)
    check('B 重試沿用同一個 requestId 與完全相同的內容（沒有自行換 requestId）', bodies.length === 2 && bodies[0] === bodies[1])
    check('B 後端沒有建立任何紀錄', (await entriesWith(page, desc, from, today)).length === 0)

    // 明確放棄：先警告，再清空；之後的新提交使用新的 requestId
    const oldRequestId = JSON.parse(bodies[1]).requestId
    await clickButton(page, '放棄追蹤，開始新的一筆')
    await page.waitForSelector('[data-testid=abandon-warning]')
    check('B 放棄前警告：原提交可能已經建立', (await page.$eval('[data-testid=abandon-warning]', (el) => el.textContent)).includes('可能已經建立'))
    await clickButton(page, '確定放棄並清空')
    await page.waitForFunction(() => document.querySelector('#ts-description')?.value === '')
    await fillEntry(page, { date: d(2), project: `RT_${run}_A`, hours: '0.75', description: `B 放棄後改填 ${run}` })
    await clickButton(page, '送出')
    await page.waitForFunction(() => document.querySelector('[data-testid=timesheet-notice]')?.textContent.includes('已新增'))
    check('B 放棄後的新提交使用新的 requestId', JSON.parse(ctl.createBodies.at(-1)).requestId !== oldRequestId)
  })

  // ================= C：原提交成功、回應遺失 → 啟用專案清單變空 → 仍可確認原提交 =================
  await scenario('C', async () => {
    const desc = `C 原提交 ${run}`
    await page.waitForSelector('[data-testid=entry-form][data-mode=new]')
    ctl.mode.create.push('lost-response')
    await fillEntry(page, { date: d(3), project: `RT_${run}_C`, hours: '0.5', description: desc })
    await clickButton(page, '送出')
    await page.waitForFunction(() => document.querySelector('[data-testid=entry-error]')?.textContent.includes('無法確認'))
    const original = await formValues(page)
    check('C 準備：後端已建立原紀錄；管理員停用專案 A、C（最後的啟用專案）',
      (await entriesWith(page, desc, from, today)).length === 1 && (await deactivate(created.C)).status === 200 && (await deactivate(created.A)).status === 200)
    // 測試庫可能留有其他執行的專案，所以以攔截模擬「啟用專案清單為空」
    ctl.mode.projects = 'empty'
    await clickButton(page, '重新整理專案清單')
    await page.waitForSelector('[data-testid=no-projects]')
    const button = await submitButton(page)
    check('C 清單變空後快照仍在', JSON.stringify(await formValues(page)) === JSON.stringify(original), JSON.stringify(await formValues(page)))
    check('C 清單為空時「重新送出原內容」仍可操作', button.text === '重新送出原內容' && !button.disabled, JSON.stringify(button))
    check('C 提示不矛盾：說明不能新增新工時，但可以確認上一筆', (await page.$eval('[data-testid=no-projects]', (el) => el.textContent)).includes('確認'))

    await resend(page)
    await soft(page.waitForFunction(() => document.querySelector('[data-testid=timesheet-notice]')?.textContent.includes('已經建立')))
    check('C 確認原提交已建立，後端只有一筆', (await entriesWith(page, desc, from, today)).length === 1 && (await page.$('[data-testid=entry-form][data-phase=editing]')) !== null)
    const last = ctl.createBodies.slice(-2)
    check('C 重試送的是原快照（同一 requestId、同一專案，不依目前的空清單重組）', last[0] === last[1])
    const newButton = await submitButton(page)
    check('C 確認後的新表單：沒有啟用專案 → 送出停用', newButton.text === '送出' && newButton.disabled, JSON.stringify(newButton))
  })

  const rejections = await A.tracker.unhandledRejections()
  check('全程：沒有未處理的 Promise 拒絕與 JS 例外', rejections.length === 0 && A.tracker.pageErrors.length === 0, [...rejections, ...A.tracker.pageErrors].join(' | '))
} catch (error) {
  check('腳本執行未中斷', false, String(error?.stack ?? error))
} finally {
  await browser?.close()
  stack?.stop()
}

finish()
