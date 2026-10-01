// T03.1：出勤更正、重新打卡申請與管理權限的瀏覽器端到端驗證，在【隔離堆疊】執行（見 isolated-stack.mjs）：
// 另一個後端（8081，測試資料庫）＋另一個前端（5174）＋本次專用的新員工與管理員，不會碰你的開發帳號與出勤。
// 執行：cd frontend; npm run e2e:corrections
import path from 'node:path'
import { OUT, createRunner, launchBrowser, loginAs } from './helpers.mjs'
import { startIsolatedStack } from './isolated-stack.mjs'
import {
  bodyText, button, click, hasButton, isDisabled, newPage, noHorizontalOverflow, selectByText, setCorrectionDate, setValue,
  simulateReturnToWindow, taipeiDate, todayStatus, waitStatus, waitText,
} from './ui.mjs'

const { check, finish } = createRunner()
let stack
let browser

async function assertNoUnhandled(name, trackers) {
  const rejections = (await Promise.all(trackers.map((t) => t.unhandledRejections()))).flat()
  const errors = trackers.flatMap((t) => t.pageErrors)
  check(`${name}：沒有未處理的 Promise 拒絕與 JS 例外`, rejections.length === 0 && errors.length === 0, [...rejections, ...errors].join(' | '))
}

/** 反覆「回到視窗」直到條件成立（useAutoRefresh 兩次觸發至少隔 3 秒）。 */
async function refocusUntil(page, predicate, arg, attempts = 8) {
  for (let i = 0; i < attempts; i++) {
    await simulateReturnToWindow(page)
    try {
      await page.waitForFunction(predicate, { timeout: 4000 }, arg)
      return true
    } catch {
      // 還沒更新，下一輪再試
    }
  }
  return false
}

/** 管理員頁面：在待審清單中找到某位員工（以員工編號辨識）的申請卡片並按下按鈕；清單沒有更新時會重新「回到視窗」觸發查詢。 */
async function adminCardAction(page, employeeNo, label) {
  const found = await refocusUntil(page, (n) => [...document.querySelectorAll('li.request-item')].some((x) => x.textContent.includes(n)), employeeNo)
  if (!found) throw new Error(`管理頁找不到 ${employeeNo} 的申請卡片`)
  await page.evaluate((no, l) => {
    const li = [...document.querySelectorAll('li.request-item')].find((x) => x.textContent.includes(no))
    ;[...li.querySelectorAll('button')].find((b) => b.textContent.trim() === l).click()
  }, employeeNo, label)
}

const adminLogin = async (context, viewport) => {
  const ui = await newPage(context, { viewport })
  await loginAs(ui.page, stack.users.admin.username, stack.users.admin.password, stack.baseUrl)
  return ui
}

try {
  stack = await startIsolatedStack()
  console.log(`隔離堆疊已啟動：${stack.baseUrl}（測試資料庫；本次專用員工 ${stack.users.a.username} 等）`)
  browser = await launchBrowser()
  const base = stack.baseUrl
  const no = (letter) => `E${letter}-${stack.runId}`
  const DESKTOP = { width: 1200, height: 900 }

  // ================= E1：申請重新打卡 → 管理員核准 → 員工重新打卡 =================
  {
    const ctxA = await browser.createBrowserContext()
    const ctxAdmin = await browser.createBrowserContext()
    const a = await newPage(ctxA, { viewport: DESKTOP })
    await loginAs(a.page, stack.users.a.username, stack.users.a.password, base)
    await a.page.goto(`${base}/#/attendance`, { waitUntil: 'networkidle0' })
    await waitStatus(a.page, '尚未上班')
    check('E1 非管理員沒有「出勤管理」入口', !(await bodyText(a.page)).includes('出勤管理'))

    await click(a.page, '上班打卡')
    await waitStatus(a.page, '工作中')
    await click(a.page, '下班打卡')
    await waitStatus(a.page, '今日已完成')
    const originalIn = await a.page.$eval('[data-testid=clock-in-time]', (el) => el.textContent)
    const originalOut = await a.page.$eval('[data-testid=clock-out-time]', (el) => el.textContent)
    check('E1 員工當日已完成上下班，兩顆按鈕皆停用（每日一組）', (await isDisabled(a.page, '上班打卡')) && (await isDisabled(a.page, '下班打卡')))

    // 申請：原因必填
    await click(a.page, '申請重新打卡')
    await click(a.page, '送出申請')
    await waitText(a.page, '請填寫原因')
    check('E1 申請原因空白 → 前端提示「請填寫原因」，沒有送出', (await a.page.$('[data-testid=reset-pending]')) === null)
    await setValue(a.page, '#reason-text', '測試功能時誤按上下班，希望重新打卡')
    await click(a.page, '送出申請')
    await a.page.waitForSelector('[data-testid=reset-pending]')
    check('E1 送出後顯示「待審核」說明；今日仍是已完成、按鈕仍停用（申請不會立即改動出勤）',
      (await todayStatus(a.page)) === '今日已完成' && (await isDisabled(a.page, '上班打卡')) && !(await hasButton(a.page, '申請重新打卡')))
    await a.page.waitForSelector('[data-testid=my-requests]') // 卡片送出後，「我的申請」是非同步重新載入的
    check('E1 「我的申請」顯示一筆待審核', (await a.page.$eval('[data-testid=my-requests]', (el) => el.textContent)).includes('待審核'))
    await a.page.screenshot({ path: path.join(OUT, 'corrections-01-employee-pending.png') })

    // 管理員核准
    const admin = await adminLogin(ctxAdmin, DESKTOP)
    check('E1 管理員看得到「出勤管理」入口', (await bodyText(admin.page)).includes('出勤管理'))
    await admin.page.click('a[href="#/admin/attendance"]')
    await admin.page.waitForSelector('[data-testid=admin-requests]')
    const cardText = await admin.page.evaluate((n) => [...document.querySelectorAll('li.request-item')].find((x) => x.textContent.includes(n)).textContent, no('A'))
    check('E1 管理員的申請卡片顯示員工、原因、原時間', cardText.includes('E2E 員工 A') && cardText.includes('誤按上下班') && cardText.includes(originalIn))
    await adminCardAction(admin.page, no('A'), '核准')
    await admin.page.waitForSelector('[role=dialog]')
    const dialogText = await admin.page.$eval('[role=dialog]', (el) => el.textContent)
    check('E1 確認視窗清楚列出員工、日期、原值與預計結果（作廢、可重新打卡）',
      dialogText.includes('E2E 員工 A') && dialogText.includes(originalIn) && dialogText.includes('作廢') && dialogText.includes('重新打卡'))
    await admin.page.screenshot({ path: path.join(OUT, 'corrections-02-admin-confirm.png') })
    await click(admin.page, '確認核准')
    await waitText(admin.page, '已核准 E2E 員工 A 的申請')
    check('E1 核准完成，申請從待審清單消失', !(await bodyText(admin.page)).includes('誤按上下班') || (await bodyText(admin.page)).includes('目前沒有待審的申請'))

    // 員工回到視窗 → 重新向後端查詢真實狀態
    await a.page.bringToFront()
    check('E1 員工回到視窗後，今日狀態重新查詢為「尚未上班」', await refocusUntil(a.page, () => document.querySelector('[data-testid=today-status]')?.textContent === '尚未上班'))
    await a.page.waitForFunction(() => !document.querySelector('[data-testid=reset-pending]')) // 申請清單是另一個請求，稍後才更新
    check('E1 核准後「重新打卡」的待審說明消失，上班按鈕開放', (await a.page.$('[data-testid=reset-pending]')) === null && !(await isDisabled(a.page, '上班打卡')))
    await click(a.page, '上班打卡')
    await waitStatus(a.page, '工作中')
    await click(a.page, '下班打卡')
    await waitStatus(a.page, '今日已完成')
    check('E1 員工重新打卡成功並再次受每日一組限制', await isDisabled(a.page, '上班打卡'))

    // 原始紀錄與歷程
    await a.page.waitForSelector('[data-testid=records-table]')
    await a.page.waitForFunction(() => document.querySelectorAll('[data-testid=records-table] tbody tr').length === 2)
    const rows = await a.page.$$eval('[data-testid=records-table] tbody tr', (trs) => trs.map((r) => ({ id: r.dataset.recordId, status: r.dataset.status, text: r.textContent })))
    check('E1 紀錄頁同一日期有兩筆（作廢＋有效），以紀錄 ID 區分', rows.length === 2 && new Set(rows.map((r) => r.id)).size === 2 && rows.some((r) => r.status === 'VOIDED') && rows.some((r) => r.status === 'COMPLETED'))
    const voided = rows.find((r) => r.status === 'VOIDED')
    check('E1 作廢的紀錄保留原上下班時間', voided.text.includes(originalIn) && voided.text.includes(originalOut) && voided.text.includes('已作廢'))
    await click(a.page, '查看歷程')
    await a.page.waitForSelector('[data-testid=history-list]')
    const historyText = await a.page.$eval('[data-testid=history-list]', (el) => el.textContent)
    check('E1 員工可查看更正歷程：提出申請、核准申請、操作者', historyText.includes('提出申請') && historyText.includes('核准申請') && historyText.includes('E2E 管理員'))
    await a.page.screenshot({ path: path.join(OUT, 'corrections-03-employee-after-approval.png') })

    // 管理員的員工出勤查詢
    await selectByText(admin.page, '#admin-employee', no('A'))
    await click(admin.page, '查詢')
    await admin.page.waitForSelector('[data-testid=admin-records-table]')
    await admin.page.waitForSelector('[data-testid=history-list]')
    const adminRows = await admin.page.$$eval('[data-testid=admin-records-table] tbody tr', (trs) => trs.map((r) => r.dataset.status))
    check('E1 管理員查詢該員工：看到作廢與有效兩筆，以及歷程', adminRows.includes('VOIDED') && adminRows.includes('COMPLETED'))
    await assertNoUnhandled('E1', [a.tracker, admin.tracker])
    await ctxA.close()
    await ctxAdmin.close()
  }

  // ================= E2：權限——非管理員直接呼叫管理 API =================
  {
    const ctxB = await browser.createBrowserContext()
    const b = await newPage(ctxB, { viewport: DESKTOP })
    await loginAs(b.page, stack.users.b.username, stack.users.b.password, base)
    check('E2 主管（MANAGER）沒有「出勤管理」入口', !(await bodyText(b.page)).includes('出勤管理'))
    await b.page.goto(`${base}/#/admin/attendance`, { waitUntil: 'networkidle0' })
    await waitText(b.page, '只有管理員可以使用這個功能')
    check('E2 主管直接開管理頁網址：畫面顯示無權限，不載入任何管理資料', (await b.page.$('[data-testid=admin-requests]')) === null)
    const statuses = await b.page.evaluate(async () => {
      const token = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '')
      const get = async (p) => (await fetch(p)).status
      const post = async (p) => (await fetch(p, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: '{}' })).status
      return {
        list: await get('/api/admin/attendance/requests'),
        employees: await get('/api/admin/attendance/employees'),
        approve: await post('/api/admin/attendance/requests/1/approve'),
        correct: await post('/api/admin/attendance/records/correct'),
        voidRecord: await post('/api/admin/attendance/records/void'),
      }
    })
    check('E2 主管直接呼叫管理 API → 全部 403', Object.values(statuses).every((s) => s === 403), JSON.stringify(statuses))
    await assertNoUnhandled('E2', [b.tracker])
    await ctxB.close()

    const ctxAnon = await browser.createBrowserContext()
    const anon = await newPage(ctxAnon, { viewport: DESKTOP })
    await anon.page.goto(base, { waitUntil: 'networkidle0' })
    const anonStatus = await anon.page.evaluate(async () => (await fetch('/api/admin/attendance/requests')).status)
    check('E2 未登入呼叫管理 API → 401', anonStatus === 401, String(anonStatus))
    await ctxAnon.close()
  }

  // ================= E3：補登、退回、撤回（員工 C） =================
  {
    const ctxC = await browser.createBrowserContext()
    const ctxAdmin = await browser.createBrowserContext()
    const c = await newPage(ctxC, { viewport: DESKTOP })
    await loginAs(c.page, stack.users.c.username, stack.users.c.password, base)
    await c.page.goto(`${base}/#/attendance`, { waitUntil: 'networkidle0' })
    await c.page.waitForSelector('[data-testid=today-status]')

    const yesterday = taipeiDate(-1)
    await click(c.page, '申請更正／補登')
    await c.page.waitForSelector('#corr-date')
    await setCorrectionDate(c.page, yesterday)
    check('E3 補登表單：沒有紀錄的日期顯示「原時間：沒有出勤紀錄」', (await c.page.$eval('[data-testid=corr-original]', (el) => el.textContent)).includes('沒有出勤紀錄'))
    await setValue(c.page, '#corr-in', '09:00:00')
    await setValue(c.page, '#corr-out', '18:00:00')
    check('E3 補登表單清楚區分原時間與申請時間', (await c.page.$eval('[data-testid=corr-requested]', (el) => el.textContent)).includes('09:00:00'))
    await click(c.page, '送出申請')
    await waitText(c.page, '請填寫原因')
    await setValue(c.page, '#corr-reason', '忘記打卡，實際 09:00 到班')
    await click(c.page, '送出申請')
    await c.page.waitForSelector('[data-testid=my-requests]')
    check('E3 補登申請送出後出現在「我的申請」（待審核），出勤紀錄沒有新增', (await bodyText(c.page)).includes('待審核') && (await c.page.$$('[data-testid=records-table] tbody tr')).length === 0)

    const admin = await adminLogin(ctxAdmin, DESKTOP)
    await admin.page.click('a[href="#/admin/attendance"]')
    await admin.page.waitForSelector('[data-testid=admin-requests]')
    await adminCardAction(admin.page, no('C'), '核准')
    await admin.page.waitForSelector('[role=dialog]')
    check('E3 核准補登的確認視窗顯示「新增補登紀錄」與申請時間', (await admin.page.$eval('[role=dialog]', (el) => el.textContent)).includes('新增補登紀錄') && (await admin.page.$eval('[role=dialog]', (el) => el.textContent)).includes('09:00:00'))
    await click(admin.page, '確認核准')
    await waitText(admin.page, '已核准 E2E 員工 C 的申請')

    await c.page.bringToFront()
    // 預設只顯示本月；補登的日期可能在上個月，先把查詢範圍放寬到涵蓋它
    await setValue(c.page, '#range-from', taipeiDate(-5))
    await setValue(c.page, '#range-to', taipeiDate(0))
    await click(c.page, '查詢')
    const gotRow = await refocusUntil(c.page, (d) => [...document.querySelectorAll('[data-testid=records-table] tbody tr')].some((r) => r.textContent.includes(d)), yesterday.replace(/-/g, '-'))
    check('E3 員工回到視窗後，紀錄列表出現補登的那一天（標示「補登」）', gotRow && (await bodyText(c.page)).includes('補登'))

    // 退回
    await click(c.page, '申請更正／補登')
    await c.page.waitForSelector('#corr-date')
    await setCorrectionDate(c.page, taipeiDate(-2))
    await setValue(c.page, '#corr-in', '09:30:00')
    await setValue(c.page, '#corr-reason', '前天也漏打')
    await click(c.page, '送出申請')
    await c.page.waitForFunction(() => document.querySelector('[data-testid=my-requests]')?.textContent.includes('前天也漏打'))
    await admin.page.bringToFront()
    await simulateReturnToWindow(admin.page)
    await adminCardAction(admin.page, no('C'), '退回')
    await admin.page.waitForSelector('[role=dialog]')
    await click(admin.page, '確認退回')
    await waitText(admin.page, '退回必須填寫原因')
    check('E3 退回原因空白 → 對話框內顯示錯誤，申請沒有被退回', (await admin.page.$('[role=dialog]')) !== null)
    await setValue(admin.page, '#reject-reason', '請附上排班表佐證')
    await click(admin.page, '確認退回')
    await waitText(admin.page, '已退回 E2E 員工 C 的申請')
    await c.page.bringToFront()
    check('E3 員工看得到退回結果與原因', await refocusUntil(c.page, () => document.body.innerText.includes('已退回') && document.body.innerText.includes('請附上排班表佐證')))
    check('E3 被退回的日期沒有新增出勤紀錄', !(await c.page.$$eval('[data-testid=records-table] tbody tr', (rs) => rs.map((r) => r.textContent))).some((t) => t.includes(taipeiDate(-2))))

    // 撤回
    await click(c.page, '申請更正／補登')
    await c.page.waitForSelector('#corr-date')
    await setCorrectionDate(c.page, taipeiDate(-3))
    await setValue(c.page, '#corr-in', '09:10:00')
    await setValue(c.page, '#corr-reason', '送出後又想撤回')
    await click(c.page, '送出申請')
    await c.page.waitForFunction(() => document.querySelector('[data-testid=my-requests]')?.textContent.includes('送出後又想撤回'))
    await click(c.page, '撤回申請')
    await waitText(c.page, '已撤回')
    check('E3 員工撤回待審申請 → 狀態為「已撤回」', (await bodyText(c.page)).includes('已撤回'))
    await assertNoUnhandled('E3', [c.tracker, admin.tracker])
    await ctxC.close()
    await ctxAdmin.close()
  }

  // ================= E4：撤銷誤按的下班（員工 B） =================
  {
    const ctxB = await browser.createBrowserContext()
    const ctxAdmin = await browser.createBrowserContext()
    const b = await newPage(ctxB, { viewport: DESKTOP })
    await loginAs(b.page, stack.users.b.username, stack.users.b.password, base)
    await b.page.goto(`${base}/#/attendance`, { waitUntil: 'networkidle0' })
    await waitStatus(b.page, '尚未上班')
    await click(b.page, '上班打卡')
    await waitStatus(b.page, '工作中')
    await click(b.page, '下班打卡') // 誤按
    await waitStatus(b.page, '今日已完成')
    await b.page.waitForSelector('[data-testid=records-table]')
    await click(b.page, '申請更正')
    await b.page.waitForSelector('[data-testid=corr-original][data-state=ready]')
    check('E4 更正表單預填目前的上下班時間', (await b.page.$eval('#corr-out', (el) => el.value)) !== '')
    await setValue(b.page, '#corr-out', '')
    check('E4 清空下班時間＝撤銷下班，申請時間顯示「無下班時間」', (await b.page.$eval('[data-testid=corr-requested]', (el) => el.textContent)).includes('無下班時間'))
    await setValue(b.page, '#corr-reason', '誤按下班，要繼續上班')
    await click(b.page, '送出申請')
    await b.page.waitForFunction(() => document.querySelector('[data-testid=my-requests]')?.textContent.includes('誤按下班，要繼續上班'))

    const admin = await adminLogin(ctxAdmin, DESKTOP)
    await admin.page.click('a[href="#/admin/attendance"]')
    await adminCardAction(admin.page, no('B'), '核准')
    await admin.page.waitForSelector('[role=dialog]')
    await click(admin.page, '確認核准')
    await waitText(admin.page, '已核准')

    await b.page.bringToFront()
    check('E4 核准後員工回到視窗：今日恢復「工作中」且下班時間清空', await refocusUntil(b.page, () => document.querySelector('[data-testid=today-status]')?.textContent === '工作中'))
    check('E4 恢復工作中後下班按鈕可再按', !(await isDisabled(b.page, '下班打卡')))
    await click(b.page, '下班打卡')
    await waitStatus(b.page, '今日已完成')
    await assertNoUnhandled('E4', [b.tracker, admin.tracker])
    await ctxB.close()
    await ctxAdmin.close()
  }

  // ================= E5：管理員直接作廢今日紀錄（員工 A 已有今日紀錄） =================
  {
    const ctxAdmin = await browser.createBrowserContext()
    const ctxA = await browser.createBrowserContext()
    const admin = await adminLogin(ctxAdmin, DESKTOP)
    await admin.page.click('a[href="#/admin/attendance"]')
    await admin.page.waitForSelector('#admin-employee')
    await selectByText(admin.page, '#admin-employee', no('A'))
    await click(admin.page, '查詢')
    await admin.page.waitForSelector('[data-testid=admin-records-table]')
    await click(admin.page, '作廢今日紀錄')
    await admin.page.waitForSelector('[role=dialog]')
    await click(admin.page, '確認作廢')
    await waitText(admin.page, '請填寫原因')
    check('E5 直接作廢必須填寫原因（空白被擋下）', (await admin.page.$('[role=dialog]')) !== null)
    await setValue(admin.page, '#void-reason', '測試後清理，開放重新打卡')
    await click(admin.page, '確認作廢')
    await waitText(admin.page, '已作廢 E2E 員工 A 今日的紀錄')

    const a = await newPage(ctxA, { viewport: DESKTOP })
    await loginAs(a.page, stack.users.a.username, stack.users.a.password, base)
    await waitStatus(a.page, '尚未上班')
    check('E5 員工登入後今日為「尚未上班」，可再打卡', !(await isDisabled(a.page, '上班打卡')))
    await admin.page.waitForSelector('[data-testid=history-list]')
    check('E5 管理員歷程列出「管理員作廢」與原因', (await admin.page.$eval('[data-testid=history-list]', (el) => el.textContent)).includes('測試後清理'))
    await assertNoUnhandled('E5', [a.tracker, admin.tracker])
    await ctxAdmin.close()
    await ctxA.close()
  }

  // ================= E6：行動版（員工提出申請、管理員核准） =================
  {
    const MOBILE = { width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true }
    const ctxC = await browser.createBrowserContext()
    const ctxAdmin = await browser.createBrowserContext()
    const c = await newPage(ctxC, { viewport: MOBILE })
    await loginAs(c.page, stack.users.c.username, stack.users.c.password, base)
    await c.page.goto(`${base}/#/attendance`, { waitUntil: 'networkidle0' })
    await c.page.waitForSelector('#range-from')
    check('E6 手機：出勤頁沒有橫向捲動', await noHorizontalOverflow(c.page))
    await c.page.waitForSelector('[data-testid=my-requests]') // 等非同步內容載入完成，避免版面位移讓點擊落在別處
    const openForm = await button(c.page, '申請更正／補登')
    await openForm.scrollIntoView()
    await openForm.tap()
    await c.page.waitForSelector('#corr-date')
    check('E6 手機：更正表單沒有橫向捲動', await noHorizontalOverflow(c.page))
    await setCorrectionDate(c.page, taipeiDate(-4))
    await setValue(c.page, '#corr-in', '09:05:00')
    await setValue(c.page, '#corr-reason', '手機提出的補登')
    await c.page.screenshot({ path: path.join(OUT, 'corrections-04-mobile-form.png') })
    await (await button(c.page, '送出申請')).tap()
    await c.page.waitForFunction(() => document.querySelector('[data-testid=my-requests]')?.textContent.includes('手機提出的補登'))
    check('E6 手機：成功送出申請', true)

    const admin = await adminLogin(ctxAdmin, MOBILE)
    await (await admin.page.$('a[href="#/admin/attendance"]')).tap()
    await admin.page.waitForSelector('[data-testid=admin-requests]')
    check('E6 手機：管理頁沒有橫向捲動', await noHorizontalOverflow(admin.page))
    await admin.page.evaluate((n) => {
      const li = [...document.querySelectorAll('li.request-item')].find((x) => x.textContent.includes(n))
      ;[...li.querySelectorAll('button')].find((b) => b.textContent.trim() === '核准').scrollIntoView()
    }, no('C'))
    const approveHandle = await admin.page.evaluateHandle((n) => {
      const li = [...document.querySelectorAll('li.request-item')].find((x) => x.textContent.includes(n))
      return [...li.querySelectorAll('button')].find((b) => b.textContent.trim() === '核准')
    }, no('C'))
    const approveHeight = await approveHandle.evaluate((b) => b.getBoundingClientRect().height)
    check('E6 手機：核准按鈕高度 ≥ 44px', approveHeight >= 44, `${approveHeight}px`)
    await approveHandle.asElement().tap()
    await admin.page.waitForSelector('[role=dialog]')
    const modalFits = await admin.page.$eval('.modal', (el) => el.getBoundingClientRect().width <= window.innerWidth && el.getBoundingClientRect().left >= 0)
    check('E6 手機：確認視窗完整顯示在畫面內', modalFits && (await noHorizontalOverflow(admin.page)))
    await admin.page.screenshot({ path: path.join(OUT, 'corrections-05-mobile-admin-confirm.png') })
    await (await button(admin.page, '確認核准')).tap()
    await waitText(admin.page, '已核准 E2E 員工 C 的申請')
    check('E6 手機：管理員成功核准', true)
    await assertNoUnhandled('E6', [c.tracker, admin.tracker])
    await ctxC.close()
    await ctxAdmin.close()
  }

  // ================= E7：回到視窗、切頁、跨台北日期時重新查詢 =================
  {
    const ctx = await browser.createBrowserContext()
    const c = await newPage(ctx, { viewport: DESKTOP })
    // 讓頁面的「現在」可被位移，用來模擬跨過台北日期（只影響前端判斷；後端的跨日由後端測試以可控時鐘驗證）
    await c.page.evaluateOnNewDocument(() => {
      const RealDate = Date
      globalThis.__timeShiftMs = 0
      class ShiftedDate extends RealDate {
        constructor(...args) {
          if (args.length === 0) super(RealDate.now() + globalThis.__timeShiftMs)
          else super(...args)
        }
        static now() {
          return RealDate.now() + globalThis.__timeShiftMs
        }
      }
      globalThis.Date = ShiftedDate
    })
    let todayRequests = 0
    c.page.on('request', (r) => {
      if (new URL(r.url()).pathname === '/api/attendance/me/today') todayRequests++
    })
    await loginAs(c.page, stack.users.c.username, stack.users.c.password, base)
    await waitStatus(c.page, '尚未上班')
    await c.page.waitForFunction(() => true)
    const baseline = todayRequests

    await new Promise((r) => setTimeout(r, 3500)) // 避開觸發的 3 秒節流
    await simulateReturnToWindow(c.page)
    await c.page.waitForFunction(() => true)
    await new Promise((r) => setTimeout(r, 800))
    check('E7 視窗回到前景（focus／visibilitychange）→ 重新查詢今日狀態', todayRequests > baseline, `${baseline} → ${todayRequests}`)

    const beforeShift = todayRequests
    await c.page.evaluate(() => {
      globalThis.__timeShiftMs = 24 * 60 * 60 * 1000 // 前端的「台北今天」變成明天
    })
    await c.page.waitForFunction(() => true)
    const deadline = Date.now() + 25_000
    while (todayRequests === beforeShift && Date.now() < deadline) await new Promise((r) => setTimeout(r, 500))
    check('E7 前端偵測到台北日期已變 → 自動重新查詢（不會停在昨天的狀態）', todayRequests > beforeShift, `${beforeShift} → ${todayRequests}`)
    await assertNoUnhandled('E7', [c.tracker])
    await ctx.close()
  }
} catch (error) {
  check('腳本執行未中斷', false, String(error?.stack ?? error))
} finally {
  await browser?.close()
  stack?.stop()
}

finish()
