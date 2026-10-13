// T05：請假申請與主管審核的瀏覽器端到端驗證，在【隔離堆疊】執行（測試資料庫＋本次專用帳號；不碰開發庫、不對開發庫建立假單）。
// 組織準備（直屬主管、第二位管理員）以本次的 E2E 管理員呼叫組織 API 完成，只影響本次帳號。
// 競態與結果不明用「請求攔截」安排（轉送後中斷、偽造回應），不靠 sleep 碰運氣。
// 執行：cd frontend; npm run e2e:leave
import crypto from 'node:crypto'
import path from 'node:path'
import { OUT, createRunner, launchBrowser, loginAs } from './helpers.mjs'
import { startIsolatedStack } from './isolated-stack.mjs'
import { click, newPage, noHorizontalOverflow, selectByText, setValue, taipeiDate, waitText } from './ui.mjs'

const { check, finish } = createRunner()
let stack
let browser
const DESKTOP = { width: 1280, height: 900 }
const MOBILE = { width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true }
const settle = (page, ms = 500) => page.evaluate((t) => new Promise((r) => setTimeout(r, t)), ms)

async function assertNoUnhandled(name, trackers) {
  const rejections = (await Promise.all(trackers.map((t) => t.unhandledRejections()))).flat()
  const errors = trackers.flatMap((t) => t.pageErrors)
  check(`${name}：沒有未處理的 Promise 拒絕與 JS 例外`, rejections.length === 0 && errors.length === 0, [...rejections, ...errors].join(' | '))
}

/** 在頁面內以目前登入者呼叫 API（帶 CSRF）。 */
function apiCall(page, method, url, body) {
  return page.evaluate(
    async (m, u, b) => {
      const token = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '')
      const res = await fetch(u, { method: m, headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: b === undefined ? undefined : JSON.stringify(b) })
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

async function openLeave(page) {
  await page.click('a[href="#/leave"]')
  await page.waitForSelector('[data-testid=leave-form]')
  await page.waitForSelector('[data-testid=approver-preview]')
}

async function fillLeave(page, { type = '事假', date, start, end, reason }) {
  await page.waitForSelector('[data-testid=leave-form][data-phase=editing]')
  await selectByText(page, '#lv-type', type)
  await setValue(page, '#lv-date', date)
  await setValue(page, '#lv-start', start)
  await setValue(page, '#lv-end', end)
  await setValue(page, '#lv-reason', reason)
}

const rowsOf = (page, testId) =>
  page.$$eval(`[data-testid=${testId}-table] tbody tr`, (trs) => trs.map((tr) => ({ id: Number(tr.dataset.leaveId), status: tr.dataset.status, reason: tr.dataset.reason })))

async function waitRow(page, testId, reason, status) {
  await page.waitForFunction(
    (t, r, s) => [...document.querySelectorAll(`[data-testid=${t}-table] tbody tr`)].some((tr) => tr.dataset.reason === r && (!s || tr.dataset.status === s)),
    {},
    testId,
    reason,
    status ?? null,
  )
  return (await rowsOf(page, testId)).find((r) => r.reason === reason && (!status || r.status === status))
}

async function openDetail(page, testId, id) {
  await page.evaluate((t, i) => document.querySelector(`[data-testid=${t}-table] tr[data-leave-id="${i}"] button`).click(), testId, id)
  await page.waitForSelector('[data-testid=leave-summary]')
}

const leaveError = (page) => page.$eval('[data-testid=leave-error]', (el) => el.textContent)

try {
  stack = await startIsolatedStack()
  console.log(`隔離堆疊已啟動：${stack.baseUrl}（測試資料庫；本次專用帳號 ${stack.users.a.username} 等）`)
  browser = await launchBrowser()
  const base = stack.baseUrl
  const runId = stack.runId
  const D1 = taipeiDate(1)
  const D2 = taipeiDate(2)
  const D3 = taipeiDate(3)
  const admin2 = { username: `e2e_admin2_${runId}`, password: `Init-${crypto.randomBytes(6).toString('hex')}` }

  // ================= 準備：組織（只動本次帳號） =================
  const ctxAdmin = await browser.createBrowserContext()
  const admin = await newPage(ctxAdmin, { viewport: DESKTOP })
  const ids = {}
  {
    const page = admin.page
    await loginAs(page, stack.users.admin.username, stack.users.admin.password, base)
    for (const key of ['a', 'b', 'c', 'admin']) {
      ids[key] = (await apiCall(page, 'GET', `/api/admin/employees?q=${stack.users[key].username}`)).body.items[0]
    }
    const a = ids.a
    const setManager = await apiCall(page, 'PUT', `/api/admin/employees/${a.id}`, {
      displayName: a.displayName, role: a.role, enabled: true, departmentId: null, managerId: ids.b.id, expectedVersion: a.version, reason: 'E2E 設定主管',
    })
    const created = await apiCall(page, 'POST', '/api/admin/employees', {
      employeeNo: `EY-${runId}`, username: admin2.username, displayName: 'E2E 管理員二', role: 'ADMIN', departmentId: null, managerId: null,
      initialPassword: admin2.password, reason: 'E2E 第二位管理員',
    })
    check('準備：A 的直屬主管設為 B；建立第二位管理員（本次專用）', setManager.status === 200 && created.status === 201, `${setManager.status}/${created.status}`)
    ids.admin2 = created.body
  }

  // ================= V1：員工送出 =================
  const ctxA = await browser.createBrowserContext()
  const A = await newPage(ctxA, { viewport: DESKTOP })
  {
    const page = A.page
    await loginAs(page, stack.users.a.username, stack.users.a.password, base)
    check('V1 員工有「請假」入口、沒有「請假審核」與「請假管理」', (await page.$('a[href="#/leave"]')) !== null && (await page.$('a[href="#/leave/approvals"]')) === null && (await page.$('a[href="#/admin/leave"]')) === null)
    await openLeave(page)
    const preview = await page.$eval('[data-testid=approver-preview]', (el) => ({ source: el.dataset.source, text: el.textContent }))
    check('V1 顯示預計審核人：E2E 主管 B（直屬主管），並說明只是提示', preview.source === 'MANAGER' && preview.text.includes('E2E 主管 B') && preview.text.includes('只是提示'), preview.text)
    await fillLeave(page, { type: '病假', date: D1, start: '09:00', end: '12:00', reason: 'E2E 看診' })
    const duration = await page.$eval('[data-testid=leave-duration]', (el) => el.textContent)
    check('V1 顯示申請時段長度 3 小時，並註明未扣除休息時間、不是計薪工時', duration.includes('3 小時') && duration.includes('未自動扣除休息時間') && duration.includes('不是計薪工時'))
    await click(page, '送出申請')
    await waitText(page, '已送出請假申請')
    check('V1 送出成功：提示等待 B 審核', (await page.$eval('[data-testid=leave-notice]', (el) => el.textContent)).includes('E2E 主管 B'))
    const row = await waitRow(page, 'my-leaves', 'E2E 看診', 'PENDING')
    check('V1 我的請假列出這筆（待審）', Boolean(row))
    check('V1 送出後表單換成新的空白表單', (await page.$eval('#lv-reason', (el) => el.value)) === '')

    // V2：重疊被拒、輸入保留；相鄰可以
    await fillLeave(page, { date: D1, start: '11:00', end: '13:00', reason: 'E2E 重疊' })
    await click(page, '送出申請')
    await page.waitForFunction(() => document.querySelector('[data-testid=leave-error]')?.textContent.includes('重疊'))
    check('V2 時段重疊 → 顯示原因、表單回到可編輯且輸入保留', (await page.$eval('#lv-reason', (el) => el.value)) === 'E2E 重疊' && (await page.$eval('[data-testid=leave-form]', (el) => el.dataset.phase)) === 'editing')
    await setValue(page, '#lv-start', '12:00')
    await setValue(page, '#lv-reason', 'E2E 下午相鄰')
    await click(page, '送出申請')
    await waitText(page, '已送出請假申請')
    await waitRow(page, 'my-leaves', 'E2E 下午相鄰', 'PENDING')
    check('V2 12:00 開始（前一筆 12:00 結束）可以送出', true)
    await fillLeave(page, { date: D1, start: '15:00', end: '14:00', reason: 'E2E 反向' })
    await click(page, '送出申請')
    await page.waitForFunction(() => document.querySelector('[data-testid=leave-error]')?.textContent.includes('結束時間必須晚於開始時間'))
    check('V2 結束早於開始 → 前端擋下（說明不支援跨午夜）', (await leaveError(page)).includes('跨午夜'))
    await assertNoUnhandled('V1～V2', [A.tracker])
  }

  // ================= V3：主管核准與退回 =================
  const ctxB = await browser.createBrowserContext()
  const B = await newPage(ctxB, { viewport: DESKTOP })
  {
    const page = B.page
    await loginAs(page, stack.users.b.username, stack.users.b.password, base)
    check('V3 主管有「請假審核」入口、沒有「請假管理」', (await page.$('a[href="#/leave/approvals"]')) !== null && (await page.$('a[href="#/admin/leave"]')) === null)
    await page.click('a[href="#/leave/approvals"]')
    const first = await waitRow(page, 'approvals', 'E2E 看診', 'PENDING')
    const second = await waitRow(page, 'approvals', 'E2E 下午相鄰', 'PENDING')
    check('V3 待審清單只有指派給 B 的兩筆', (await rowsOf(page, 'approvals')).length === 2)
    await openDetail(page, 'approvals', first.id)
    await click(page, '核准')
    const confirmText = await page.$eval('[data-testid=decision-confirm]', (el) => el.textContent)
    check('V3 核准確認列出申請人、假別、日期、時間、原因', confirmText.includes('E2E 員工 A') && confirmText.includes('病假') && confirmText.includes(D1) && confirmText.includes('09:00～12:00') && confirmText.includes('E2E 看診'))
    await click(page, '確認核准')
    await waitText(page, '已核准 E2E 員工 A')
    await page.waitForFunction(() => document.querySelector('[data-testid=leave-summary]')?.dataset.status === 'APPROVED')
    check('V3 核准後詳情顯示已核准、歷程有核准', (await page.$eval('[data-testid=leave-history]', (el) => el.innerText)).includes('核准'))
    await click(page, '關閉')
    await openDetail(page, 'approvals', second.id)
    await click(page, '退回')
    await click(page, '確認退回')
    await page.waitForFunction(() => document.querySelector('[data-testid=decision-confirm] [role=alert]')?.textContent.includes('退回原因必填'))
    check('V3 退回原因空白 → 擋下', true)
    await setValue(page, '#reject-reason', 'E2E 下午有客戶會議，請改期')
    await click(page, '確認退回')
    await page.waitForFunction(() => document.querySelector('[data-testid=leave-summary]')?.dataset.status === 'REJECTED')
    check('V3 退回成功', true)
    await click(page, '關閉')
    await assertNoUnhandled('V3', [B.tracker])
  }

  // ================= V4：員工看結果、撤回 =================
  {
    const page = A.page
    await page.bringToFront()
    await click(page, '重新整理')
    const rejected = await waitRow(page, 'my-leaves', 'E2E 下午相鄰', 'REJECTED')
    await waitRow(page, 'my-leaves', 'E2E 看診', 'APPROVED')
    check('V4 員工清單顯示已核准與已退回', true)
    await openDetail(page, 'my-leaves', rejected.id)
    check('V4 詳情顯示退回原因', (await page.$eval('[data-testid=decision-reason]', (el) => el.textContent)).includes('客戶會議'))
    check('V4 已結案的申請沒有撤回按鈕', await page.$eval('[data-testid=leave-detail]', (el) => !el.innerText.includes('撤回這筆申請')))
    await click(page, '關閉')

    await fillLeave(page, { type: '特休', date: D2, start: '13:30', end: '18:00', reason: 'E2E 要撤回的' })
    await click(page, '送出申請')
    const row = await waitRow(page, 'my-leaves', 'E2E 要撤回的', 'PENDING')
    await openDetail(page, 'my-leaves', row.id)
    await click(page, '撤回這筆申請')
    check('V4 撤回前有確認說明', (await page.$eval('[data-testid=withdraw-confirm]', (el) => el.textContent)).includes('不能再恢復'))
    await click(page, '確認撤回')
    await page.waitForFunction(() => document.querySelector('[data-testid=leave-summary]')?.dataset.status === 'WITHDRAWN')
    check('V4 撤回成功、詳情顯示已撤回', true)
    await click(page, '關閉')
  }

  // ================= V5：越權與沒有審核人 =================
  {
    const ctxC = await browser.createBrowserContext()
    const C = await newPage(ctxC, { viewport: DESKTOP })
    await loginAs(C.page, stack.users.c.username, stack.users.c.password, base)
    const someoneElse = (await rowsOf(A.page, 'my-leaves'))[0].id
    const statuses = {
      othersDetail: (await apiCall(C.page, 'GET', `/api/leave/me/${someoneElse}`)).status,
      approvals: (await apiCall(C.page, 'GET', '/api/leave/approvals')).status,
      approve: (await apiCall(C.page, 'POST', `/api/leave/approvals/${someoneElse}/approve`, { expectedVersion: 0 })).status,
      admin: (await apiCall(C.page, 'GET', '/api/admin/leave/requests')).status,
    }
    check('V5 員工 C：他人申請 404、審核清單 403、核准他人 404、管理 API 403', statuses.othersDetail === 404 && statuses.approvals === 403 && statuses.approve === 404 && statuses.admin === 403, JSON.stringify(statuses))
    await openLeave(C.page)
    check('V5 沒有主管也沒有指定 → 預計審核人提示需要管理員設定', (await C.page.$eval('[data-testid=approver-preview]', (el) => el.dataset.source)) === 'NONE')
    await fillLeave(C.page, { date: D1, start: '09:00', end: '10:00', reason: 'E2E 沒有審核人' })
    await click(C.page, '送出申請')
    await C.page.waitForFunction(() => document.querySelector('[data-testid=leave-error]')?.textContent.includes('請管理員設定'))
    check('V5 送出被拒並清楚提示（沒有建立）', (await C.page.$eval('[data-testid=my-leaves]', (el) => el.innerText)).includes('沒有請假申請'))
    await ctxC.close()
    const bView = await apiCall(B.page, 'GET', `/api/leave/approvals/${someoneElse}`)
    check('V5 主管只能讀指派給自己的（A 的申請本來就指派給 B → 200）', bView.status === 200)
  }

  // ================= V6：Max 情境——管理員沒有主管，設定指定審核人；不能自審 =================
  {
    const page = admin.page
    await page.bringToFront()
    await openLeave(page)
    check('V6 管理員沒有主管 → 預計審核人：沒有', (await page.$eval('[data-testid=approver-preview]', (el) => el.dataset.source)) === 'NONE')
    await page.click('a[href="#/admin/leave"]')
    await click(page, '指定請假審核人')
    await page.waitForSelector('[data-testid=setting-search]')
    await setValue(page, '#setting-q', stack.users.admin.username)
    await click(page, '搜尋')
    await page.waitForSelector(`[data-testid=setting-search-results] button[data-username="${stack.users.admin.username}"]`)
    await page.click(`[data-testid=setting-search-results] button[data-username="${stack.users.admin.username}"]`)
    await page.waitForSelector('[data-testid=setting-form]')
    check('V6 設定畫面顯示目前未指定', (await page.$eval('[data-testid=setting-current]', (el) => el.textContent)).includes('未指定'))
    const options = await page.$$eval('#setting-approver option', (os) => os.map((o) => o.textContent))
    check('V6 審核人選項不含自己', !options.some((o) => o.includes(`EX-${runId}`)))
    await selectByText(page, '#setting-approver', `EB-${runId}`)
    await click(page, '確認儲存')
    await page.waitForFunction(() => document.querySelector('[data-testid=setting-form] [role=alert]')?.textContent.includes('修改原因必填'))
    await setValue(page, '#setting-reason', 'E2E 管理員沒有主管，由 B 審核')
    await click(page, '確認儲存')
    await page.waitForSelector('[data-testid=setting-notice]')
    await page.waitForFunction(() => document.querySelector('[data-testid=setting-current]')?.textContent.includes('E2E 主管 B'))
    check('V6 儲存後顯示指定審核人 B、歷程有原因', (await page.$eval('[data-testid=setting-editor]', (el) => el.innerText)).includes('E2E 管理員沒有主管，由 B 審核'))
    const me = (await apiCall(page, 'GET', `/api/admin/employees/${ids.admin.id}`)).body
    check('V6 指定審核人不改組織主管關係（管理員仍沒有直屬主管）', me.manager === null)

    await openLeave(page)
    await page.waitForFunction(() => document.querySelector('[data-testid=approver-preview]')?.dataset.source === 'DESIGNATED')
    await fillLeave(page, { date: D3, start: '09:00', end: '18:00', reason: 'E2E 管理員請假' })
    await click(page, '送出申請')
    const own = await waitRow(page, 'my-leaves', 'E2E 管理員請假', 'PENDING')
    check('V6 管理員的申請指派給 B', (await apiCall(page, 'GET', `/api/leave/me/${own.id}`)).body.request.approver.employeeNo === `EB-${runId}`)
    const selfApprove = await apiCall(page, 'POST', `/api/leave/approvals/${own.id}/approve`, { expectedVersion: 0 })
    check('V6 管理員不能審核自己的申請（404，不是指派給自己）', selfApprove.status === 404)

    await page.click('a[href="#/admin/leave"]')
    await waitRow(page, 'admin-leaves', 'E2E 管理員請假', 'PENDING')
    await openDetail(page, 'admin-leaves', own.id)
    await page.waitForSelector('[data-testid=self-reassign-note]')
    check('V6 請假管理：自己的申請只顯示「需另一位管理員改派」，沒有改派表單', (await page.$('[data-testid=reassign-form]')) === null)
    await click(page, '關閉')
    ids.ownLeave = own.id
    await assertNoUnhandled('V6', [admin.tracker])
  }

  // ================= V7：另一位管理員改派；原審核人失去權限 =================
  {
    const ctx2 = await browser.createBrowserContext()
    const X = await newPage(ctx2, { viewport: DESKTOP })
    await loginAs(X.page, admin2.username, admin2.password, base)
    await X.page.click('a[href="#/admin/leave"]')
    await waitRow(X.page, 'admin-leaves', 'E2E 管理員請假', 'PENDING')
    await openDetail(X.page, 'admin-leaves', ids.ownLeave)
    await X.page.waitForSelector('[data-testid=reassign-form]')
    const choices = await X.page.$$eval('#reassign-to option', (os) => os.map((o) => o.textContent))
    check('V7 改派選項不含申請人與目前審核人', !choices.some((o) => o.includes(`EX-${runId}`) || o.includes(`EB-${runId}`)))
    await selectByText(X.page, "#reassign-to", `EY-${runId}`.toUpperCase()) // 經組織 API 建立：員工編號已轉大寫
    await click(X.page, '檢查改派內容')
    await X.page.waitForFunction(() => document.querySelector('[data-testid=reassign-form] [role=alert]')?.textContent.includes('改派原因必填'))
    await setValue(X.page, '#reassign-reason', 'E2E B 休假中')
    await click(X.page, '檢查改派內容')
    const confirm = await X.page.$eval('[data-testid=reassign-confirm]', (el) => el.textContent)
    check('V7 改派確認列出前後審核人與原因', confirm.includes('E2E 主管 B → E2E 管理員二') && confirm.includes('E2E B 休假中'))
    await click(X.page, '確認改派')
    await waitText(X.page, '已將申請')
    const lost = await apiCall(B.page, 'GET', `/api/leave/approvals/${ids.ownLeave}`)
    check('V7 改派後原審核人 B 讀不到（404）', lost.status === 404)
    // notice先更新，詳情稍後重讀；等新審核人真正顯示再操作，避免點到重載中的位置。
    await X.page.waitForFunction(() => document.querySelector('[data-testid=leave-summary]')?.textContent.includes('E2E 管理員二'))
    await click(X.page, '關閉')
    await X.page.click('a[href="#/leave/approvals"]')
    const mine = await waitRow(X.page, 'approvals', 'E2E 管理員請假', 'PENDING')
    await openDetail(X.page, 'approvals', mine.id)
    await click(X.page, '核准')
    await click(X.page, '確認核准')
    await X.page.waitForFunction(() => document.querySelector('[data-testid=leave-summary]')?.dataset.status === 'APPROVED')
    const history = await X.page.$eval('[data-testid=leave-history]', (el) => el.innerText)
    check('V7 新審核人核准；歷程有送出、改派（B → 管理員二）、核准', history.includes('改派') && history.includes('E2E 主管 B → E2E 管理員二') && history.includes('核准'))
    await assertNoUnhandled('V7', [X.tracker])
    await ctx2.close()
  }

  // ================= V8：版本衝突 =================
  {
    const page = B.page
    await page.bringToFront()
    const r = await apiCall(A.page, 'POST', '/api/leave/me', { requestId: crypto.randomUUID(), leaveType: 'PERSONAL', workDate: D2, startTime: '09:00', endTime: '10:00', reason: 'E2E 衝突' })
    await click(page, '重新整理')
    await waitRow(page, 'approvals', 'E2E 衝突', 'PENDING')
    await openDetail(page, 'approvals', r.body.request.id)
    await click(page, '核准')
    const w = await apiCall(A.page, 'POST', `/api/leave/me/${r.body.request.id}/withdraw`, { expectedVersion: 0, reason: null })
    check('V8 （準備）員工在主管確認前撤回', w.status === 200)
    await click(page, '確認核准')
    await page.waitForFunction(() => [...document.querySelectorAll('[data-testid=decision-confirm] button')].some((b) => b.textContent.trim() === '重新載入'))
    check('V8 狀態已變更 → 不假裝成功，顯示原因與「重新載入」', (await page.$eval('[data-testid=decision-confirm] [role=alert]', (el) => el.textContent)).length > 0)
    await click(page, '重新載入')
    await page.waitForFunction(() => document.querySelector('[data-testid=leave-summary]')?.dataset.status === 'WITHDRAWN')
    check('V8 重新載入後顯示已撤回', true)
    await click(page, '關閉')
  }

  // ================= V9：回應遺失 → 重送同一內容；重試被拒仍鎖定 =================
  {
    const page = A.page
    await page.bringToFront()
    await page.setRequestInterception(true)
    let mode = 'pass'
    const bodies = []
    const handler = async (r) => {
      if (r.isInterceptResolutionHandled()) return
      const url = new URL(r.url())
      if (r.method() === 'POST' && url.pathname === '/api/leave/me') {
        bodies.push(JSON.parse(r.postData() ?? '{}'))
        if (mode === 'lost') {
          mode = 'pass'
          const cookies = await page.cookies()
          const headers = { ...r.headers(), cookie: cookies.map((c) => `${c.name}=${c.value}`).join('; ') }
          delete headers.host
          delete headers['content-length']
          await fetch(r.url(), { method: 'POST', headers, body: r.postData() })
          return r.abort('failed')
        }
        if (mode === 'forbidden') {
          mode = 'pass'
          return r.respond({ status: 403, contentType: 'application/json', body: '{"code":"FORBIDDEN","message":"安全驗證失敗，請重新整理頁面後再試"}' })
        }
      }
      if (r.method() === 'GET' && url.pathname === '/api/leave/me' && mode === 'list-fail') {
        mode = 'pass'
        return r.respond({ status: 500, contentType: 'application/json', body: '{"code":"INTERNAL_ERROR","message":"系統發生錯誤，請稍後再試"}' })
      }
      void r.continue()
    }
    page.on('request', handler)
    mode = 'lost'
    await fillLeave(page, { date: D3, start: '09:00', end: '10:30', reason: 'E2E 網路不穩' })
    await click(page, '送出申請')
    await page.waitForSelector('[data-testid=leave-form][data-phase=unknown]')
    check('V9 回應遺失 → 結果不明、表單鎖定在原內容、不自動重送', (await leaveError(page)).includes('無法確認原提交是否已建立') && (await page.$eval('#lv-reason', (el) => el.matches(':disabled'))))
    await waitRow(page, 'my-leaves', 'E2E 網路不穩', 'PENDING')
    check('V9 重新查詢清單：伺服器其實已建立', true)
    mode = 'forbidden'
    await click(page, '重新送出原內容')
    await page.waitForFunction(() => document.querySelector('[data-testid=leave-error]')?.textContent.includes('原提交的結果仍未確認'))
    check('V9 重試被拒（403）→ 不清除快照，仍鎖定為原內容', (await page.$eval('[data-testid=leave-form]', (el) => el.dataset.unconfirmed)) === 'true' && (await page.$eval('#lv-reason', (el) => el.value)) === 'E2E 網路不穩')
    await click(page, '重新送出原內容')
    await waitText(page, '原提交已經建立')
    await waitRow(page, 'my-leaves', 'E2E 網路不穩', 'PENDING')
    const last3 = bodies.slice(-3)
    const sameId = last3.every((b) => b.requestId === last3[0].requestId)
    const rows = (await rowsOf(page, 'my-leaves')).filter((r) => r.reason === 'E2E 網路不穩')
    check('V9 重送沿用同一個 requestId 與內容 → 後端回「已建立」，清單只有一筆', sameId && rows.length === 1, JSON.stringify(last3.map((b) => b.requestId)))
    check('V9 確認後表單換新（新的 requestId 用於下一筆）', (await page.$eval('#lv-reason', (el) => el.value)) === '')

    // 載入失敗 → 錯誤＋重試
    mode = 'list-fail'
    await click(page, '重新整理')
    await waitText(page, '無法載入請假清單')
    await click(page, '重試')
    await page.waitForSelector('[data-testid=my-leaves-table]')
    check('V9 清單載入失敗 → 錯誤訊息與重試；重試後恢復', true)
    page.off('request', handler)
    await page.setRequestInterception(false)
    await settle(page)
    await assertNoUnhandled('V4～V9', [A.tracker, B.tracker])
  }

  // ================= V10：手機尺寸 =================
  {
    const ctx = await browser.createBrowserContext()
    const ui = await newPage(ctx, { viewport: MOBILE })
    await loginAs(ui.page, stack.users.a.username, stack.users.a.password, base)
    await ui.page.goto(`${base}/#/leave`, { waitUntil: 'networkidle0' })
    await ui.page.waitForSelector('[data-testid=my-leaves-table]')
    check('V10 手機：請假頁沒有橫向捲動', await noHorizontalOverflow(ui.page))
    const fits = await ui.page.$$eval('#lv-type, #lv-date, #lv-start, #lv-end, #lv-reason', (els) => els.every((el) => el.getBoundingClientRect().right <= window.innerWidth + 1))
    check('V10 手機：表單欄位完整顯示', fits)
    await ui.page.screenshot({ path: path.join(OUT, 'leave-01-mobile.png') })
    await ctx.close()

    const ctxM = await browser.createBrowserContext()
    const m = await newPage(ctxM, { viewport: MOBILE })
    await loginAs(m.page, stack.users.admin.username, stack.users.admin.password, base)
    await m.page.goto(`${base}/#/admin/leave`, { waitUntil: 'networkidle0' })
    await m.page.waitForSelector('[data-testid=admin-leaves-table]')
    check('V10 手機：請假管理頁沒有橫向捲動', await noHorizontalOverflow(m.page))
    const first = (await rowsOf(m.page, 'admin-leaves'))[0]
    await openDetail(m.page, 'admin-leaves', first.id)
    check('V10 手機：詳情視窗完整顯示', await m.page.$eval('.modal', (el) => el.getBoundingClientRect().width <= window.innerWidth && el.getBoundingClientRect().left >= 0))
    await m.page.screenshot({ path: path.join(OUT, 'leave-02-mobile-admin.png') })
    await ctxM.close()
  }

  await assertNoUnhandled('全程', [A.tracker, B.tracker, admin.tracker])
} catch (error) {
  check('腳本執行未中斷', false, String(error?.stack ?? error))
} finally {
  await browser?.close()
  stack?.stop()
}

finish()
