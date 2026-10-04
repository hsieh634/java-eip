// T03.1：出勤更正、重新打卡申請與管理權限的瀏覽器端到端驗證，在【隔離堆疊】執行（見 isolated-stack.mjs）：
// 另一個後端（8081，測試資料庫）＋另一個前端（5174）＋本次專用的新員工與管理員，不會碰你的開發帳號與出勤。
// 第 1 輪複審新增：E4 撤銷下班打卡（同一筆、上班時間完整保留）＋舊表單被拒、E5 R1（ID 不同但版本相同的舊對話框被拒）、
// E8 R2（相同條件再按查詢會重新取得）。
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

  // ================= E1：申請整組作廢並重新打卡 → 管理員核准 → 員工重新打卡 =================
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

    // 已下班：兩種今日申請都可以選，名稱可以清楚分辨
    check('E1 已下班時同時提供「申請撤銷下班打卡」與「申請整組作廢並重新打卡」', (await hasButton(a.page, '申請撤銷下班打卡')) && (await hasButton(a.page, '申請整組作廢並重新打卡')))
    // 申請：原因必填
    await click(a.page, '申請整組作廢並重新打卡')
    await a.page.waitForSelector('[data-testid=reset-form]')
    const resetFormText = await a.page.$eval('[data-testid=reset-form]', (el) => el.textContent)
    check('E1 整組作廢表單列出員工、目前上下班，並明確說明「整組」都會作廢、要重新打上下班卡',
      resetFormText.includes('E2E 員工 A') && resetFormText.includes(originalIn) && resetFormText.includes(originalOut) &&
      resetFormText.includes('整組打卡（上班與下班）都會標為作廢') && resetFormText.includes('重新打上班卡與下班卡') && resetFormText.includes('申請撤銷下班打卡'))
    await click(a.page, '送出申請')
    await waitText(a.page, '請填寫原因')
    check('E1 申請原因空白 → 前端提示「請填寫原因」，沒有送出', (await a.page.$('[data-testid=today-pending]')) === null)
    await setValue(a.page, '#reason-text', '測試功能時誤按上下班，希望重新打卡')
    await click(a.page, '送出申請')
    await a.page.waitForSelector('[data-testid=today-pending]')
    check('E1 送出後顯示「整組作廢並重新打卡」待審說明；今日仍是已完成、按鈕仍停用（申請不會立即改動出勤）',
      (await a.page.$eval('[data-testid=today-pending]', (el) => el.dataset.kind)) === 'RESET_TODAY' &&
      (await todayStatus(a.page)) === '今日已完成' && (await isDisabled(a.page, '上班打卡')) &&
      !(await hasButton(a.page, '申請整組作廢並重新打卡')) && !(await hasButton(a.page, '申請撤銷下班打卡')))
    await a.page.waitForSelector('[data-testid=my-requests]') // 卡片送出後，「我的申請」是非同步重新載入的
    check('E1 「我的申請」顯示一筆待審核', (await a.page.$eval('[data-testid=my-requests]', (el) => el.textContent)).includes('待審核'))
    await a.page.screenshot({ path: path.join(OUT, 'corrections-01-employee-pending.png') })

    // 管理員核准
    const admin = await adminLogin(ctxAdmin, DESKTOP)
    check('E1 管理員看得到「出勤管理」入口', (await bodyText(admin.page)).includes('出勤管理'))
    await admin.page.click('a[href="#/admin/attendance"]')
    await admin.page.waitForSelector('[data-testid=admin-requests]')
    const cardText = await admin.page.evaluate((n) => [...document.querySelectorAll('li.request-item')].find((x) => x.textContent.includes(n)).textContent, no('A'))
    check('E1 管理員的申請卡片顯示員工、種類「整組作廢並重新打卡」、原因、原時間',
      cardText.includes('E2E 員工 A') && cardText.includes('整組作廢並重新打卡') && cardText.includes('誤按上下班') && cardText.includes(originalIn))
    await adminCardAction(admin.page, no('A'), '核准')
    await admin.page.waitForSelector('[role=dialog]')
    const dialogText = await admin.page.$eval('[role=dialog]', (el) => el.textContent)
    check('E1 確認視窗清楚列出員工、日期、原值與預計結果（整組作廢、回到尚未上班）',
      dialogText.includes('E2E 員工 A') && dialogText.includes(originalIn) && dialogText.includes('整組作廢') && dialogText.includes('尚未上班'))
    await admin.page.screenshot({ path: path.join(OUT, 'corrections-02-admin-confirm.png') })
    await click(admin.page, '確認核准')
    await waitText(admin.page, '已核准 E2E 員工 A 的申請')
    check('E1 核准完成，申請從待審清單消失', !(await bodyText(admin.page)).includes('誤按上下班') || (await bodyText(admin.page)).includes('目前沒有待審的申請'))

    // 員工回到視窗 → 重新向後端查詢真實狀態
    await a.page.bringToFront()
    check('E1 員工回到視窗後，今日狀態重新查詢為「尚未上班」', await refocusUntil(a.page, () => document.querySelector('[data-testid=today-status]')?.textContent === '尚未上班'))
    await a.page.waitForFunction(() => !document.querySelector('[data-testid=today-pending]')) // 申請清單是另一個請求，稍後才更新
    check('E1 核准後待審說明消失，上班按鈕開放', (await a.page.$('[data-testid=today-pending]')) === null && !(await isDisabled(a.page, '上班打卡')))
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
    check('E1 員工可查看更正歷程：提出申請、核准申請、種類「整組作廢並重新打卡」、操作者',
      historyText.includes('提出申請') && historyText.includes('核准申請') && historyText.includes('整組作廢並重新打卡') && historyText.includes('E2E 管理員'))
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
    await click(c.page, '申請更正／補登時間')
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
    await click(c.page, '申請更正／補登時間')
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
    await click(c.page, '申請更正／補登時間')
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

  // ================= E4：申請撤銷下班打卡（員工 B）＋舊表單被拒絕 =================
  {
    const ctxB = await browser.createBrowserContext()
    const ctxAdmin = await browser.createBrowserContext()
    const b = await newPage(ctxB, { viewport: DESKTOP })
    await loginAs(b.page, stack.users.b.username, stack.users.b.password, base)
    await b.page.goto(`${base}/#/attendance`, { waitUntil: 'networkidle0' })
    await waitStatus(b.page, '尚未上班')
    check('E4 尚未打卡時沒有今日申請按鈕', !(await hasButton(b.page, '申請撤銷下班打卡')) && !(await hasButton(b.page, '申請整組作廢並重新打卡')))
    await click(b.page, '上班打卡')
    await waitStatus(b.page, '工作中')
    await b.page.waitForSelector('[data-testid=records-table]')
    check('E4 只有上班（工作中）時：可申請整組作廢，但沒有「撤銷下班」', !(await hasButton(b.page, '申請撤銷下班打卡')) && (await hasButton(b.page, '申請整組作廢並重新打卡')))
    await click(b.page, '下班打卡') // 誤按
    await waitStatus(b.page, '今日已完成')
    const todayJson = () => b.page.evaluate(async () => (await fetch('/api/attendance/me/today')).json())
    const before = await todayJson()
    const shownIn = await b.page.$eval('[data-testid=clock-in-time]', (el) => el.textContent)

    await click(b.page, '申請撤銷下班打卡')
    await b.page.waitForSelector('[data-testid=revoke-form]')
    const revokeText = await b.page.$eval('[data-testid=revoke-form]', (el) => el.textContent)
    const revokeIn = await b.page.$eval('#revoke-in', (el) => ({ value: el.value, readOnly: el.readOnly }))
    check('E4 撤銷下班表單：顯示員工、日期、目前上下班時間；上班時間唯讀且等於目前上班時間',
      revokeText.includes('E2E 主管 B') && revokeText.includes(taipeiDate(0)) && revokeText.includes(shownIn) && revokeIn.readOnly && revokeIn.value === shownIn,
      `${revokeText} | ${JSON.stringify(revokeIn)}`)
    check('E4 撤銷下班表單寫明結果：「保留上班時間、撤銷下班時間，恢復工作中，可再次下班打卡」',
      (await b.page.$eval('[data-testid=form-outcome]', (el) => el.textContent)).includes('保留上班時間、撤銷下班時間，恢復工作中，可再次下班打卡'))
    await click(b.page, '送出申請')
    await waitText(b.page, '請填寫原因')
    check('E4 原因空白 → 前端擋下', (await b.page.$('[data-testid=today-pending]')) === null)
    await setValue(b.page, '#reason-text', '誤按下班，要繼續上班')
    await click(b.page, '送出申請')
    await b.page.waitForSelector('[data-testid=today-pending]')
    check('E4 送出後待審說明為「撤銷下班打卡」；核准前仍是已完成',
      (await b.page.$eval('[data-testid=today-pending]', (el) => el.dataset.kind)) === 'REVOKE_CLOCK_OUT' && (await todayStatus(b.page)) === '今日已完成')

    const admin = await adminLogin(ctxAdmin, DESKTOP)
    await admin.page.click('a[href="#/admin/attendance"]')
    await adminCardAction(admin.page, no('B'), '核准')
    await admin.page.waitForSelector('[role=dialog]')
    const revokeDialog = await admin.page.$eval('[role=dialog]', (el) => el.textContent)
    check('E4 管理員確認視窗：種類「撤銷下班打卡」，預計結果是保留上班、清除下班、不作廢',
      revokeDialog.includes('撤銷下班打卡') && revokeDialog.includes(`保留上班時間 ${shownIn}`) && revokeDialog.includes('不會作廢紀錄'))
    await click(admin.page, '確認核准')
    await waitText(admin.page, '已核准')

    await b.page.bringToFront()
    check('E4 核准後員工回到視窗：今日恢復「工作中」', await refocusUntil(b.page, () => document.querySelector('[data-testid=today-status]')?.textContent === '工作中'))
    const after = await todayJson()
    check('E4 同一筆紀錄（ID 不變）、上班時間完整精度不變、下班清空、版本 +1',
      after.recordId === before.recordId && after.clockInAt === before.clockInAt && after.clockOutAt === null && after.version === before.version + 1,
      JSON.stringify({ before, after }))
    const bRows = await b.page.$$eval('[data-testid=records-table] tbody tr', (trs) => trs.map((r) => r.dataset.status))
    check('E4 紀錄列表沒有作廢紀錄、只有一筆（撤銷下班不是整組作廢）', bRows.length === 1 && !bRows.includes('VOIDED'), bRows.join(','))
    check('E4 恢復工作中後下班按鈕可再按', !(await isDisabled(b.page, '下班打卡')))
    await click(b.page, '下班打卡')
    await waitStatus(b.page, '今日已完成')
    await click(b.page, '查看歷程')
    await b.page.waitForSelector('[data-testid=history-list]')
    const bHistory = await b.page.$$eval('[data-testid=history-list] li', (lis) => lis.map((li) => ({ op: li.dataset.operation, kind: li.dataset.kind, text: li.textContent })))
    const approvedEntry = bHistory.find((h) => h.op === 'REQUEST_APPROVED')
    check('E4 歷程標示「撤銷下班打卡」，修改後是時間（不是整組作廢）',
      approvedEntry?.kind === 'REVOKE_CLOCK_OUT' && approvedEntry.text.includes('撤銷下班打卡') && !approvedEntry.text.includes('整組作廢'), JSON.stringify(approvedEntry))

    // 舊表單：員工開著撤銷表單時，管理員直接更正了這筆紀錄 → 送出被拒絕，不建立申請
    await click(b.page, '申請撤銷下班打卡')
    await b.page.waitForSelector('[data-testid=revoke-form]')
    await admin.page.bringToFront()
    await selectByText(admin.page, '#admin-employee', no('B'))
    await click(admin.page, '查詢')
    await admin.page.waitForSelector('[data-testid=admin-records-table]')
    await click(admin.page, '更正時間')
    await admin.page.waitForSelector('[role=dialog]')
    await setValue(admin.page, '#direct-out', '')
    await setValue(admin.page, '#direct-reason', '當面確認仍在工作')
    await click(admin.page, '確認更正')
    await waitText(admin.page, '已更正 E2E 主管 B').catch(async (e) => {
      throw new Error(`直接更正沒有成功：${await admin.page.$eval('[role=dialog]', (el) => el.textContent).catch(() => '(無對話框)')}`, { cause: e })
    })
    check('E4 管理員以畫面直接更正（同時送出紀錄 ID＋版本）成功', true)
    await b.page.bringToFront()
    await setValue(b.page, '#reason-text', '用舊畫面送出')
    await click(b.page, '送出申請')
    await waitText(b.page, '出勤資料在你開啟表單後已經變動')
    check('E4 舊表單送出 → 顯示「資料已變動」，沒有建立待審申請', (await b.page.$('[data-testid=today-pending]')) === null)
    check('E4 卡片重新查詢後表單提示已過期、今日狀態更新為工作中',
      await refocusUntil(b.page, () => document.querySelector('[data-testid=form-outdated]') !== null && document.querySelector('[data-testid=today-status]')?.textContent === '工作中'))
    await click(b.page, '取消')
    await click(b.page, '下班打卡')
    await waitStatus(b.page, '今日已完成')
    await assertNoUnhandled('E4', [b.tracker, admin.tracker])
    await ctxB.close()
    await ctxAdmin.close()
  }

  // ================= E5：R1——舊的直接更正對話框不能改到「換上來的另一筆」；管理員直接作廢 =================
  {
    const ctxAdmin = await browser.createBrowserContext()
    const ctxAdmin2 = await browser.createBrowserContext()
    const ctxA = await browser.createBrowserContext()
    const admin = await adminLogin(ctxAdmin, DESKTOP)
    await admin.page.click('a[href="#/admin/attendance"]')
    await admin.page.waitForSelector('#admin-employee')
    await selectByText(admin.page, '#admin-employee', no('A'))
    await click(admin.page, '查詢')
    await admin.page.waitForSelector('[data-testid=admin-records-table]')
    const oldRow = await admin.page.$eval('[data-testid=admin-records-table] tbody tr:not(.row--voided)', (tr) => ({ id: tr.dataset.recordId, version: tr.cells[4].textContent }))
    await click(admin.page, '更正時間') // 開著「舊紀錄」的更正對話框
    await admin.page.waitForSelector('[role=dialog]')

    // 另一個管理員工作階段：作廢今日紀錄
    const admin2 = await adminLogin(ctxAdmin2, DESKTOP)
    await admin2.page.click('a[href="#/admin/attendance"]')
    await admin2.page.waitForSelector('#admin-employee')
    await selectByText(admin2.page, '#admin-employee', no('A'))
    await click(admin2.page, '查詢')
    await admin2.page.waitForSelector('[data-testid=admin-records-table]')
    await click(admin2.page, '作廢今日紀錄')
    await admin2.page.waitForSelector('[role=dialog]')
    await click(admin2.page, '確認作廢')
    await waitText(admin2.page, '請填寫原因')
    check('E5 直接作廢必須填寫原因（空白被擋下）', (await admin2.page.$('[role=dialog]')) !== null)
    await setValue(admin2.page, '#void-reason', '測試後清理，開放重新打卡')
    await click(admin2.page, '確認作廢')
    await waitText(admin2.page, '已作廢 E2E 員工 A 今日的紀錄')

    // 員工重新上下班 → 新的一筆，版本號碼和舊紀錄相同
    const a = await newPage(ctxA, { viewport: DESKTOP })
    await loginAs(a.page, stack.users.a.username, stack.users.a.password, base)
    await waitStatus(a.page, '尚未上班')
    check('E5 員工登入後今日為「尚未上班」，可再打卡', !(await isDisabled(a.page, '上班打卡')))
    await click(a.page, '上班打卡')
    await waitStatus(a.page, '工作中')
    await click(a.page, '下班打卡')
    await waitStatus(a.page, '今日已完成')
    const replacement = await a.page.evaluate(async () => (await fetch('/api/attendance/me/today')).json())
    check('E5 新紀錄與舊紀錄 ID 不同、版本相同（只比版本會誤判）', String(replacement.recordId) !== oldRow.id && String(replacement.version) === oldRow.version,
      JSON.stringify({ oldRow, replacement }))

    // 第一個管理員送出舊對話框
    await admin.page.bringToFront()
    // 上班改成 00:00:00：任何時刻都不晚於既有的下班時間，也不在未來（原本寫死 08:00，在台北 08:00 前執行會被「下班早於上班」擋下）
    await setValue(admin.page, '#direct-in', '00:00:00')
    await setValue(admin.page, '#direct-reason', '舊對話框')
    await click(admin.page, '確認更正')
    await waitText(admin.page, '這筆出勤資料已被其他操作更新').catch(async (e) => {
      throw new Error(`舊對話框沒有得到 409：${await admin.page.$eval('[role=dialog]', (el) => el.textContent).catch(() => '(無對話框)')}`, { cause: e })
    })
    check('E5 舊對話框送出 → 409「已被其他操作更新」，對話框仍開著', (await admin.page.$('[role=dialog]')) !== null)
    await click(admin.page, '取消')
    const replacementAfter = await a.page.evaluate(async () => (await fetch('/api/attendance/me/today')).json())
    check('E5 新紀錄完全沒被改（時間、版本相同）', JSON.stringify(replacementAfter) === JSON.stringify(replacement))

    await admin2.page.bringToFront()
    await admin2.page.waitForSelector('[data-testid=history-list]')
    check('E5 管理員歷程列出「管理員作廢」與原因', (await admin2.page.$eval('[data-testid=history-list]', (el) => el.textContent)).includes('測試後清理'))
    await assertNoUnhandled('E5', [a.tracker, admin.tracker, admin2.tracker])
    await ctxAdmin.close()
    await ctxAdmin2.close()
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
    const openForm = await button(c.page, '申請更正／補登時間')
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
  // ================= E8：R2——相同條件再按一次「查詢」也會重新取得紀錄與歷程 =================
  {
    const ctxAdmin = await browser.createBrowserContext()
    const ctxC = await browser.createBrowserContext()
    const admin = await adminLogin(ctxAdmin, DESKTOP)
    let recordFetches = 0
    let historyFetches = 0
    admin.page.on('request', (r) => {
      const p = new URL(r.url()).pathname
      if (p === '/api/admin/attendance/records') recordFetches++
      if (p === '/api/admin/attendance/history') historyFetches++
    })
    await admin.page.click('a[href="#/admin/attendance"]')
    await admin.page.waitForSelector('#admin-employee')
    await selectByText(admin.page, '#admin-employee', no('C'))
    await setValue(admin.page, '#admin-from', taipeiDate(0))
    await setValue(admin.page, '#admin-to', taipeiDate(0))
    await click(admin.page, '查詢')
    await waitText(admin.page, '這段期間沒有出勤紀錄')
    await admin.page.waitForFunction(() => document.querySelector('[data-testid=history-list]') || document.body.innerText.includes('沒有歷程紀錄'))
    const fetchesBefore = { recordFetches, historyFetches }
    const reasonsBefore = await admin.page.evaluate(() => document.body.innerText.includes('R2 驗證用的申請'))
    check('E8 第一次查詢：今天沒有紀錄、也沒有這次的申請歷程', !reasonsBefore && (await admin.page.$('[data-testid=admin-records-table]')) === null)

    // 另一個工作階段：員工 C 上班打卡並提出整組作廢申請（新增出勤＋新增歷程）
    const c = await newPage(ctxC, { viewport: DESKTOP })
    await loginAs(c.page, stack.users.c.username, stack.users.c.password, base)
    await waitStatus(c.page, '尚未上班')
    await click(c.page, '上班打卡')
    await waitStatus(c.page, '工作中')
    await click(c.page, '申請整組作廢並重新打卡')
    await setValue(c.page, '#reason-text', 'R2 驗證用的申請')
    await click(c.page, '送出申請')
    await c.page.waitForSelector('[data-testid=today-pending]')

    // 管理員不改任何條件，再按一次「查詢」
    await admin.page.bringToFront()
    check('E8 條件不變、尚未重新查詢前畫面仍是舊資料（員工出勤區沒有自動輪詢）', (await admin.page.$('[data-testid=admin-records-table]')) === null)
    await click(admin.page, '查詢')
    await admin.page.waitForSelector('[data-testid=admin-records-table]')
    await admin.page.waitForFunction(() => document.querySelector('[data-testid=history-list]')?.textContent.includes('R2 驗證用的申請'))
    const rowStatuses = await admin.page.$$eval('[data-testid=admin-records-table] tbody tr', (trs) => trs.map((r) => r.dataset.status))
    check('E8 相同條件再查詢：紀錄更新為今日「工作中」', rowStatuses.length === 1 && rowStatuses[0] === 'WORKING', rowStatuses.join(','))
    check('E8 相同條件再查詢：歷程出現剛提出的申請（整組作廢並重新打卡）',
      (await admin.page.$eval('[data-testid=history-list]', (el) => el.textContent)).includes('整組作廢並重新打卡'))
    check('E8 第二次查詢確實重新向後端取得紀錄與歷程', recordFetches > fetchesBefore.recordFetches && historyFetches > fetchesBefore.historyFetches,
      JSON.stringify({ fetchesBefore, recordFetches, historyFetches }))

    // 清理：撤回這筆待審申請（只動本次專用帳號）
    await c.page.bringToFront()
    await click(c.page, '撤回申請')
    await c.page.waitForFunction(() => !document.querySelector('[data-testid=today-pending]'))
    await assertNoUnhandled('E8', [admin.tracker, c.tracker])
    await ctxAdmin.close()
    await ctxC.close()
  }
} catch (error) {
  check('腳本執行未中斷', false, String(error?.stack ?? error))
} finally {
  await browser?.close()
  stack?.stop()
}

finish()
