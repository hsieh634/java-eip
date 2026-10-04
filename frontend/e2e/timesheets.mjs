// T04：專案管理與個人工時回報的瀏覽器端到端驗證，在【隔離堆疊】執行（測試資料庫＋本次專用帳號，不寫入開發資料庫）。
// 專案代碼含本次執行識別碼，避免與測試庫中先前留下的資料衝突。
// 競態與重複提交用「請求攔截」安排先後（扣住、放行、偽造、中斷），不靠 sleep 碰運氣。
// 執行：cd frontend; npm run e2e:timesheets
import path from 'node:path'
import { OUT, createRunner, launchBrowser, loginAs } from './helpers.mjs'
import { startIsolatedStack } from './isolated-stack.mjs'
import { bodyText, button, click, newPage, noHorizontalOverflow, selectByText, setValue, taipeiDate, waitText } from './ui.mjs'

const { check, finish } = createRunner()
let stack
let browser
const DESKTOP = { width: 1200, height: 900 }
const MOBILE = { width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true }
const json = (status, body) => ({ status, contentType: 'application/json', body: typeof body === 'string' ? body : JSON.stringify(body) })
const settle = (page, ms = 600) => page.evaluate((t) => new Promise((r) => setTimeout(r, t)), ms)

async function assertNoUnhandled(name, trackers) {
  const rejections = (await Promise.all(trackers.map((t) => t.unhandledRejections()))).flat()
  const errors = trackers.flatMap((t) => t.pageErrors)
  check(`${name}：沒有未處理的 Promise 拒絕與 JS 例外`, rejections.length === 0 && errors.length === 0, [...rejections, ...errors].join(' | '))
}

/** 攔截並計數：list（GET 清單）、create（POST）、update（PUT）、projects（GET 可填報專案）；hold 內的種類先扣住。 */
async function controlRequests(page) {
  const ctl = { hold: new Set(), held: [], counts: { list: 0, create: 0, update: 0, projects: 0 }, createBodies: [], mode: { create: 'pass', projects: 'pass' } }
  await page.setRequestInterception(true)
  page.on('request', async (r) => {
    if (r.isInterceptResolutionHandled()) return
    const url = new URL(r.url())
    const m = r.method()
    let key = null
    if (m === 'GET' && url.pathname === '/api/timesheets/me') key = 'list'
    else if (m === 'POST' && url.pathname === '/api/timesheets/me') key = 'create'
    else if (m === 'PUT' && url.pathname.startsWith('/api/timesheets/me/')) key = 'update'
    else if (m === 'GET' && url.pathname === '/api/projects') key = 'projects'
    if (key) ctl.counts[key]++
    if (key === 'create') ctl.createBodies.push(JSON.parse(r.postData() ?? '{}'))
    if (key === 'projects' && ctl.mode.projects === 'empty') return r.respond(json(200, { projects: [] }))
    if (key === 'create' && ctl.mode.create === 'lost-response') {
      // 回應遺失：請求其實送達並被伺服器處理，但瀏覽器收不到回應（網路結果不明）
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

const rows = (page) =>
  page.$$eval('[data-testid=timesheet-table] tbody tr', (trs) => trs.map((tr) => ({ id: tr.dataset.entryId, minutes: Number(tr.dataset.minutes), text: tr.innerText })))
const totalMinutes = (page) => page.$eval('[data-testid=timesheet-total]', (el) => Number(el.dataset.minutes))
const daily = (page) => page.$$eval('[data-testid=timesheet-daily] li', (lis) => Object.fromEntries(lis.map((li) => [li.dataset.date, Number(li.dataset.minutes)])))
const entryError = (page) => page.$eval('[data-testid=entry-error]', (el) => el.textContent)

/** 填寫新增表單並送出。 */
async function fillEntry(page, { date, project, hours, description }) {
  await page.waitForSelector('[data-testid=entry-form][data-mode=new]')
  if (date) await setValue(page, '#ts-date', date)
  await selectByText(page, '#ts-project', project)
  await setValue(page, '#ts-hours', hours)
  await setValue(page, '#ts-description', description)
}

async function query(page, from, to) {
  await page.waitForSelector('#ts-from')
  await setValue(page, '#ts-from', from)
  await setValue(page, '#ts-to', to)
  await click(page, '查詢')
}

try {
  stack = await startIsolatedStack()
  console.log(`隔離堆疊已啟動：${stack.baseUrl}（測試資料庫；本次專用帳號 ${stack.users.a.username} 等）`)
  browser = await launchBrowser()
  const base = stack.baseUrl
  const run = stack.runId.toUpperCase()
  const CODE_A = `E2E_${run}_A`
  const CODE_B = `E2E_${run}_B`
  const today = taipeiDate(0)
  const yesterday = taipeiDate(-1)

  // ================= P1：管理員建立專案 =================
  const ctxAdmin = await browser.createBrowserContext()
  const admin = await newPage(ctxAdmin, { viewport: DESKTOP })
  {
    const page = admin.page
    await loginAs(page, stack.users.admin.username, stack.users.admin.password, base)
    check('P1 ADMIN 看得到「專案管理」入口', (await page.$('a[href="#/admin/projects"]')) !== null)
    await page.click('a[href="#/admin/projects"]')
    await page.waitForSelector('[data-testid=create-project-form]')
    await setValue(page, '#pj-code', 'bad code!')
    await setValue(page, '#pj-name', '不合法')
    await click(page, '建立專案')
    await waitText(page, '專案代碼需為 2～32 個字元')
    check('P1 代碼含空白與符號 → 前端擋下', true)
    for (const [code, name] of [[CODE_A.toLowerCase(), 'E2E 專案 A'], [CODE_B, 'E2E 專案 B']]) {
      await setValue(page, '#pj-code', ` ${code} `)
      await setValue(page, '#pj-name', name)
      await click(page, '建立專案')
      await page.waitForFunction((c) => document.querySelector(`[data-project-code="${c}"]`) !== null, {}, code.toUpperCase())
    }
    check('P1 建立兩個專案；代碼自動去除空白並轉大寫', (await page.$(`[data-project-code="${CODE_A}"]`)) !== null)
    await setValue(page, '#pj-code', CODE_B)
    await setValue(page, '#pj-name', '重複')
    await click(page, '建立專案')
    await waitText(page, '已經存在')
    check('P1 重複代碼 → 顯示後端的「已經存在」', true)
    await assertNoUnhandled('P1', [admin.tracker])
  }

  // ================= W1：員工填報多筆、查詢與合計、修改 =================
  const ctxA = await browser.createBrowserContext()
  const A = await newPage(ctxA, { viewport: DESKTOP })
  const ctlA = await controlRequests(A.page)
  {
    const page = A.page
    await loginAs(page, stack.users.a.username, stack.users.a.password, base)
    check('W1 員工沒有「專案管理」入口；「工時回報」已啟用', (await page.$('a[href="#/admin/projects"]')) === null && (await page.$('a[href="#/timesheets"]')) !== null)
    await page.click('a[href="#/timesheets"]')
    await page.waitForSelector('[data-testid=entry-form][data-mode=new]')
    const options = await page.$$eval('#ts-project option', (os) => os.map((o) => o.textContent))
    check('W1 專案下拉選單列出啟用中的專案', options.some((o) => o.includes(CODE_A)) && options.some((o) => o.includes(CODE_B)))

    await setValue(page, '#ts-hours', '0.1')
    check('W1 時數 0.1 → 提示需以 0.25 小時為單位（不四捨五入）', (await page.$eval('[data-testid=hours-preview]', (el) => el.textContent)).includes('0.25 小時'))
    await setValue(page, '#ts-hours', '7.75')
    check('W1 時數 7.75 → 換算為 7 小時 45 分（465 分鐘）', (await page.$eval('[data-testid=hours-preview]', (el) => el.textContent)).includes('465 分鐘'))

    await fillEntry(page, { date: today, project: CODE_A, hours: '2', description: '撰寫需求文件' })
    await click(page, '送出')
    await waitText(page, '已新增工時紀錄')
    await fillEntry(page, { date: today, project: CODE_B, hours: '1.5', description: '專案會議' })
    await click(page, '送出')
    await page.waitForFunction(() => document.querySelectorAll('[data-testid=timesheet-table] tbody tr').length === 2)
    await fillEntry(page, { date: yesterday, project: CODE_A, hours: '.25', description: '補填昨天的確認' })
    await click(page, '送出')
    await query(page, yesterday, today)
    await page.waitForFunction(() => document.querySelectorAll('[data-testid=timesheet-table] tbody tr').length === 3)
    const d = await daily(page)
    check('W1 三筆紀錄；期間合計 3 小時 45 分；每日合計（今天 210、昨天 15）', (await totalMinutes(page)) === 225 && d[today] === 210 && d[yesterday] === 15, JSON.stringify(d))
    const list = await rows(page)
    check('W1 日期新到舊：最後一列是昨天的紀錄', list[2].text.includes('補填昨天'))

    // 修改：專案會議改成 2 小時
    const meeting = list.find((r) => r.text.includes('專案會議'))
    await page.click(`tr[data-entry-id="${meeting.id}"] button`)
    await page.waitForSelector('[data-testid=entry-form][data-mode=edit]')
    check('W1 修改表單預填目前內容（1.5 小時）', (await page.$eval('#ts-hours', (el) => el.value)) === '1.5')
    await setValue(page, '#ts-hours', '2')
    await click(page, '送出')
    await waitText(page, '已更新工時紀錄')
    await page.waitForFunction(() => Number(document.querySelector('[data-testid=timesheet-total]')?.dataset.minutes) === 255)
    check('W1 修改後合計由後端重新計算：4 小時 15 分', (await totalMinutes(page)) === 255)

    // 每日上限：今天已有 4 小時，再加 21 小時 → 超過；20 小時 → 剛好 24 小時
    await fillEntry(page, { date: today, project: CODE_A, hours: '21', description: '超過上限' })
    await click(page, '送出')
    await page.waitForFunction(() => document.querySelector('[data-testid=entry-error]')?.textContent.includes('24 小時上限'))
    check('W1 超過每日 24 小時 → 顯示後端訊息，沒有新增', (await rows(page)).length === 3, await entryError(page))
    await setValue(page, '#ts-hours', '20')
    await click(page, '送出')
    await waitText(page, '已新增工時紀錄')
    await page.waitForFunction((t) => Number(document.querySelector(`[data-testid=timesheet-daily] li[data-date="${t}"]`)?.dataset.minutes) === 1440, {}, today)
    check('W1 剛好 24 小時可以新增；今天合計 1440 分鐘', true)
    await assertNoUnhandled('W1', [A.tracker])
  }

  // ================= W2：專案停用後，新增被拒、舊紀錄可查看並依規則修改 =================
  {
    await admin.page.bringToFront()
    await admin.page.click('a[href="#/admin/projects"]')
    await admin.page.waitForSelector(`[data-project-code="${CODE_B}"]`)
    await admin.page.evaluate((c) => [...document.querySelector(`[data-project-code="${c}"]`).querySelectorAll('button')].find((b) => b.textContent.trim() === '停用').click(), CODE_B)
    await admin.page.waitForSelector('[role=dialog]')
    check('W2 停用確認視窗說明：不能新增、既有工時保留、不刪除', (await admin.page.$eval('[data-testid=toggle-outcome]', (el) => el.textContent)).includes('不會刪除任何資料'))
    await click(admin.page, '確認停用')
    await waitText(admin.page, `已停用專案 ${CODE_B}`)

    const page = A.page
    await page.bringToFront()
    // 員工的下拉選單還是舊的（仍列出 B）：送出後由後端拒絕，並重新載入專案清單
    await fillEntry(page, { date: yesterday, project: CODE_B, hours: '1', description: '停用後才送出' })
    await click(page, '送出')
    await page.waitForFunction(() => document.querySelector('[data-testid=entry-error]')?.textContent.includes('已停用'))
    // 專案清單重新載入後，B 不再是可選的選項；目前草稿選著的 B 只以「停用或無法選擇」的停用選項標示（不偷偷改選）
    await page.waitForFunction((c) => ![...document.querySelectorAll('#ts-project option')].some((o) => o.textContent.includes(c) && !o.disabled), {}, CODE_B)
    check('W2 停用專案不可新增；專案清單重新載入後 B 不再可選（草稿中的選擇標示為無法選擇）', (await page.$('[data-testid=project-unavailable]')) !== null && (await rows(page)).every((r) => !r.text.includes('停用後才送出')))
    const bRow = (await rows(page)).find((r) => r.text.includes(CODE_B))
    check('W2 舊紀錄仍可查看，並標示「已停用」', bRow?.text.includes('已停用'))
    await page.click(`tr[data-entry-id="${bRow.id}"] button`)
    await page.waitForSelector('[data-testid=entry-form][data-mode=edit]')
    const selected = await page.$eval('#ts-project', (el) => el.options[el.selectedIndex].textContent)
    check('W2 修改表單保留原本的停用專案（不會被清空）', selected.includes(CODE_B) && selected.includes('已停用'), selected)
    await setValue(page, '#ts-description', '專案會議（補充紀錄）')
    await click(page, '送出')
    // 提示文字可能沿用先前的「已更新」，所以等清單真的出現新內容
    const kept = await page.waitForFunction(() => [...document.querySelectorAll('[data-testid=timesheet-table] tbody tr')].some((tr) => tr.innerText.includes('專案會議（補充紀錄）')), { timeout: 10_000 }).then(() => true, () => false)
    check('W2 維持原本的停用專案時可以修改內容', kept, await entryError(page))
  }

  // ================= W3：過期表單衝突 =================
  {
    const page1 = A.page
    const tab2 = await newPage(ctxA, { viewport: DESKTOP })
    await tab2.page.goto(`${base}/#/timesheets`, { waitUntil: 'networkidle0' })
    await query(tab2.page, yesterday, today)
    await tab2.page.waitForSelector('[data-testid=timesheet-table]')
    const target = (await rows(tab2.page)).find((r) => r.text.includes('補填昨天'))
    await tab2.page.click(`tr[data-entry-id="${target.id}"] button`)
    await tab2.page.waitForSelector('[data-testid=entry-form][data-mode=edit]')

    await page1.bringToFront()
    await page1.click(`tr[data-entry-id="${target.id}"] button`)
    await page1.waitForSelector('[data-testid=entry-form][data-mode=edit]')
    await setValue(page1, '#ts-hours', '0.5')
    await click(page1, '送出')
    await waitText(page1, '已更新工時紀錄')

    await tab2.page.bringToFront()
    await setValue(tab2.page, '#ts-hours', '1')
    await click(tab2.page, '送出')
    await waitText(tab2.page, '你的變更沒有儲存')
    check('W3 另一分頁先存 → 過期表單送出顯示衝突，不默默覆蓋', true)
    await click(tab2.page, '重新載入最新內容')
    await tab2.page.waitForFunction(() => document.querySelector('#ts-hours')?.value === '0.5')
    check('W3 重新載入後表單顯示最新內容（0.5 小時），沒有自動以新版本重送', true)
    await tab2.page.close()
  }

  // ================= W4：同條件重新查詢會真正重新取得 =================
  {
    const page = A.page
    await page.bringToFront()
    await query(page, yesterday, today)
    await page.waitForSelector('[data-testid=timesheet-table]')
    const before = (await rows(page)).length
    const listBefore = ctlA.counts.list
    // 同一位員工在別處新增一筆
    const status = await page.evaluate(async (args) => {
      const token = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '')
      const projects = (await (await fetch('/api/projects')).json()).projects
      const res = await fetch('/api/timesheets/me', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
        body: JSON.stringify({ requestId: crypto.randomUUID(), workDate: args.yesterday, projectId: projects.find((p) => p.code === args.code).id, description: '另一個分頁新增', minutes: 30 }),
      })
      return res.status
    }, { yesterday, code: CODE_A })
    check('W4 準備：另一處新增一筆', status === 201, String(status))
    await click(page, '查詢') // 條件完全相同
    await page.waitForFunction((n) => document.querySelectorAll('[data-testid=timesheet-table] tbody tr').length === n + 1, {}, before)
    check('W4 條件相同再按「查詢」→ 重新向後端取得，看到新紀錄', ctlA.counts.list > listBefore)
  }

  // ================= W5：重複提交與網路結果不明 =================
  {
    const page = A.page
    // 快速連按：同一個事件迴圈內按兩次 → 只送出一次
    const createsBefore = ctlA.counts.create
    await fillEntry(page, { date: yesterday, project: CODE_A, hours: '0.25', description: '連按測試' })
    await page.evaluate(() => {
      const b = [...document.querySelectorAll('button')].find((x) => x.textContent.trim() === '送出')
      b.click()
      b.click()
    })
    await page.waitForFunction((t) => [...document.querySelectorAll('[data-testid=timesheet-table] tbody tr')].some((tr) => tr.innerText.includes(t)), {}, '連按測試')
    await settle(page)
    check('W5 快速連按兩次 → 只送出一次新增', ctlA.counts.create === createsBefore + 1)

    // 回應遺失：伺服器已建立，但瀏覽器收不到回應
    ctlA.mode.create = 'lost-response'
    await fillEntry(page, { date: yesterday, project: CODE_A, hours: '0.75', description: '網路不穩時的新增' })
    await click(page, '送出')
    await page.waitForSelector('[data-testid=entry-form][data-unknown=true]')
    check('W5 網路結果不明 → 提示「無法確認原提交是否已新增」、表單鎖定，不自動重送', (await entryError(page)).includes('無法確認原提交是否已新增'))
    await page.waitForFunction(() => [...document.querySelectorAll('[data-testid=timesheet-table] tbody tr')].some((tr) => tr.innerText.includes('網路不穩時的新增')))
    check('W5 重新查詢後清單已出現這筆（伺服器其實已建立）', true)
    await click(page, '重新送出原內容') // 結果不明時表單鎖定在原內容，重送沿用同一個 requestId
    await waitText(page, '原提交已經建立')
    const bodies = ctlA.createBodies.slice(-2)
    check('W5 再按送出沿用同一個 requestId → 後端回「已建立」，沒有新增第二筆',
      bodies[0].requestId === bodies[1].requestId && (await rows(page)).filter((r) => r.text.includes('網路不穩時的新增')).length === 1, JSON.stringify(bodies.map((b) => b.requestId)))
    await fillEntry(page, { date: yesterday, project: CODE_A, hours: '0.25', description: '確認後的下一筆' })
    await click(page, '送出')
    await page.waitForFunction((t) => [...document.querySelectorAll('[data-testid=timesheet-table] tbody tr')].some((tr) => tr.innerText.includes(t)), {}, '確認後的下一筆')
    check('W5 確認之後的下一筆使用新的 requestId', ctlA.createBodies.at(-1).requestId !== bodies[1].requestId)
  }

  // ================= W6：舊的查詢回應不覆蓋新畫面 =================
  {
    const page = A.page
    const emptyBody = { from: yesterday, to: yesterday, timeZone: 'Asia/Taipei', entries: [], totalMinutes: 0, daily: [] }
    ctlA.hold.add('list')
    await query(page, yesterday, yesterday) // 舊查詢（扣住）
    await ctlA.waitHeld('list', 1)
    await query(page, today, today) // 新查詢（扣住）
    await ctlA.waitHeld('list', 2)
    const newer = ctlA.take('list', 1)
    await newer.continue()
    await page.waitForFunction((t) => document.querySelector('[data-testid=timesheet-range]')?.textContent.includes(`${t} ～ ${t}`), {}, today)
    const older = ctlA.take('list', 0)
    await older.respond(json(200, emptyBody))
    ctlA.hold.delete('list')
    await settle(page)
    check('W6 新查詢（今天）先回、舊查詢（昨天、空的）後回 → 畫面維持今天的結果',
      (await page.$eval('[data-testid=timesheet-range]', (el) => el.textContent)).includes(`${today} ～ ${today}`) && (await rows(page)).length > 0)
  }

  // ================= W7：送出後切頁，舊操作不修改新頁面；已送出的新增不會被撤回 =================
  {
    const page = A.page
    ctlA.hold.add('create')
    await fillEntry(page, { date: yesterday, project: CODE_A, hours: '0.5', description: '送出後切頁' })
    await click(page, '送出')
    await ctlA.waitHeld('create', 1)
    await page.click('a[href="#/attendance"]')
    await page.waitForSelector('[data-testid=today-status]')
    const pending = ctlA.take('create', 0)
    ctlA.hold.delete('create')
    await pending.continue()
    await settle(page)
    check('W7 送出後切頁 → 回應晚到也不在新頁面顯示任何訊息或錯誤', (await page.$('[data-testid=timesheet-notice]')) === null)
    await page.click('a[href="#/timesheets"]')
    await query(page, yesterday, yesterday)
    await page.waitForFunction(() => [...document.querySelectorAll('[data-testid=timesheet-table] tbody tr')].some((tr) => tr.innerText.includes('送出後切頁')))
    check('W7 已送出的新增在後端完成（切頁不能撤回），回到頁面查詢可看到；表單是新的空白表單', (await page.$eval('#ts-description', (el) => el.value)) === '')
    await assertNoUnhandled('W2～W7', [A.tracker])
  }

  // ================= W8：沒有啟用專案時的提示 =================
  {
    const ctx = await browser.createBrowserContext()
    const ui = await newPage(ctx, { viewport: DESKTOP })
    const ctl = await controlRequests(ui.page)
    ctl.mode.projects = 'empty'
    await loginAs(ui.page, stack.users.c.username, stack.users.c.password, base)
    await ui.page.click('a[href="#/timesheets"]')
    await ui.page.waitForSelector('[data-testid=no-projects]')
    check('W8 沒有啟用專案 → 提示需先由管理員建立或啟用；不顯示虛構選項、送出停用',
      (await ui.page.$$eval('#ts-project option', (os) => os.length)) === 1 && (await (await button(ui.page, '送出')).evaluate((b) => b.disabled)))
    await ctx.close()
  }

  // ================= W9：權限 =================
  {
    for (const [label, user] of [['員工', stack.users.c], ['主管', stack.users.b]]) {
      const ctx = await browser.createBrowserContext()
      const ui = await newPage(ctx, { viewport: DESKTOP })
      await loginAs(ui.page, user.username, user.password, base)
      check(`W9 ${label}沒有「專案管理」入口`, (await ui.page.$('a[href="#/admin/projects"]')) === null)
      await ui.page.goto(`${base}/#/admin/projects`, { waitUntil: 'networkidle0' })
      await waitText(ui.page, '只有管理員可以使用這個功能')
      const statuses = await ui.page.evaluate(async () => {
        const token = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '')
        const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }
        return {
          list: (await fetch('/api/admin/projects')).status,
          create: (await fetch('/api/admin/projects', { method: 'POST', headers, body: '{"code":"HACK","name":"x"}' })).status,
          update: (await fetch('/api/admin/projects/1', { method: 'PUT', headers, body: '{"name":"x","active":false,"expectedVersion":0}' })).status,
        }
      })
      check(`W9 ${label}直接呼叫專案管理 API → 全部 403`, Object.values(statuses).every((s) => s === 403), JSON.stringify(statuses))
      const mine = await ui.page.evaluate(async () => (await (await fetch('/api/timesheets/me')).json()).entries.length)
      check(`W9 ${label}的工時清單只有自己的（看不到員工 A 的紀錄）`, mine === 0)
      await ctx.close()
    }
  }

  // ================= W10：手機尺寸 =================
  {
    const ctx = await browser.createBrowserContext()
    const ui = await newPage(ctx, { viewport: MOBILE })
    await loginAs(ui.page, stack.users.a.username, stack.users.a.password, base)
    await (await ui.page.$('a[href="#/timesheets"]')).tap()
    await ui.page.waitForSelector('[data-testid=entry-form]')
    await query(ui.page, yesterday, today)
    await ui.page.waitForSelector('[data-testid=timesheet-table]')
    check('W10 手機：工時頁沒有橫向捲動', await noHorizontalOverflow(ui.page))
    const inputsFit = await ui.page.$$eval('#ts-date, #ts-project, #ts-hours, #ts-description', (els) => els.every((el) => el.getBoundingClientRect().right <= window.innerWidth + 1))
    check('W10 手機：表單欄位完整顯示（不截斷）', inputsFit)
    const submitHeight = await (await button(ui.page, '送出')).evaluate((b) => b.getBoundingClientRect().height)
    check('W10 手機：送出按鈕高度 ≥ 44px', submitHeight >= 44, `${submitHeight}px`)
    await ui.page.screenshot({ path: path.join(OUT, 'timesheets-01-mobile.png') })
    await ctx.close()

    const ctx2 = await browser.createBrowserContext()
    const ad = await newPage(ctx2, { viewport: MOBILE })
    await loginAs(ad.page, stack.users.admin.username, stack.users.admin.password, base)
    await ad.page.goto(`${base}/#/admin/projects`, { waitUntil: 'networkidle0' })
    await ad.page.waitForSelector('[data-testid=project-table]')
    check('W10 手機：專案管理頁沒有橫向捲動', await noHorizontalOverflow(ad.page))
    await ad.page.evaluate((c) => [...document.querySelector(`[data-project-code="${c}"]`).querySelectorAll('button')].find((b) => b.textContent.trim() === '重新啟用').scrollIntoView(), CODE_B)
    const toggle = await ad.page.evaluateHandle((c) => [...document.querySelector(`[data-project-code="${c}"]`).querySelectorAll('button')].find((b) => b.textContent.trim() === '重新啟用'), CODE_B)
    await toggle.asElement().tap()
    await ad.page.waitForSelector('[role=dialog]')
    check('W10 手機：確認視窗完整顯示', await ad.page.$eval('.modal', (el) => el.getBoundingClientRect().width <= window.innerWidth && el.getBoundingClientRect().left >= 0))
    await (await button(ad.page, '確認啟用')).tap()
    await waitText(ad.page, `已重新啟用專案 ${CODE_B}`)
    check('W10 手機：管理員可重新啟用專案', true)
    await ctx2.close()
  }

  check('沒有被帶回登入頁（一般錯誤不登出）', (await bodyText(A.page)).includes('TeamPortal') && (await A.page.$('.login-card')) === null)
  await assertNoUnhandled('全程', [A.tracker, admin.tracker])
} catch (error) {
  check('腳本執行未中斷', false, String(error?.stack ?? error))
} finally {
  await browser?.close()
  stack?.stop()
}

finish()
