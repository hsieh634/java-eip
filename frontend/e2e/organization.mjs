// T05 前置：組織與員工管理的瀏覽器端到端驗證，在【隔離堆疊】執行（測試資料庫＋本次專用帳號，不碰開發庫與 STMAX 帳號）。
// 部門代碼、員工編號、帳號都含本次執行識別碼，重複執行不需要清空資料。
// 新建員工的初始密碼是本次隨機產生的一次性值，不輸出、不寫檔、不截圖。
// 執行：cd frontend; npm run e2e:organization
import crypto from 'node:crypto'
import path from 'node:path'
import { OUT, createRunner, launchBrowser, loginAs } from './helpers.mjs'
import { startIsolatedStack } from './isolated-stack.mjs'
import { click, newPage, noHorizontalOverflow, selectByText, setValue, waitText } from './ui.mjs'

const { check, finish } = createRunner()
let stack
let browser
const DESKTOP = { width: 1280, height: 900 }
const MOBILE = { width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true }

async function assertNoUnhandled(name, trackers) {
  const rejections = (await Promise.all(trackers.map((t) => t.unhandledRejections()))).flat()
  const errors = trackers.flatMap((t) => t.pageErrors)
  check(`${name}：沒有未處理的 Promise 拒絕與 JS 例外`, rejections.length === 0 && errors.length === 0, [...rejections, ...errors].join(' | '))
}

/** 在頁面內以目前登入者呼叫 API（帶 CSRF）。回傳 { status, body }。 */
function apiCall(page, method, url, body) {
  return page.evaluate(
    async (m, u, b) => {
      const token = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '')
      const res = await fetch(u, {
        method: m,
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
        body: b === undefined ? undefined : JSON.stringify(b),
      })
      let json = null
      try {
        json = await res.json()
      } catch {
        // 沒有本文
      }
      return { status: res.status, body: json }
    },
    method,
    url,
    body,
  )
}

async function openOrganization(page, tab = '員工') {
  await page.click('a[href="#/admin/organization"]')
  await page.waitForSelector('[role=tablist]')
  await click(page, tab)
}

/** 員工分頁：以關鍵字搜尋並等清單出現。 */
async function searchEmployees(page, q) {
  await setValue(page, '#emp-q', q)
  await click(page, '搜尋')
  await page.waitForFunction(
    (k) => {
      const trs = [...document.querySelectorAll('[data-testid=employee-table] tbody tr')]
      return trs.length > 0 && trs.every((tr) => tr.innerText.toLowerCase().includes(k.toLowerCase()))
    },
    {},
    q,
  )
}

const rowText = (page, username) => page.$eval(`tr[data-username="${username}"]`, (tr) => tr.innerText)

async function clickRowButton(page, username, label) {
  await page.waitForSelector(`tr[data-username="${username}"]`)
  await page.evaluate(
    (u, l) => [...document.querySelector(`tr[data-username="${u}"]`).querySelectorAll('button')].find((b) => b.textContent.trim() === l).click(),
    username,
    label,
  )
}

const dialogError = (page) => page.$eval('[role=dialog] [role=alert]', (el) => el.textContent)
const waitDialogError = (page, text) =>
  page.waitForFunction((t) => document.querySelector('[role=dialog] .form-error')?.textContent.includes(t), {}, text)

try {
  stack = await startIsolatedStack()
  console.log(`隔離堆疊已啟動：${stack.baseUrl}（測試資料庫；本次專用帳號 ${stack.users.admin.username} 等）`)
  browser = await launchBrowser()
  const base = stack.baseUrl
  const runId = stack.runId
  const RUN = runId.toUpperCase()
  const DEPT_P = `E2E_${RUN}_P`
  const DEPT_Q = `E2E_${RUN}_Q`
  const N1 = { no: `E2E-${RUN}-1`, username: `e2e_n1_${runId}`, name: 'E2E 新人一' }
  const N2 = { no: `E2E-${RUN}-2`, username: `e2e_n2_${runId}`, name: 'E2E 新人二' }
  const newPassword = `Init-${crypto.randomBytes(6).toString('hex')} x` // 一次性；含空白，驗證不被 trim
  const B = stack.users.b.username
  const C = stack.users.c.username
  const B_NO = `EB-${runId}` // E2eSeedRunner 的員工編號
  const ADMIN_NO = `EX-${runId}`

  // ================= G1：管理員入口與部門 =================
  const ctxAdmin = await browser.createBrowserContext()
  const admin = await newPage(ctxAdmin, { viewport: DESKTOP })
  {
    const page = admin.page
    await loginAs(page, stack.users.admin.username, stack.users.admin.password, base)
    check('G1 ADMIN 看得到「組織與員工」入口', (await page.$('a[href="#/admin/organization"]')) !== null)
    await openOrganization(page, '部門')
    await page.waitForSelector('[data-testid=create-department-form]')
    await setValue(page, '#dept-code', 'bad code!')
    await setValue(page, '#dept-name', '不合法')
    await click(page, '建立部門')
    await waitText(page, '部門代碼需為 2～32 個字元')
    check('G1 部門代碼含空白與符號 → 前端擋下', true)
    for (const [code, name] of [[DEPT_P.toLowerCase(), 'E2E 專案部'], [DEPT_Q, 'E2E 行政部']]) {
      await setValue(page, '#dept-code', ` ${code} `)
      await setValue(page, '#dept-name', name)
      await click(page, '建立部門')
      await page.waitForFunction((c) => document.querySelector(`[data-department-code="${c}"]`) !== null, {}, code.toUpperCase())
    }
    check('G1 建立兩個部門；代碼自動去除空白並轉大寫', (await page.$(`[data-department-code="${DEPT_P}"]`)) !== null)
    await setValue(page, '#dept-code', DEPT_Q)
    await setValue(page, '#dept-name', '重複')
    await click(page, '建立部門')
    await waitText(page, '已經存在')
    check('G1 重複部門代碼 → 顯示後端的「已經存在」', true)
  }

  // ================= G2：建立員工、新員工可登入 =================
  {
    const page = admin.page
    await click(page, '員工')
    await page.waitForSelector('[data-testid=employee-search]')
    await searchEmployees(page, runId)
    const seeded = await page.$$eval('[data-testid=employee-table] tbody tr', (trs) => trs.length)
    check('G2 搜尋本次執行識別碼 → 列出本次的 4 個 E2E 帳號', seeded === 4, String(seeded))
    check('G2 未指派的員工顯示「未指派」', (await rowText(page, C)).includes('未指派'))

    await click(page, '新增員工')
    await page.waitForSelector('[data-testid=create-employee-dialog]')
    check('G2 建立畫面說明：初始密碼由管理員交付、不寄送邀請、不強制首次改密碼',
      (await page.$eval('[data-testid=password-delivery-notice]', (el) => el.textContent)).includes('不會寄送邀請信'))
    check('G2 初始密碼欄位預設遮蔽（type=password）', (await page.$eval('#new-emp-password', (el) => el.type)) === 'password')
    await setValue(page, '#new-emp-no', N1.no.toLowerCase())
    await setValue(page, '#new-emp-username', ` ${N1.username.toUpperCase()} `)
    await setValue(page, '#new-emp-name', N1.name)
    await selectByText(page, '#new-emp-dept', DEPT_P)
    await selectByText(page, '#new-emp-manager', B_NO) // 測試庫累積了先前執行的同名帳號，用本次的員工編號選
    await click(page, '確認建立')
    await waitDialogError(page, '請輸入初始密碼')
    check('G2 沒有初始密碼 → 前端擋下，其他輸入保留', (await page.$eval('#new-emp-name', (el) => el.value)) === N1.name)
    await setValue(page, '#new-emp-password', newPassword)
    await click(page, '確認建立')
    await page.waitForSelector('[data-testid=employee-notice]')
    const notice = await page.$eval('[data-testid=employee-notice]', (el) => el.textContent)
    check('G2 建立成功提示（含帳號、提醒安全交付，不顯示密碼）', notice.includes(N1.username) && notice.includes('安全') && !notice.includes(newPassword))
    await searchEmployees(page, runId)
    const n1Row = await rowText(page, N1.username)
    check('G2 新員工列：員工編號轉大寫、部門 P、主管 B', n1Row.includes(N1.no) && n1Row.includes(DEPT_P) && n1Row.includes('E2E 主管 B'), n1Row)
    const n1Api = (await apiCall(page, 'GET', `/api/admin/employees?q=${N1.username}`)).body.items[0]
    check('G2 新員工的主管是本次的 B（不是先前執行的同名帳號）', n1Api.manager?.employeeNo === B_NO, JSON.stringify(n1Api.manager))

    // N2：主管角色前的普通員工，放在 Q 部門
    await click(page, '新增員工')
    await page.waitForSelector('[data-testid=create-employee-dialog]')
    await setValue(page, '#new-emp-no', N2.no)
    await setValue(page, '#new-emp-username', N2.username)
    await setValue(page, '#new-emp-name', N2.name)
    await selectByText(page, '#new-emp-dept', DEPT_Q)
    await setValue(page, '#new-emp-password', newPassword)
    await click(page, '確認建立')
    await page.waitForSelector('[data-testid=employee-notice]')
    await waitText(page, N2.username)

    const ctxN1 = await browser.createBrowserContext()
    const n1 = await newPage(ctxN1, { viewport: DESKTOP })
    await loginAs(n1.page, N1.username.toUpperCase(), newPassword, base)
    check('G2 新員工可用初始密碼登入（帳號不分大小寫，密碼原樣含空白）', (await n1.page.$('.topbar')) !== null)
    check('G2 新員工（EMPLOYEE）看不到「組織與員工」入口', (await n1.page.$('a[href="#/admin/organization"]')) === null)
    await ctxN1.close()
    await assertNoUnhandled('G1～G2', [admin.tracker])
  }

  // ================= G3：修改（原因必填、變更摘要、歷程） =================
  {
    const page = admin.page
    await clickRowButton(page, N1.username, '編輯')
    await page.waitForSelector('[data-testid=edit-employee-dialog]')
    await selectByText(page, '#edit-emp-manager', ADMIN_NO)
    const summary = await page.$eval('[data-testid=change-summary]', (el) => el.textContent)
    check('G3 變更摘要列出「直屬主管：E2E 主管 B → E2E 管理員」', summary.includes('直屬主管') && summary.includes('E2E 主管 B') && summary.includes('E2E 管理員'), summary)
    await click(page, '確認儲存')
    await waitDialogError(page, '必須填寫原因')
    check('G3 改主管沒有原因 → 擋下，輸入保留', (await page.$eval('#edit-emp-manager', (el) => el.options[el.selectedIndex].textContent)).includes('E2E 管理員'))
    await setValue(page, '#edit-emp-reason', 'E2E 調整主管')
    await click(page, '確認儲存')
    await page.waitForFunction(() => document.querySelector('[data-testid=employee-notice]')?.textContent.includes('直屬主管'))
    await page.waitForFunction((u) => document.querySelector(`tr[data-username="${u}"]`)?.innerText.includes('E2E 管理員'), {}, N1.username)
    check('G3 儲存成功，清單顯示新主管', true)
    await clickRowButton(page, N1.username, '歷程')
    await page.waitForSelector('[data-testid=org-history-list]')
    const history = await page.$eval('[data-testid=org-history-list]', (el) => el.innerText)
    check('G3 歷程：建立＋修改兩筆、原因、前後值、操作者；沒有密碼',
      history.includes('建立員工') && history.includes('修改員工') && history.includes('E2E 調整主管') && history.includes(`${B_NO} E2E 主管 B → ${ADMIN_NO} E2E 管理員`)
        && history.includes('E2E 管理員（') && !history.includes(newPassword) && !/密碼|password/i.test(history), history.slice(0, 300))
    await click(page, '關閉')
  }

  // ================= G4：過期表單 → 409，保留輸入，明確重新載入 =================
  {
    const ctx2 = await browser.createBrowserContext()
    const admin2 = await newPage(ctx2, { viewport: DESKTOP })
    await loginAs(admin2.page, stack.users.admin.username, stack.users.admin.password, base)
    await openOrganization(admin2.page)
    await searchEmployees(admin2.page, runId)
    await clickRowButton(admin2.page, N2.username, '編輯')
    await admin2.page.waitForSelector('[data-testid=edit-employee-dialog]')

    const page = admin.page
    await clickRowButton(page, N2.username, '編輯')
    await page.waitForSelector('[data-testid=edit-employee-dialog]')
    await setValue(page, '#edit-emp-name', 'E2E 新人二（甲改）')
    await click(page, '確認儲存')
    await page.waitForFunction(() => document.querySelector('[data-testid=employee-notice]')?.textContent.includes('顯示名稱'))

    await admin2.page.bringToFront()
    await setValue(admin2.page, '#edit-emp-name', 'E2E 新人二（乙改）')
    await click(admin2.page, '確認儲存')
    await admin2.page.waitForSelector('[data-testid=employee-conflict]')
    check('G4 另一位管理員先存 → 過期表單顯示衝突（409），不覆蓋', (await dialogError(admin2.page)).includes('已被其他管理員修改'))
    check('G4 衝突後輸入仍保留', (await admin2.page.$eval('#edit-emp-name', (el) => el.value)) === 'E2E 新人二（乙改）')
    await click(admin2.page, '載入最新資料（會取代目前輸入）')
    await admin2.page.waitForFunction(() => document.querySelector('#edit-emp-name')?.value === 'E2E 新人二（甲改）')
    check('G4 明確按「載入最新資料」後才換成最新內容（沒有自動重送）', true)
    await click(admin2.page, '取消')
    await assertNoUnhandled('G4', [admin2.tracker])
    await ctx2.close()
  }

  // ================= G5：停用或改角色 → 對方舊 Session 失效 =================
  {
    const ctxN1 = await browser.createBrowserContext()
    const n1 = await newPage(ctxN1, { viewport: DESKTOP })
    await loginAs(n1.page, N1.username, newPassword, base)
    const ctxN2 = await browser.createBrowserContext()
    const n2 = await newPage(ctxN2, { viewport: DESKTOP })
    await loginAs(n2.page, N2.username, newPassword, base)

    const page = admin.page
    await page.bringToFront()
    await clickRowButton(page, N1.username, '編輯')
    await page.waitForSelector('[data-testid=edit-employee-dialog]')
    await setValue(page, '#edit-emp-enabled', 'false')
    check('G5 停用時提示「對方目前的登入會失效」', (await page.$eval('[data-testid=change-summary]', (el) => el.textContent)).includes('登入會失效'))
    await setValue(page, '#edit-emp-reason', 'E2E 停用測試')
    await click(page, '確認儲存')
    await page.waitForFunction((u) => document.querySelector(`tr[data-username="${u}"]`)?.dataset.enabled === 'false', {}, N1.username)

    await clickRowButton(page, N2.username, '編輯')
    await page.waitForSelector('[data-testid=edit-employee-dialog]')
    await setValue(page, '#edit-emp-role', 'MANAGER')
    await setValue(page, '#edit-emp-reason', 'E2E 升為主管')
    await click(page, '確認儲存')
    await page.waitForFunction((u) => document.querySelector(`tr[data-username="${u}"]`)?.innerText.includes('主管'), {}, N2.username)

    const s1 = await apiCall(n1.page, 'GET', '/api/attendance/me/today')
    const s2 = await apiCall(n2.page, 'GET', '/api/attendance/me/today')
    check('G5 被停用者的舊 Session 下一個請求 → 401', s1.status === 401, String(s1.status))
    check('G5 被改角色者的舊 Session 下一個請求 → 401', s2.status === 401, String(s2.status))
    await n2.page.reload({ waitUntil: 'networkidle0' })
    await n2.page.waitForSelector('#username')
    check('G5 重新整理後回到登入頁', true)
    await loginAs(n2.page, N2.username, newPassword, base)
    check('G5 重新登入後取得新角色（主管）', (await n2.page.$eval('.topbar .badge', (el) => el.textContent)) === '主管')
    const relogin = await n1.page.evaluate(async (u, p) => {
      await fetch('/api/auth/me')
      const token = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '')
      return (await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: JSON.stringify({ username: u, password: p }) })).status
    }, N1.username, newPassword)
    check('G5 停用者不能重新登入', relogin === 401, String(relogin))
    await ctxN1.close()
    await ctxN2.close()
  }

  // ================= G6：規則擋下（循環、無效主管、部門仍有啟用員工、自己、仍有部屬） =================
  {
    const page = admin.page
    // B 的主管設為 N2（現在是主管）；再把 N2 的主管設為 B → 循環
    await clickRowButton(page, B, '編輯')
    await page.waitForSelector('[data-testid=edit-employee-dialog]')
    await selectByText(page, '#edit-emp-manager', N2.no)
    await setValue(page, '#edit-emp-reason', 'E2E 設主管')
    await click(page, '確認儲存')
    await page.waitForFunction((u) => document.querySelector(`tr[data-username="${u}"]`)?.innerText.includes('E2E 新人二'), {}, B)
    await clickRowButton(page, N2.username, '編輯')
    await page.waitForSelector('[data-testid=edit-employee-dialog]')
    await selectByText(page, '#edit-emp-manager', B_NO)
    await setValue(page, '#edit-emp-reason', 'E2E 循環')
    await click(page, '確認儲存')
    await waitDialogError(page, '循環')
    check('G6 主管循環（B→N2、N2→B）→ 擋下並說明', true)
    await click(page, '取消')

    const options = await apiCall(page, 'GET', '/api/admin/employees/manager-options')
    check('G6 主管選項只有啟用中的 MANAGER／ADMIN（不含員工 C、已停用的 N1）',
      options.body.options.every((o) => o.role !== 'EMPLOYEE') && !options.body.options.some((o) => o.employeeNo === N1.no))
    const n2Detail = (await apiCall(page, 'GET', `/api/admin/employees?q=${N2.username}`)).body.items[0]
    const cDetail = (await apiCall(page, 'GET', `/api/admin/employees?q=${C}`)).body.items[0]
    const invalid = await apiCall(page, 'PUT', `/api/admin/employees/${n2Detail.id}`, {
      displayName: n2Detail.displayName, role: n2Detail.role, enabled: true, departmentId: n2Detail.department?.id ?? null,
      managerId: cDetail.id, expectedVersion: n2Detail.version, reason: '直接呼叫',
    })
    check('G6 直接呼叫 API 把員工 C（EMPLOYEE）設為主管 → 409 MANAGER_INVALID', invalid.status === 409 && invalid.body.code === 'MANAGER_INVALID', JSON.stringify(invalid))

    // 自己：不能停用或降級
    await searchEmployees(page, stack.users.admin.username)
    await clickRowButton(page, stack.users.admin.username, '編輯')
    await page.waitForSelector('[data-testid=edit-employee-dialog]')
    const locked = await page.$eval('#edit-emp-role', (el) => el.disabled) && (await page.$eval('#edit-emp-enabled', (el) => el.disabled))
    check('G6 自己的角色與啟用狀態在畫面上鎖定', locked)
    await click(page, '取消')
    const meRow = (await apiCall(page, 'GET', `/api/admin/employees?q=${stack.users.admin.username}`)).body.items[0]
    const selfDemote = await apiCall(page, 'PUT', `/api/admin/employees/${meRow.id}`, {
      displayName: meRow.displayName, role: 'EMPLOYEE', enabled: true, departmentId: null, managerId: null, expectedVersion: meRow.version, reason: '自己降級',
    })
    check('G6 直接呼叫 API 降級自己 → 403 SELF_ADMIN_CHANGE_FORBIDDEN', selfDemote.status === 403 && selfDemote.body.code === 'SELF_ADMIN_CHANGE_FORBIDDEN', JSON.stringify(selfDemote))

    // 仍有部屬：N2 是 B 的直屬主管 → 停用 N2 被擋下
    await searchEmployees(page, runId)
    await clickRowButton(page, N2.username, '編輯')
    await page.waitForSelector('[data-testid=edit-employee-dialog]')
    await setValue(page, '#edit-emp-enabled', 'false')
    await setValue(page, '#edit-emp-reason', 'E2E 停用主管')
    await click(page, '確認儲存')
    await waitDialogError(page, '直屬主管')
    check('G6 仍是別人的直屬主管 → 不能停用（不自動改派）', (await dialogError(page)).includes('1 位員工'))
    await click(page, '取消')

    // 部門 Q 有啟用中的 N2 → 不能停用
    await click(page, '部門')
    await page.waitForSelector(`[data-department-code="${DEPT_Q}"]`)
    await page.evaluate((c) => [...document.querySelector(`[data-department-code="${c}"]`).querySelectorAll('button')].find((b) => b.textContent.trim() === '編輯').click(), DEPT_Q)
    await page.waitForSelector('[data-testid=edit-department-dialog]')
    await setValue(page, '#edit-dept-active', 'false')
    check('G6 停用部門前提示目前啟用中的員工數', (await page.$eval('[data-testid=deactivate-warning]', (el) => el.textContent)).includes('1 位啟用中的員工'))
    await click(page, '確認儲存')
    await waitDialogError(page, '必須填寫原因')
    await setValue(page, '#edit-dept-reason', 'E2E 停用部門')
    await click(page, '確認儲存')
    await waitDialogError(page, '啟用中的員工')
    check('G6 部門仍有啟用中的員工 → 409 擋下', true)
    await click(page, '取消')
    // 部門 P 只剩已停用的 N1 → 可以停用
    await page.evaluate((c) => [...document.querySelector(`[data-department-code="${c}"]`).querySelectorAll('button')].find((b) => b.textContent.trim() === '編輯').click(), DEPT_P)
    await page.waitForSelector('[data-testid=edit-department-dialog]')
    await setValue(page, '#edit-dept-active', 'false')
    await setValue(page, '#edit-dept-reason', 'E2E 停用部門')
    await click(page, '確認儲存')
    await page.waitForFunction((c) => document.querySelector(`[data-department-code="${c}"]`)?.dataset.active === 'false', {}, DEPT_P)
    check('G6 只剩已停用員工的部門可以停用', true)
    await page.evaluate((c) => [...document.querySelector(`[data-department-code="${c}"]`).querySelectorAll('button')].find((b) => b.textContent.trim() === '歷程').click(), DEPT_P)
    await page.waitForSelector('[data-testid=org-history-list]')
    const deptHistory = await page.$eval('[data-testid=org-history-list]', (el) => el.innerText)
    check('G6 部門歷程：建立與停用（含原因、前後值）', deptHistory.includes('建立部門') && deptHistory.includes('E2E 停用部門') && deptHistory.includes('啟用 → 停用'))
    await click(page, '關閉')
    await assertNoUnhandled('G3～G6', [admin.tracker])
  }

  // ================= G7：未指派員工照常打卡、填工時 =================
  {
    const ctxC = await browser.createBrowserContext()
    const c = await newPage(ctxC, { viewport: DESKTOP })
    await loginAs(c.page, C, stack.users.c.password, base)
    const project = await apiCall(admin.page, 'POST', '/api/admin/projects', { code: `E2E_${RUN}_ORG`, name: 'E2E 組織測試專案', description: null })
    const clockIn = await apiCall(c.page, 'POST', '/api/attendance/clock-in')
    const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Taipei' }).format(new Date())
    const entry = await apiCall(c.page, 'POST', '/api/timesheets/me', {
      requestId: crypto.randomUUID(), workDate: today, projectId: project.body.id, description: '未指派部門也能填', minutes: 30,
    })
    check('G7 沒有部門與主管的員工 C：打卡 200、填工時 201', clockIn.status === 200 && entry.status === 201, `${clockIn.status}/${entry.status}`)
    await ctxC.close()
  }

  // ================= G8：EMPLOYEE／MANAGER 沒有入口、API 403 =================
  {
    for (const [label, user] of [['員工', stack.users.c], ['主管', stack.users.b]]) {
      const ctx = await browser.createBrowserContext()
      const ui = await newPage(ctx, { viewport: DESKTOP })
      await loginAs(ui.page, user.username, user.password, base)
      check(`G8 ${label}沒有「組織與員工」入口`, (await ui.page.$('a[href="#/admin/organization"]')) === null)
      await ui.page.goto(`${base}/#/admin/organization`, { waitUntil: 'networkidle0' })
      await waitText(ui.page, '只有管理員可以使用這個功能')
      const statuses = {
        departments: (await apiCall(ui.page, 'GET', '/api/admin/departments')).status,
        employees: (await apiCall(ui.page, 'GET', '/api/admin/employees')).status,
        createDept: (await apiCall(ui.page, 'POST', '/api/admin/departments', { code: 'HACK', name: 'x' })).status,
        createEmp: (await apiCall(ui.page, 'POST', '/api/admin/employees', { employeeNo: 'HACK-1', username: 'hack', displayName: 'x', role: 'ADMIN', initialPassword: 'x' })).status,
        update: (await apiCall(ui.page, 'PUT', '/api/admin/employees/1', { displayName: 'x', role: 'ADMIN', enabled: true, expectedVersion: 0, reason: 'x' })).status,
      }
      check(`G8 ${label}直接呼叫組織 API → 全部 403`, Object.values(statuses).every((s) => s === 403), JSON.stringify(statuses))
      await ctx.close()
    }
  }

  // ================= G9：載入中、錯誤＋重試、空結果 =================
  {
    const ctx = await browser.createBrowserContext()
    const ui = await newPage(ctx, { viewport: DESKTOP })
    await loginAs(ui.page, stack.users.admin.username, stack.users.admin.password, base)
    await ui.page.setRequestInterception(true)
    let mode = 'hold'
    const held = []
    ui.page.on('request', (r) => {
      if (r.isInterceptResolutionHandled()) return
      const url = new URL(r.url())
      if (r.method() === 'GET' && url.pathname === '/api/admin/employees') {
        if (mode === 'hold') return void held.push(r)
        if (mode === 'fail') {
          mode = 'pass'
          return void r.respond({ status: 500, contentType: 'application/json', body: '{"code":"INTERNAL_ERROR","message":"系統發生錯誤，請稍後再試"}' })
        }
      }
      void r.continue()
    })
    await openOrganization(ui.page)
    await waitText(ui.page, '載入中…')
    check('G9 清單載入中顯示「載入中…」', true)
    mode = 'fail'
    held.splice(0).forEach((r) => void r.respond({ status: 500, contentType: 'application/json', body: '{"code":"INTERNAL_ERROR","message":"系統發生錯誤，請稍後再試"}' }))
    await waitText(ui.page, '無法載入員工')
    check('G9 載入失敗 → 錯誤訊息＋重試按鈕', true)
    mode = 'pass'
    await click(ui.page, '重試')
    await ui.page.waitForSelector('[data-testid=employee-table]')
    check('G9 按重試後成功載入', true)
    await setValue(ui.page, '#emp-q', `nobody-${runId}`)
    await click(ui.page, '搜尋')
    await ui.page.waitForSelector('[data-testid=employee-empty]')
    check('G9 沒有符合條件 → 空狀態說明', (await ui.page.$eval('[data-testid=employee-empty]', (el) => el.textContent)).includes('沒有符合條件'))
    await ctx.close()
  }

  // ================= G10：手機尺寸 =================
  {
    const ctx = await browser.createBrowserContext()
    const ui = await newPage(ctx, { viewport: MOBILE })
    await loginAs(ui.page, stack.users.admin.username, stack.users.admin.password, base)
    await ui.page.goto(`${base}/#/admin/organization`, { waitUntil: 'networkidle0' })
    await searchEmployees(ui.page, runId)
    check('G10 手機：員工清單沒有橫向捲動', await noHorizontalOverflow(ui.page))
    await ui.page.screenshot({ path: path.join(OUT, 'organization-01-mobile-employees.png') })
    await clickRowButton(ui.page, N2.username, '編輯')
    await ui.page.waitForSelector('[data-testid=edit-employee-dialog]')
    const fits = await ui.page.$eval('.modal', (el) => el.getBoundingClientRect().width <= window.innerWidth && el.getBoundingClientRect().left >= 0)
    const selectsFit = await ui.page.$$eval('.modal select, .modal input, .modal textarea', (els) => els.every((el) => el.getBoundingClientRect().right <= window.innerWidth + 1))
    check('G10 手機：編輯視窗與欄位完整顯示', fits && selectsFit)
    const saveHeight = await ui.page.evaluate(() => [...document.querySelectorAll('.modal button')].find((b) => b.textContent.trim() === '確認儲存').getBoundingClientRect().height)
    check('G10 手機：按鈕高度 ≥ 44px', saveHeight >= 44, `${saveHeight}px`)
    await ui.page.screenshot({ path: path.join(OUT, 'organization-02-mobile-edit.png') })
    await click(ui.page, '取消')
    await click(ui.page, '部門')
    await ui.page.waitForSelector('[data-testid=department-table]')
    check('G10 手機：部門清單沒有橫向捲動', await noHorizontalOverflow(ui.page))
    await ctx.close()
  }

  await assertNoUnhandled('全程', [admin.tracker])
} catch (error) {
  check('腳本執行未中斷', false, String(error?.stack ?? error))
} finally {
  await browser?.close()
  stack?.stop()
}

finish()
