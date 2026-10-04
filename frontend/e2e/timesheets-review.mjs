// T04 第 1 輪複審 R1／R2 的回歸測試，在【隔離堆疊】執行（測試資料庫＋本次專用帳號，不寫入開發資料庫）。
// R1：新增的結果不明時，requestId 必須與「當時送出的內容」綁在一起——不能讓使用者改了內容再用同一個 requestId 重送、
//     然後被「已建立」的回應清掉新內容；送出中也不能產生稍後被清掉的新編輯。
// R2：送出後才發現專案停用、背景更新專案清單又失敗時，表單（草稿、提交快照、requestId）不能被卸載。
// 事件順序用請求攔截安排（扣住、放行、偽造、中斷、回應遺失），不靠 sleep 碰運氣。
// 執行：cd frontend; npm run e2e:timesheets-review
import { createRunner, launchBrowser, loginAs } from './helpers.mjs'
import { startIsolatedStack } from './isolated-stack.mjs'
import { hasButton, newPage, selectByText, setValue, taipeiDate } from './ui.mjs'

const { check, finish } = createRunner()
let stack
let browser
const DESKTOP = { width: 1200, height: 900 }
const json = (status, body) => ({ status, contentType: 'application/json', body: typeof body === 'string' ? body : JSON.stringify(body) })
const settle = (page, ms = 600) => page.evaluate((t) => new Promise((r) => setTimeout(r, t)), ms)
const soft = (p) => p.then(() => true, () => false)

/** 一個情境中途出錯時記錄為失敗、繼續下一個情境（讓修正前的版本也能逐項顯示哪裡失敗）。 */
async function scenario(name, fn) {
  try {
    await fn()
  } catch (error) {
    check(`${name}：情境執行完成`, false, String(error?.message ?? error).split('\n')[0])
  }
}

async function controlRequests(page) {
  const ctl = { hold: new Set(), held: [], counts: { create: 0, projects: 0 }, createBodies: [], mode: { create: 'pass', projects: 'pass' } }
  await page.setRequestInterception(true)
  page.on('request', async (r) => {
    if (r.isInterceptResolutionHandled()) return
    const url = new URL(r.url())
    const m = r.method()
    let key = null
    if (m === 'POST' && url.pathname === '/api/timesheets/me') key = 'create'
    else if (m === 'GET' && url.pathname === '/api/projects') key = 'projects'
    if (key) ctl.counts[key]++
    if (key === 'create') ctl.createBodies.push(JSON.parse(r.postData() ?? '{}'))
    if (key === 'projects' && ctl.mode.projects === 'abort') return r.abort('failed')
    if (key === 'create' && ctl.mode.create === 'abort') {
      ctl.mode.create = 'pass'
      return r.abort('failed') // 請求沒有到達伺服器
    }
    if (key === 'create' && ctl.mode.create === 'lost-response') {
      // 回應遺失：伺服器其實已建立，但瀏覽器收不到回應
      ctl.mode.create = 'pass'
      const cookies = await page.cookies()
      const headers = { ...r.headers(), cookie: cookies.map((c) => `${c.name}=${c.value}`).join('; ') }
      delete headers.host
      delete headers['content-length']
      await fetch(r.url(), { method: 'POST', headers, body: r.postData() })
      return r.abort('failed')
    }
    if (key && ctl.hold.has(key)) {
      ctl.held.push({ key, request: r })
      return
    }
    void r.continue()
  })
  ctl.waitHeld = async (key, n) => {
    const deadline = Date.now() + 15_000
    while (ctl.held.filter((h) => h.key === key).length < n) {
      if (Date.now() > deadline) throw new Error(`等不到第 ${n} 個 ${key} 請求`)
      await new Promise((r) => setTimeout(r, 50))
    }
  }
  ctl.take = (key, index = 0) => {
    const item = ctl.held.filter((h) => h.key === key)[index]
    ctl.held.splice(ctl.held.indexOf(item), 1)
    return item.request
  }
  return ctl
}

const value = (page, selector) => page.$eval(selector, (el) => el.value).catch(() => null)
const rowsWith = (page, text) =>
  page.$$eval('[data-testid=timesheet-table] tbody tr', (trs, t) => trs.filter((tr) => tr.innerText.includes(t)).map((tr) => ({ id: tr.dataset.entryId, minutes: Number(tr.dataset.minutes) })), text)
const waitRow = (page, text) =>
  page.waitForFunction((t) => [...document.querySelectorAll('[data-testid=timesheet-table] tbody tr')].some((tr) => tr.innerText.includes(t)), { timeout: 10_000 }, text)
const clickButton = (page, label) =>
  page.evaluate((l) => {
    const b = [...document.querySelectorAll('button')].find((x) => x.textContent.trim() === l)
    if (!b) throw new Error(`找不到按鈕 ${l}`)
    b.click()
  }, label)
/** 修正後的「重新送出原內容」；修正前只有「送出」。 */
const resend = async (page) => clickButton(page, (await hasButton(page, '重新送出原內容')) ? '重新送出原內容' : '送出')

async function fillEntry(page, { date, project, hours, description }) {
  await page.waitForSelector('[data-testid=entry-form][data-mode=new]')
  await setValue(page, '#ts-date', date)
  await selectByText(page, '#ts-project', project)
  await setValue(page, '#ts-hours', hours)
  await setValue(page, '#ts-description', description)
}

async function adminApi(page, method, path, body) {
  return page.evaluate(async (m, p, b) => {
    const token = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '')
    const res = await fetch(p, { method: m, headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: b ? JSON.stringify(b) : undefined })
    return { status: res.status, body: await res.json() }
  }, method, path, body)
}

try {
  stack = await startIsolatedStack()
  console.log(`隔離堆疊已啟動：${stack.baseUrl}（測試資料庫；本次專用帳號 ${stack.users.a.username} 等）`)
  browser = await launchBrowser()
  const base = stack.baseUrl
  const run = stack.runId.toUpperCase()
  const CODE_A = `RV_${run}_A`
  const CODE_B = `RV_${run}_B`
  const today = taipeiDate(0)
  const d = (n) => taipeiDate(-n)

  // 準備：管理員建立兩個專案
  const ctxAdmin = await browser.createBrowserContext()
  const admin = await newPage(ctxAdmin, { viewport: DESKTOP })
  await loginAs(admin.page, stack.users.admin.username, stack.users.admin.password, base)
  const projectB = (await adminApi(admin.page, 'POST', '/api/admin/projects', { code: CODE_B, name: 'R2 會被停用的專案', description: null })).body
  check('準備：建立兩個專案', (await adminApi(admin.page, 'POST', '/api/admin/projects', { code: CODE_A, name: '一般專案', description: null })).status === 201 && Boolean(projectB.id))

  const ctx = await browser.createBrowserContext()
  const A = await newPage(ctx, { viewport: DESKTOP })
  const ctl = await controlRequests(A.page)
  const page = A.page
  await loginAs(page, stack.users.a.username, stack.users.a.password, base)
  await page.click('a[href="#/timesheets"]')
  await page.waitForSelector('[data-testid=entry-form][data-mode=new]')
  await setValue(page, '#ts-from', d(10))
  await setValue(page, '#ts-to', today)
  await clickButton(page, '查詢')

  // ================= R1-1：回應遺失 → 表單鎖定在原提交；重送同一份內容 → 已建立；之後以正常修改流程改成新內容 =================
  await scenario('R1-1', async () => {
    ctl.mode.create = 'lost-response'
    await fillEntry(page, { date: d(1), project: CODE_A, hours: '1.5', description: 'R1 回應遺失的那一筆' })
    await clickButton(page, '送出')
    await soft(page.waitForFunction(() => document.querySelector('[data-testid=entry-error]')?.textContent.includes('無法確認')))
    // 使用者想把 1.5 小時改成 2 小時、並改內容（修正前：可以改，之後被「已建立」清掉）
    await setValue(page, '#ts-hours', '2')
    await setValue(page, '#ts-description', 'R1 送出後才改的內容')
    check('R1-1 結果不明期間，表單鎖定在原提交內容（不能改成 2 小時或改內容）',
      (await value(page, '#ts-hours')) === '1.5' && (await value(page, '#ts-description')) === 'R1 回應遺失的那一筆',
      JSON.stringify({ hours: await value(page, '#ts-hours'), desc: await value(page, '#ts-description') }))
    check('R1-1 結果不明時離開 loading：可以重新送出', !(await page.$eval('button[type=submit]', (b) => b.disabled)))
    await resend(page)
    await soft(page.waitForFunction(() => document.querySelector('[data-testid=timesheet-notice]')?.textContent.includes('已經建立')))
    const bodies = ctl.createBodies.slice(-2)
    check('R1-1 重送沿用同一個 requestId，而且內容與原提交完全相同',
      bodies.length === 2 && bodies[0].requestId === bodies[1].requestId && bodies[1].minutes === 90 && bodies[1].description === 'R1 回應遺失的那一筆',
      JSON.stringify(bodies.map((b) => [b.requestId, b.minutes, b.description])))
    await soft(waitRow(page, 'R1 回應遺失的那一筆'))
    const saved = await rowsWith(page, 'R1 回應遺失的那一筆')
    check('R1-1 後端只有一筆，內容是原提交（90 分鐘）', saved.length === 1 && saved[0].minutes === 90, JSON.stringify(saved))
    check('R1-1 沒有任何「送出後才改的內容」被誤報為已儲存', (await rowsWith(page, 'R1 送出後才改的內容')).length === 0
      && !(await page.$eval('[data-testid=timesheet-notice]', (el) => el.textContent).catch(() => '')).includes('已新增'))
    // 原提交確認後，用正常修改流程改成新內容
    await page.click(`tr[data-entry-id="${saved[0].id}"] button`)
    await page.waitForSelector('[data-testid=entry-form][data-mode=edit]')
    await setValue(page, '#ts-hours', '2')
    await setValue(page, '#ts-description', 'R1 確認後修改的內容')
    await clickButton(page, '送出')
    await waitRow(page, 'R1 確認後修改的內容')
    check('R1-1 原提交確認後，可以透過修改流程改成 2 小時', (await rowsWith(page, 'R1 確認後修改的內容'))[0]?.minutes === 120)
  })

  // ================= R1-2：送出中不能編輯（否則成功回應會清掉送出後才輸入的內容） =================
  await scenario('R1-2', async () => {
    await page.waitForSelector('[data-testid=entry-form][data-mode=new]')
    ctl.hold.add('create')
    await fillEntry(page, { date: d(2), project: CODE_A, hours: '1', description: 'R1 送出中' })
    await clickButton(page, '送出')
    await ctl.waitHeld('create', 1)
    await setValue(page, '#ts-hours', '3')
    await setValue(page, '#ts-description', 'R1 送出中又改了')
    check('R1-2 送出中表單不可編輯（輸入不會被接受）',
      (await value(page, '#ts-hours')) === '1' && (await value(page, '#ts-description')) === 'R1 送出中')
    ctl.hold.delete('create')
    await ctl.take('create', 0).continue()
    await waitRow(page, 'R1 送出中')
    check('R1-2 送出成功後以原內容建立（1 小時）', (await rowsWith(page, 'R1 送出中'))[0]?.minutes === 60)
  })

  // ================= R1-3：明確拒絕（每日上限）後可以修正再送 =================
  await scenario('R1-3', async () => {
    await fillEntry(page, { date: d(3), project: CODE_A, hours: '23', description: 'R1 先填 23 小時' })
    await clickButton(page, '送出')
    await waitRow(page, 'R1 先填 23 小時')
    await fillEntry(page, { date: d(3), project: CODE_A, hours: '2', description: 'R1 超過上限' })
    await clickButton(page, '送出')
    await page.waitForFunction(() => document.querySelector('[data-testid=entry-error]')?.textContent.includes('24 小時上限'))
    await setValue(page, '#ts-hours', '1')
    check('R1-3 明確拒絕後表單可以修正（改成 1 小時）', (await value(page, '#ts-hours')) === '1')
    await clickButton(page, '送出')
    await waitRow(page, 'R1 超過上限')
    check('R1-3 修正後再送成功', (await rowsWith(page, 'R1 超過上限'))[0]?.minutes === 60)
  })

  // ================= R1-4：網路錯誤（請求沒到伺服器）→ 離開 loading，重送原內容後建立 =================
  await scenario('R1-4', async () => {
    ctl.mode.create = 'abort'
    await fillEntry(page, { date: d(4), project: CODE_A, hours: '0.5', description: 'R1 網路錯誤' })
    await clickButton(page, '送出')
    await page.waitForFunction(() => document.querySelector('[data-testid=entry-error]')?.textContent.includes('無法確認'))
    check('R1-4 網路錯誤後離開 loading（按鈕可再按）', !(await page.$eval('button[type=submit]', (b) => b.disabled)))
    await resend(page)
    await waitRow(page, 'R1 網路錯誤')
    check('R1-4 重送原內容後建立一筆', (await rowsWith(page, 'R1 網路錯誤')).length === 1)
  })

  // ================= R1-5：放棄追蹤原提交 → 明確警告後才清空，下一筆使用新的 requestId =================
  await scenario('R1-5', async () => {
    ctl.mode.create = 'lost-response'
    await fillEntry(page, { date: d(5), project: CODE_A, hours: '0.25', description: 'R1 放棄追蹤' })
    await clickButton(page, '送出')
    await page.waitForFunction(() => document.querySelector('[data-testid=entry-error]')?.textContent.includes('無法確認'))
    const abandonedId = ctl.createBodies.at(-1).requestId
    await clickButton(page, '放棄追蹤，開始新的一筆')
    await page.waitForSelector('[data-testid=abandon-warning]')
    check('R1-5 放棄前警告：原提交可能已經建立', (await page.$eval('[data-testid=abandon-warning]', (el) => el.textContent)).includes('可能已經建立'))
    await clickButton(page, '確定放棄並清空')
    await page.waitForFunction(() => document.querySelector('#ts-description')?.value === '')
    await fillEntry(page, { date: d(5), project: CODE_A, hours: '0.25', description: 'R1 放棄後的新一筆' })
    await clickButton(page, '送出')
    await waitRow(page, 'R1 放棄後的新一筆')
    check('R1-5 放棄後的新一筆使用新的 requestId', ctl.createBodies.at(-1).requestId !== abandonedId)
  })

  // ================= R2：送出後才發現專案停用，背景更新專案清單又失敗 → 表單不卸載 =================
  await scenario('R2', async () => {
    await page.waitForSelector('[data-testid=entry-form][data-mode=new]')
    await fillEntry(page, { date: d(6), project: CODE_B, hours: '1.25', description: 'R2 草稿' })
    const deactivate = await adminApi(admin.page, 'PUT', `/api/admin/projects/${projectB.id}`, { name: projectB.name, description: null, active: false, expectedVersion: projectB.version })
    check('R2 準備：管理員停用專案 B', deactivate.status === 200)
    ctl.mode.projects = 'abort' // 背景更新專案清單會遇到網路錯誤
    const createsBefore = ctl.createBodies.length
    await clickButton(page, '送出')
    await soft(page.waitForFunction(() => document.querySelector('[data-testid=entry-error]')?.textContent.includes('已停用')))
    await settle(page, 800)
    const draft = { date: await value(page, '#ts-date'), project: await value(page, '#ts-project'), hours: await value(page, '#ts-hours'), desc: await value(page, '#ts-description') }
    check('R2 背景更新失敗後表單仍在，草稿完整保留（日期、專案、時數、內容）',
      draft.date === d(6) && draft.project === String(projectB.id) && draft.hours === '1.25' && draft.desc === 'R2 草稿', JSON.stringify(draft))
    check('R2 原本的送出錯誤（專案已停用）沒有消失', (await page.$eval('[data-testid=entry-error]', (el) => el.textContent).catch(() => '')).includes('已停用'))
    check('R2 顯示獨立的「專案清單更新失敗」與重試', (await page.$('[data-testid=projects-refresh-error]')) !== null)

    ctl.mode.projects = 'pass'
    await clickButton(page, '重試更新專案清單')
    await page.waitForFunction(() => !document.querySelector('[data-testid=projects-refresh-error]'))
    const unavailable = await page.$('[data-testid=project-unavailable]')
    const selected = await page.$eval('#ts-project', (el) => el.options[el.selectedIndex]?.textContent ?? '')
    check('R2 重試成功後：仍選著 B，並明確標示「無法選擇」而不是偷偷改選其他專案',
      unavailable !== null && (await value(page, '#ts-project')) === String(projectB.id) && selected.includes(CODE_B), selected)
    check('R2 重試後草稿仍在', (await value(page, '#ts-hours')) === '1.25' && (await value(page, '#ts-description')) === 'R2 草稿')

    // 舊的背景更新回應晚到（還列著 B）不能覆蓋較新的清單
    ctl.hold.add('projects')
    await clickButton(page, '重新整理專案清單')
    await ctl.waitHeld('projects', 1)
    await clickButton(page, '重新整理專案清單')
    await ctl.waitHeld('projects', 2)
    await ctl.take('projects', 1).continue()
    const stale = ctl.take('projects', 0)
    await stale.respond(json(200, { projects: [{ id: projectB.id, code: CODE_B, name: 'R2 會被停用的專案', description: null }] }))
    ctl.hold.delete('projects')
    await settle(page)
    const normalB = await page.$$eval('#ts-project option', (os, c) => os.filter((o) => o.textContent.includes(c) && !o.disabled).length, CODE_B)
    check('R2 舊的專案清單回應晚到 → 不覆蓋較新的清單（B 不會變回可選）', normalB === 0)

    // 改選 A 後送出：同一個邏輯提交（先前被明確拒絕，沒有建立）沿用同一個 requestId
    await selectByText(page, '#ts-project', CODE_A)
    await clickButton(page, '送出')
    await waitRow(page, 'R2 草稿')
    const after = ctl.createBodies.slice(createsBefore)
    check('R2 改選啟用專案後送出成功；草稿與 requestId 一路保留',
      after.length === 2 && after[0].requestId === after[1].requestId && after[1].projectId !== projectB.id, JSON.stringify(after.map((b) => [b.requestId, b.projectId])))
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
