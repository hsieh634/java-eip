// T05 第 1 輪複審 R1～R3 的回歸測試，在【隔離堆疊】執行（測試資料庫＋本次專用帳號；不碰開發庫）。
// R1：成功回應無法使用（損壞 JSON、缺欄位、requestId 不符、本文讀取中斷）時，前端不能把它當成「後端沒有建立」。
// R2：相同條件再按「查詢」必須重新讀取（三種清單）；舊回應晚到不能覆蓋新結果。
// R3：原指定審核人失效時，設定畫面的下拉選項與受控值一致，且可以直接選「不指定」。
//
// 證據範圍：
// - 損壞 JSON／缺欄位／requestId 不符：瀏覽器真的送出 POST（由測試轉送到後端、後端真的建立），瀏覽器收到的是被替換的成功回應。
// - 本文讀取中斷：在頁面內把「這一次 fetch 回應的 json()」換成丟出 TypeError（受控替身）；請求本身是真的，後端真的建立。
// - 受控函式：從隔離堆疊的 Vite 直接載入前端模組，呼叫實際的驗證函式。
// 執行：cd frontend; npm run e2e:leave-review
import crypto from 'node:crypto'
import { createRunner, launchBrowser, loginAs } from './helpers.mjs'
import { startIsolatedStack } from './isolated-stack.mjs'
import { click, newPage, selectByText, setValue, taipeiDate, waitText } from './ui.mjs'

const { check, finish } = createRunner()
let stack
let browser
const DESKTOP = { width: 1280, height: 900 }
const settle = (page, ms = 600) => page.evaluate((t) => new Promise((r) => setTimeout(r, t)), ms)

async function assertNoUnhandled(name, trackers) {
  const rejections = (await Promise.all(trackers.map((t) => t.unhandledRejections()))).flat()
  const errors = trackers.flatMap((t) => t.pageErrors)
  check(`${name}：沒有未處理的 Promise 拒絕與 JS 例外`, rejections.length === 0 && errors.length === 0, [...rejections, ...errors].join(' | '))
}

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

/**
 * 請求控制：記錄 POST /api/leave/me 的本文與各清單 GET 次數；依 mode 決定這一次 POST 怎麼回：
 * pass（照常）、corrupt／missing／mismatch（先轉送到後端讓它真的建立，再回傳被替換的成功回應）、forbidden／unauthorized（不轉送）、hold（扣住）。
 * listMode：hold-list（扣住清單 GET）、fail-list（清單 GET 回 500 一次）。
 */
async function control(page) {
  const ctl = { mode: 'pass', listMode: 'pass', posts: [], gets: { me: 0, approvals: 0, admin: 0 }, heldPosts: [], heldLists: [] }
  await page.setRequestInterception(true)
  page.on('request', (r) => {
    handle(r).catch((e) => {
      // 測試工具本身的錯誤不能讓整個腳本崩潰（否則隔離堆疊不會被關閉）
      console.log(`  （請求控制發生錯誤：${e}）`)
      if (!r.isInterceptResolutionHandled()) void r.continue().catch(() => {})
    })
  })
  async function handle(r) {
    if (r.isInterceptResolutionHandled()) return
    const url = new URL(r.url())
    const m = r.method()
    if (m === 'GET' && url.pathname === '/api/leave/me') ctl.gets.me++
    if (m === 'GET' && url.pathname === '/api/leave/approvals') ctl.gets.approvals++
    if (m === 'GET' && url.pathname === '/api/admin/leave/requests') ctl.gets.admin++
    const isList = m === 'GET' && ['/api/leave/me', '/api/leave/approvals', '/api/admin/leave/requests'].includes(url.pathname)
    if (isList && ctl.listMode === 'hold-list') return void ctl.heldLists.push(r)
    if (isList && ctl.listMode === 'fail-list') {
      ctl.listMode = 'pass'
      return void r.respond({ status: 500, contentType: 'application/json', body: '{"code":"INTERNAL_ERROR","message":"系統發生錯誤，請稍後再試"}' })
    }
    if (m === 'POST' && url.pathname === '/api/leave/me') {
      ctl.posts.push(JSON.parse(r.postData() ?? '{}'))
      const mode = ctl.mode
      ctl.mode = 'pass'
      if (mode === 'hold') return void ctl.heldPosts.push(r)
      if (mode === 'forbidden') return void r.respond({ status: 403, contentType: 'application/json', body: '{"code":"CSRF_REJECTED","message":"安全驗證失敗，請重新整理頁面後再試"}' })
      if (mode === 'unauthorized') return void r.respond({ status: 401, contentType: 'application/json', body: '{"code":"UNAUTHENTICATED","message":"請先登入"}' })
      if (mode === 'corrupt' || mode === 'missing' || mode === 'mismatch') {
        const real = await forward(page, r)
        let body = '{}'
        if (mode === 'corrupt') body = real.text.slice(0, Math.floor(real.text.length / 2)) // 截斷的 JSON
        if (mode === 'mismatch') {
          const parsed = JSON.parse(real.text)
          if (parsed.request) parsed.request.requestId = crypto.randomUUID()
          body = JSON.stringify(parsed)
        }
        return void r.respond({ status: real.status, contentType: 'application/json', body })
      }
    }
    void r.continue()
  }
  return ctl
}

/** 把瀏覽器的請求原樣轉送到隔離後端（後端真的處理），回傳真實回應。 */
async function forward(page, r) {
  const cookies = await page.cookies()
  const headers = { ...r.headers(), cookie: cookies.map((c) => `${c.name}=${c.value}`).join('; ') }
  delete headers.host
  delete headers['content-length']
  const res = await fetch(r.url(), { method: r.method(), headers, body: r.postData() })
  return { status: res.status, text: await res.text() }
}

/** 在頁面內讓「下一次 POST /api/leave/me 的回應本文讀取」丟出 TypeError（請求本身照常送到後端）。 */
async function breakNextBodyRead(page) {
  await page.evaluate(() => {
    const original = globalThis.__origFetch ?? globalThis.fetch
    globalThis.__origFetch = original
    globalThis.fetch = async (input, init) => {
      const res = await original(input, init)
      if (String(input) === '/api/leave/me' && init?.method === 'POST') {
        globalThis.fetch = original
        await res.text() // 真的讀完（後端已提交），但交給程式的是一個讀取時會中斷的回應
        return { ok: res.ok, status: res.status, json: () => Promise.reject(new TypeError('network error while reading body')) }
      }
      return res
    }
  })
}

/** 每個情境從全新的表單（新的 requestId）開始，情境之間互不影響。 */
async function freshForm(page) {
  await page.reload({ waitUntil: 'networkidle0' })
  await page.waitForSelector('[data-testid=leave-form][data-phase=editing]')
}

async function fillLeave(page, { date, start, end, reason }) {
  await page.waitForSelector('[data-testid=leave-form][data-phase=editing]')
  await setValue(page, '#lv-date', date)
  await setValue(page, '#lv-start', start)
  await setValue(page, '#lv-end', end)
  await setValue(page, '#lv-reason', reason)
}

const formState = (page) =>
  page.$eval('[data-testid=leave-form]', (f) => ({ phase: f.dataset.phase, unconfirmed: f.dataset.unconfirmed === 'true', error: f.querySelector('[data-testid=leave-error]')?.textContent ?? '', reason: f.querySelector('#lv-reason').value }))

async function waitPhaseSettled(page) {
  await page.waitForFunction(() => document.querySelector('[data-testid=leave-form]')?.dataset.phase !== 'submitting')
  await settle(page, 300)
}

/** 後端的真實狀態：本人清單中這個原因的筆數，以及第一筆的送出歷程數。 */
async function backendCount(page, reason) {
  const list = (await apiCall(page, 'GET', '/api/leave/me?size=50')).body.items.filter((r) => r.reason === reason)
  if (list.length === 0) return { rows: 0, submitted: 0, status: null, id: null }
  const detail = (await apiCall(page, 'GET', `/api/leave/me/${list[0].id}`)).body
  return { rows: list.length, submitted: detail.history.filter((h) => h.action === 'SUBMITTED').length, status: list[0].status, id: list[0].id }
}

const sameSubmission = (bodies) => bodies.length > 0 && bodies.every((b) => JSON.stringify(b) === JSON.stringify(bodies[0]))
const noLeak = (text) => !/SyntaxError|TypeError|JSON|Unexpected|undefined|stack/i.test(text)

try {
  stack = await startIsolatedStack()
  console.log(`隔離堆疊已啟動：${stack.baseUrl}（測試資料庫；本次專用帳號 ${stack.users.a.username} 等）`)
  browser = await launchBrowser()
  const base = stack.baseUrl
  const runId = stack.runId
  const admin2 = { username: `e2e_admin2_${runId}`, password: `Init-${crypto.randomBytes(6).toString('hex')}` }

  // ================= 準備（只動本次帳號） =================
  const ctxAdmin = await browser.createBrowserContext()
  const admin = await newPage(ctxAdmin, { viewport: DESKTOP })
  const ids = {}
  {
    const page = admin.page
    await loginAs(page, stack.users.admin.username, stack.users.admin.password, base)
    for (const key of ['a', 'b', 'c', 'admin']) ids[key] = (await apiCall(page, 'GET', `/api/admin/employees?q=${stack.users[key].username}`)).body.items[0]
    const a = ids.a
    const s1 = await apiCall(page, 'PUT', `/api/admin/employees/${a.id}`, { displayName: a.displayName, role: a.role, enabled: true, departmentId: null, managerId: ids.b.id, expectedVersion: a.version, reason: '複審測試：A 的主管 B' })
    const s2 = await apiCall(page, 'POST', '/api/admin/employees', { employeeNo: `EY-${runId}`, username: admin2.username, displayName: 'E2E 管理員二', role: 'ADMIN', departmentId: null, managerId: null, initialPassword: admin2.password, reason: '複審測試' })
    const s3 = await apiCall(page, 'POST', '/api/admin/employees', { employeeNo: `EM-${runId}`, username: `e2e_m_${runId}`, displayName: 'E2E 臨時主管 M', role: 'MANAGER', departmentId: null, managerId: null, initialPassword: admin2.password, reason: '複審測試' })
    ids.admin2 = s2.body
    ids.m = s3.body
    check('準備：A 的主管設為 B；建立第二位管理員與臨時主管 M（本次專用）', s1.status === 200 && s2.status === 201 && s3.status === 201, `${s1.status}/${s2.status}/${s3.status}`)
  }

  const ctxA = await browser.createBrowserContext()
  const A = await newPage(ctxA, { viewport: DESKTOP })
  await loginAs(A.page, stack.users.a.username, stack.users.a.password, base)
  const ctlA = await control(A.page)
  await A.page.click('a[href="#/leave"]')
  await A.page.waitForSelector('[data-testid=leave-form]')

  // ================= R1-0：受控函式（實際的前端驗證函式；舊版本沒有這個模組，不算舊缺陷證據） =================
  {
    const result = await A.page.evaluate(async () => {
      try {
        const m = await import('/src/leave/submitOutcome.ts')
        const http = await import('/src/api/http.ts')
        const sent = { leaveType: 'SICK', workDate: '2026-10-07', startTime: '09:00', endTime: '10:00', reason: '看診' }
        const rid = '11111111-1111-4111-8111-111111111111'
        const request = { id: 7, requestId: rid, leaveType: 'SICK', workDate: '2026-10-07', startTime: '09:00', endTime: '10:00', minutes: 60, reason: '看診', status: 'PENDING', approver: { id: 2, employeeNo: 'X', displayName: 'B', valid: true }, version: 0 }
        const ok = (raw) => { try { m.checkSubmitResult(raw, rid, sent); return true } catch (e) { return e instanceof m.UnconfirmedResultError ? false : `other:${e}` } }
        return {
          loaded: true,
          validNew: ok({ replayed: false, request }),
          validReplayApproved: ok({ replayed: true, request: { ...request, status: 'APPROVED' } }),
          validReplayWithdrawn: ok({ replayed: true, request: { ...request, status: 'WITHDRAWN' } }),
          empty: ok({}),
          nullBody: ok(null),
          noReplayed: ok({ request }),
          badStatus: ok({ replayed: false, request: { ...request, status: 'DONE' } }),
          stringId: ok({ replayed: false, request: { ...request, id: '7' } }),
          otherRequestId: ok({ replayed: false, request: { ...request, requestId: '22222222-2222-4222-8222-222222222222' } }),
          otherContent: ok({ replayed: false, request: { ...request, endTime: '11:00' } }),
          noApproverName: ok({ replayed: false, request: { ...request, approver: {} } }),
          uncertain: [new SyntaxError('x'), new TypeError('x'), new m.UnconfirmedResultError(), new http.ApiError(0, 'NETWORK_ERROR', 'x'), new http.ApiError(502, 'X', 'x')].map((e) => m.isUnconfirmed(e)),
          definite: [new http.ApiError(400, 'INVALID_LEAVE', 'x'), new http.ApiError(403, 'FORBIDDEN', 'x'), new http.ApiError(409, 'LEAVE_OVERLAP', 'x'), new http.ApiError(401, 'UNAUTHENTICATED', 'x')].map((e) => m.isUnconfirmed(e)),
        }
      } catch (e) {
        return { loaded: false, error: String(e) }
      }
    })
    if (!result.loaded) {
      check('R1-0 受控函式模組可載入（舊版本沒有這個模組：此項只代表新函式不存在，不算舊缺陷證據）', false, result.error)
    } else {
      check('R1-0 合法的新建、replayed（已核准、已撤回）回應通過驗證', result.validNew === true && result.validReplayApproved === true && result.validReplayWithdrawn === true, JSON.stringify(result))
      check('R1-0 空物件、null、缺 replayed、不合法狀態、id 型別錯誤、requestId 或內容不符、缺審核人名稱 → 都判定「無法確認」',
        [result.empty, result.nullBody, result.noReplayed, result.badStatus, result.stringId, result.otherRequestId, result.otherContent, result.noApproverName].every((v) => v === false), JSON.stringify(result))
      check('R1-0 解析錯誤、讀取錯誤、驗證失敗、網路／5xx → 無法確認；400／403／409／401 → 明確回應', result.uncertain.every(Boolean) && result.definite.every((v) => v === false), JSON.stringify(result))
    }
  }

  // ================= R1-1：損壞 JSON（後端已建立）→ 403 重試 → 成功重試 =================
  {
    const page = A.page
    const reason = 'R1 損壞 JSON'
    const before = ctlA.posts.length
    ctlA.mode = 'corrupt'
    await fillLeave(page, { date: taipeiDate(1), start: '09:00', end: '10:00', reason })
    await click(page, '送出申請')
    await waitPhaseSettled(page)
    let s = await formState(page)
    const created = await backendCount(page, reason)
    check('R1-1 後端已建立（1 筆）', created.rows === 1)
    check('R1-1 HTTP 201 但 JSON 損壞 → 結果不明、表單鎖定、保存原快照（不是可編輯）', s.phase === 'unknown' && s.unconfirmed && s.reason === reason, JSON.stringify(s))
    check('R1-1 訊息不洩漏 SyntaxError／解析細節，也沒有提示成功', noLeak(s.error) && !(await page.$('[data-testid=leave-notice]')), s.error)
    ctlA.mode = 'forbidden'
    if (s.phase === 'unknown') {
      await click(page, '重新送出原內容')
      await waitPhaseSettled(page)
      s = await formState(page)
      check('R1-1 重試收到 403 → 仍鎖定、快照保留', s.phase === 'unknown' && s.unconfirmed && s.reason === reason, JSON.stringify(s))
      await click(page, '重新送出原內容')
      await waitText(page, '原提交已經建立')
    }
    ctlA.mode = 'pass'
    const bodies = ctlA.posts.slice(before)
    const after = await backendCount(page, reason)
    check('R1-1 三次送出的本文完全相同（同一個 requestId、同一份內容）', bodies.length === 3 && sameSubmission(bodies), JSON.stringify(bodies.map((b) => b.requestId)))
    check('R1-1 後端仍只有 1 筆申請、1 筆送出歷程', after.rows === 1 && after.submitted === 1, JSON.stringify(after))
  }

  // ================= R1-2：成功狀態但缺必要欄位（{}） =================
  {
    const page = A.page
    const reason = 'R1 缺欄位'
    await freshForm(page)
    const before = ctlA.posts.length
    await page.waitForSelector('[data-testid=leave-form][data-phase=editing]')
    ctlA.mode = 'missing'
    await fillLeave(page, { date: taipeiDate(1), start: '10:00', end: '11:00', reason })
    await click(page, '送出申請')
    await waitPhaseSettled(page)
    const s = await formState(page)
    check('R1-2 201 ＋ {} → 不提示成功、不清空、保持結果不明', s.phase === 'unknown' && s.unconfirmed && s.reason === reason && !(await page.$('[data-testid=leave-notice]')), JSON.stringify(s))
    check('R1-2 訊息不洩漏 TypeError', noLeak(s.error), s.error)
    // replayed 可能是非 PENDING：原申請先被撤回，再確認 → 仍確認原申請、不重建
    const c = await backendCount(page, reason)
    await apiCall(page, 'POST', `/api/leave/me/${c.id}/withdraw`, { expectedVersion: 0, reason: null })
    if (s.phase === 'unknown') {
      await click(page, '重新送出原內容')
      await waitText(page, '原提交已經建立')
    }
    const after = await backendCount(page, reason)
    check('R1-2 原申請已撤回後確認 → 顯示已建立（已撤回），不重建：仍 1 筆、1 筆送出歷程', after.rows === 1 && after.submitted === 1 && after.status === 'WITHDRAWN', JSON.stringify(after))
    check('R1-2 兩次送出本文相同', sameSubmission(ctlA.posts.slice(before)))
  }

  // ================= R1-3：requestId 不符的成功回應 =================
  {
    const page = A.page
    const reason = 'R1 requestId 不符'
    await freshForm(page)
    await page.waitForSelector('[data-testid=leave-form][data-phase=editing]')
    ctlA.mode = 'mismatch'
    await fillLeave(page, { date: taipeiDate(1), start: '11:00', end: '12:00', reason })
    await click(page, '送出申請')
    await waitPhaseSettled(page)
    const s = await formState(page)
    check('R1-3 成功回應的 requestId 與本次提交不符 → 不提示成功、保持結果不明', s.phase === 'unknown' && s.unconfirmed && !(await page.$('[data-testid=leave-notice]')), JSON.stringify(s))
    if (s.phase === 'unknown') {
      await click(page, '重新送出原內容')
      await waitText(page, '原提交已經建立')
    }
    check('R1-3 確認後仍 1 筆', (await backendCount(page, reason)).rows === 1)
  }

  // ================= R1-4：成功狀態，但讀取回應本文時中斷（受控替身） =================
  {
    const page = A.page
    const reason = 'R1 本文讀取中斷'
    await freshForm(page)
    const before = ctlA.posts.length
    await page.waitForSelector('[data-testid=leave-form][data-phase=editing]')
    await breakNextBodyRead(page)
    await fillLeave(page, { date: taipeiDate(1), start: '13:00', end: '14:00', reason })
    await click(page, '送出申請')
    await waitPhaseSettled(page)
    const s = await formState(page)
    check('R1-4 後端已建立後本文讀取中斷 → 保持結果不明、原快照', s.phase === 'unknown' && s.unconfirmed && s.reason === reason && noLeak(s.error), JSON.stringify(s))
    if (s.phase === 'unknown') {
      await click(page, '重新送出原內容')
      await waitText(page, '原提交已經建立')
    }
    const after = await backendCount(page, reason)
    check('R1-4 兩次送出本文相同；後端 1 筆、1 筆送出歷程', sameSubmission(ctlA.posts.slice(before)) && after.rows === 1 && after.submitted === 1, JSON.stringify(after))
  }

  // ================= R1-5：首次明確拒絕仍可編輯；合法新建正常完成 =================
  {
    const page = A.page
    await freshForm(page)
    await fillLeave(page, { date: taipeiDate(1), start: '09:30', end: '10:30', reason: 'R1 重疊' })
    await click(page, '送出申請')
    await page.waitForFunction(() => document.querySelector('[data-testid=leave-error]')?.textContent.includes('重疊'))
    const s = await formState(page)
    check('R1-5 第一次送出被明確拒絕（409 重疊）→ 可以編輯、沒有鎖定', s.phase === 'editing' && !s.unconfirmed, JSON.stringify(s))
    await setValue(page, '#lv-start', '15:00')
    await setValue(page, '#lv-end', '16:00')
    await setValue(page, '#lv-reason', 'R1 正常新建')
    await click(page, '送出申請')
    await waitText(page, '已送出請假申請')
    check('R1-5 合法的新建回應正常完成、表單換新', (await page.$eval('#lv-reason', (el) => el.value)) === '')
  }

  // ================= R1-6：卸載後的回應不更新畫面；401 走登入失效流程 =================
  {
    const page = A.page
    await freshForm(page)
    ctlA.mode = 'hold'
    await fillLeave(page, { date: taipeiDate(2), start: '09:00', end: '10:00', reason: 'R1 送出後切頁' })
    await click(page, '送出申請')
    await page.waitForFunction(() => document.querySelector('[data-testid=leave-form]')?.dataset.phase === 'submitting')
    await page.click('a[href="#/attendance"]')
    await page.waitForSelector('[data-testid=today-status]')
    const held = ctlA.heldPosts.shift()
    const real = await forward(page, held)
    await held.respond({ status: real.status, contentType: 'application/json', body: real.text.slice(0, 10) })
    await settle(page)
    check('R1-6 送出後切頁，損壞回應晚到 → 新頁面沒有任何提示或錯誤', !(await page.$('[data-testid=leave-notice]')) && !(await page.$('[data-testid=leave-error]')))
    await page.click('a[href="#/leave"]')
    await page.waitForSelector('[data-testid=leave-form][data-phase=editing]')
    await assertNoUnhandled('R1', [A.tracker])

    // 真正的 401：本次專用的員工 D 在結果不明時被管理員停用，重試時由 ActiveAccountFilter 回 401（不是偽造的回應）
    const dPassword = `Init-${crypto.randomBytes(6).toString('hex')}`
    const dUser = `e2e_d_${runId}`
    const dCreated = await apiCall(admin.page, 'POST', '/api/admin/employees', { employeeNo: `ED-${runId}`, username: dUser, displayName: 'E2E 員工 D', role: 'EMPLOYEE', departmentId: null, managerId: ids.b.id, initialPassword: dPassword, reason: '複審測試：401 流程' })
    const ctx2 = await browser.createBrowserContext()
    const A2 = await newPage(ctx2, { viewport: DESKTOP })
    await loginAs(A2.page, dUser, dPassword, base)
    const ctl2 = await control(A2.page)
    await A2.page.click('a[href="#/leave"]')
    ctl2.mode = 'missing'
    await fillLeave(A2.page, { date: taipeiDate(2), start: '11:00', end: '12:00', reason: 'R1 401 流程' })
    await click(A2.page, '送出申請')
    await waitPhaseSettled(A2.page)
    const unknownBefore = (await formState(A2.page)).phase === 'unknown'
    const d = (await apiCall(admin.page, 'GET', `/api/admin/employees/${dCreated.body.id}`)).body
    await apiCall(admin.page, 'PUT', `/api/admin/employees/${d.id}`, { displayName: d.displayName, role: d.role, enabled: false, departmentId: null, managerId: ids.b.id, expectedVersion: d.version, reason: '複審測試：停用 D' })
    if (unknownBefore) await click(A2.page, '重新送出原內容')
    const toLogin = await A2.page.waitForSelector('#username', { timeout: 10_000 }).then(() => true, () => false)
    check('R1-6 結果不明時帳號被停用 → 重試得到 401 → 走既有登入失效流程（回到登入頁）', unknownBefore && toLogin)
    check('R1-6 401 之後沒有再送出（不會以其他身分重送）', ctl2.posts.length === 2, `${ctl2.posts.length} 次 POST`)
    await assertNoUnhandled('R1-6 401', [A2.tracker])
    await ctx2.close()
  }

  // ================= R2：相同條件再查詢 =================
  const ctxB = await browser.createBrowserContext()
  const B = await newPage(ctxB, { viewport: DESKTOP })
  await loginAs(B.page, stack.users.b.username, stack.users.b.password, base)
  const ctlB = await control(B.page)
  {
    // 我的請假：相同條件按兩次 → 兩次讀取；別人核准後再按 → 看到最新
    const page = A.page
    await page.waitForSelector('[data-testid=my-leaves-table]')
    const n0 = ctlA.gets.me
    await click(page, '查詢')
    await settle(page)
    await click(page, '查詢')
    await settle(page)
    check('R2-1 我的請假：相同條件連按兩次「查詢」→ 兩次讀取', ctlA.gets.me - n0 === 2, `${ctlA.gets.me - n0} 次`)
    await page.waitForSelector('[data-testid=my-leaves-table]')
    const target = await backendCount(page, 'R1 正常新建')
    const ap = await apiCall(B.page, 'POST', `/api/leave/approvals/${target.id}/approve`, { expectedVersion: 0 })
    await click(page, '查詢')
    const seen = await page.waitForFunction((id) => document.querySelector(`[data-testid=my-leaves-table] tr[data-leave-id="${id}"]`)?.dataset.status === 'APPROVED', { timeout: 8000 }, target.id).then(() => true, () => false)
    check('R2-1 主管核准後，員工按相同條件「查詢」看到已核准', ap.status === 200 && seen)

    // 舊回應晚到不覆蓋新結果
    ctlA.listMode = 'hold-list'
    await click(page, '查詢')
    await click(page, '查詢')
    const deadline = Date.now() + 5000
    while (ctlA.heldLists.length < 2 && Date.now() < deadline) await new Promise((r) => setTimeout(r, 50))
    const gotTwo = ctlA.heldLists.length >= 2
    ctlA.listMode = 'pass'
    if (gotTwo) {
      const [older, newer] = ctlA.heldLists.splice(0, 2)
      await newer.continue()
      await page.waitForSelector('[data-testid=my-leaves-table]')
      await older.respond({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 }) })
      await settle(page)
    } else {
      for (const r of ctlA.heldLists.splice(0)) await r.continue()
    }
    const rows = await page.$$eval('[data-testid=my-leaves-table] tbody tr', (trs) => trs.length).catch(() => 0)
    check('R2-2 連按兩次查詢：新回應先到、舊回應（空清單）後到 → 畫面維持新結果', gotTwo && rows > 0, `held=${gotTwo} rows=${rows}`)

    // 失敗後再次查詢可以恢復；重試也可以
    ctlA.listMode = 'fail-list'
    await click(page, '查詢')
    const failedShown = await page.waitForFunction(() => document.body.innerText.includes('無法載入請假清單'), { timeout: 5000 }).then(() => true, () => false)
    check('R2-3 （準備）相同條件再查詢時讀取失敗 → 顯示錯誤', failedShown)
    ctlA.listMode = 'pass'
    await click(page, '查詢')
    const recovered = await page.waitForSelector('[data-testid=my-leaves-table]', { timeout: 8000 }).then(() => true, () => false)
    check('R2-3 讀取失敗後，再按相同條件「查詢」→ 重新讀取並恢復', recovered)
    ctlA.listMode = 'fail-list'
    await setValue(page, '#my-leaves-status', 'PENDING') // 換條件以確保送出新請求（這一段只驗證「重試」）
    await click(page, '查詢')
    await waitText(page, '無法載入請假清單')
    ctlA.listMode = 'pass'
    await click(page, '重試')
    check('R2-3 失敗後按「重試」也能恢復', await page.waitForSelector('[data-testid=my-leaves-table]', { timeout: 8000 }).then(() => true, () => false))
  }
  {
    // 請假審核：員工撤回後，主管按相同條件「查詢」→ 消失
    const page = B.page
    const r = await apiCall(A.page, 'POST', '/api/leave/me', { requestId: crypto.randomUUID(), leaveType: 'PERSONAL', workDate: taipeiDate(3), startTime: '09:00', endTime: '10:00', reason: 'R2 待撤回' })
    await page.click('a[href="#/leave/approvals"]')
    await page.waitForFunction((id) => document.querySelector(`[data-testid=approvals-table] tr[data-leave-id="${id}"]`) !== null, {}, r.body.request.id)
    const n0 = ctlB.gets.approvals
    await click(page, '查詢')
    await settle(page)
    await click(page, '查詢')
    await settle(page)
    check('R2-4 請假審核：相同條件連按兩次「查詢」→ 兩次讀取', ctlB.gets.approvals - n0 === 2, `${ctlB.gets.approvals - n0} 次`)
    await apiCall(A.page, 'POST', `/api/leave/me/${r.body.request.id}/withdraw`, { expectedVersion: 0, reason: null })
    await click(page, '查詢')
    const gone = await page.waitForFunction((id) => document.querySelector('[data-testid=approvals-table], [data-testid=approvals-empty]') && !document.querySelector(`[data-testid=approvals-table] tr[data-leave-id="${id}"]`), { timeout: 8000 }, r.body.request.id).then(() => true, () => false)
    check('R2-4 員工撤回後，主管按相同條件「查詢」→ 待審清單不再列出', gone)
    ids.r2pending = (await apiCall(A.page, 'POST', '/api/leave/me', { requestId: crypto.randomUUID(), leaveType: 'PERSONAL', workDate: taipeiDate(3), startTime: '11:00', endTime: '12:00', reason: 'R2 待改派' })).body.request.id
  }
  {
    // 請假管理：另一位管理員改派後，按相同條件「查詢」→ 看到新審核人
    const ctlAdmin = await control(admin.page)
    const page = admin.page
    await page.click('a[href="#/admin/leave"]')
    await page.waitForFunction((id) => document.querySelector(`[data-testid=admin-leaves-table] tr[data-leave-id="${id}"]`) !== null, {}, ids.r2pending)
    const n0 = ctlAdmin.gets.admin
    await click(page, '查詢')
    await settle(page)
    await click(page, '查詢')
    await settle(page)
    check('R2-5 請假管理：相同條件連按兩次「查詢」→ 兩次讀取', ctlAdmin.gets.admin - n0 === 2, `${ctlAdmin.gets.admin - n0} 次`)
    const ctx2 = await browser.createBrowserContext()
    const X = await newPage(ctx2, { viewport: DESKTOP })
    await loginAs(X.page, admin2.username, admin2.password, base)
    const re = await apiCall(X.page, 'POST', `/api/admin/leave/requests/${ids.r2pending}/reassign`, { approverId: ids.admin2.id, expectedVersion: 0, reason: 'R2 改派' })
    await ctx2.close()
    await click(page, '查詢')
    const updated = await page.waitForFunction((id) => document.querySelector(`[data-testid=admin-leaves-table] tr[data-leave-id="${id}"]`)?.innerText.includes('E2E 管理員二'), { timeout: 8000 }, ids.r2pending).then(() => true, () => false)
    check('R2-5 另一位管理員改派後，按相同條件「查詢」→ 看到新審核人', re.status === 200 && updated)
    page.removeAllListeners('request')
    await page.setRequestInterception(false)
    await assertNoUnhandled('R2', [A.tracker, B.tracker])
  }

  // ================= R3：原指定審核人失效 =================
  {
    const page = admin.page
    const c = ids.c
    const m = ids.m
    const set = await apiCall(page, 'PUT', `/api/admin/leave/approver-settings/${c.id}`, { approverId: m.id, expectedVersion: 0, reason: 'R3 準備：指定 M' })
    const mNow = (await apiCall(page, 'GET', `/api/admin/employees/${m.id}`)).body
    const dis = await apiCall(page, 'PUT', `/api/admin/employees/${m.id}`, { displayName: mNow.displayName, role: mNow.role, enabled: false, departmentId: null, managerId: null, expectedVersion: mNow.version, reason: 'R3 準備：停用 M' })
    check('R3 準備：C 的指定審核人設為 M，之後停用 M', set.status === 200 && dis.status === 200, `${set.status}/${dis.status}`)

    await page.goto(`${base}/#/admin/leave`, { waitUntil: 'networkidle0' })
    await click(page, '指定請假審核人')
    await setValue(page, '#setting-q', stack.users.c.username)
    await click(page, '搜尋')
    await page.waitForSelector(`[data-testid=setting-search-results] button[data-username="${stack.users.c.username}"]`)
    await page.click(`[data-testid=setting-search-results] button[data-username="${stack.users.c.username}"]`)
    await page.waitForSelector('[data-testid=setting-form]')
    const sel = await page.$eval('#setting-approver', (el) => ({ value: el.value, text: el.options[el.selectedIndex]?.textContent ?? '', disabled: el.options[el.selectedIndex]?.disabled ?? false }))
    check('R3 下拉選單的值就是目前指定的 M（不是看似「不指定」）', sel.value === String(m.id), JSON.stringify(sel))
    check('R3 選項明示「目前指定：M（員工編號）（已無效）」', sel.text.includes('E2E 臨時主管 M') && sel.text.includes(`EM-${runId}`.toUpperCase()) && sel.text.includes('已無效'), sel.text)
    const summary = await page.$eval('[data-testid=setting-change]', (el) => el.textContent)
    check('R3 摘要不出現孤立的 #ID', !/#\d+/.test(summary), summary)
    await setValue(page, '#setting-approver', '')
    await setValue(page, '#setting-reason', 'R3 M 已停用，改回採直屬主管')
    await click(page, '確認儲存')
    await page.waitForSelector('[data-testid=setting-notice]')
    let st = (await apiCall(page, 'GET', `/api/admin/leave/approver-settings/${c.id}`)).body
    check('R3 直接選「不指定」並儲存成功：指定為空、版本 2', st.designatedApprover === null && st.version === 2, JSON.stringify({ d: st.designatedApprover, v: st.version }))
    await page.waitForSelector('[data-testid=setting-form]')
    await selectByText(page, '#setting-approver', `EB-${runId}`)
    await setValue(page, '#setting-reason', 'R3 改由 B 審核')
    await click(page, '確認儲存')
    await page.waitForFunction(() => document.querySelector('[data-testid=setting-current]')?.textContent.includes('E2E 主管 B'))
    st = (await apiCall(page, 'GET', `/api/admin/leave/approver-settings/${c.id}`)).body
    const hist = (await apiCall(page, 'GET', `/api/admin/leave/approver-settings/${c.id}/history`)).body.history
    const cNow = (await apiCall(page, 'GET', `/api/admin/employees/${c.id}`)).body
    check('R3 換成有效審核人 B 成功：版本 3、歷程三筆（M → 無 → B）且原因正確', st.designatedApprover?.employeeNo === `EB-${runId}` && st.version === 3 && hist.length === 3
      && hist[0].reason === 'R3 改由 B 審核' && hist[1].reason === 'R3 M 已停用，改回採直屬主管' && hist[1].beforeApprover?.id === m.id && hist[1].afterApprover === null, JSON.stringify(hist.map((h) => h.reason)))
    check('R3 C 的直屬主管不受影響（仍為未指派）', cNow.manager === null)
    const bad = await apiCall(page, 'PUT', `/api/admin/leave/approver-settings/${c.id}`, { approverId: m.id, expectedVersion: 3, reason: '新指派無效者' })
    check('R3 後端仍拒絕新指派無效審核人（409 APPROVER_INVALID）', bad.status === 409 && bad.body.code === 'APPROVER_INVALID')
    // 其他員工的選項不出現無效的 M
    await setValue(page, '#setting-q', stack.users.a.username)
    await click(page, '搜尋')
    await page.waitForSelector(`[data-testid=setting-search-results] button[data-username="${stack.users.a.username}"]`)
    await page.click(`[data-testid=setting-search-results] button[data-username="${stack.users.a.username}"]`)
    await page.waitForFunction((u) => document.querySelector('[data-testid=setting-editor]')?.dataset.employeeNo?.toLowerCase() === `ea-${u}`, {}, runId)
    const options = await page.$$eval('#setting-approver option', (os) => os.map((o) => o.textContent))
    const mNo = `EM-${runId}`.toUpperCase() // 測試庫累積了先前執行的同名帳號，用本次的員工編號判斷
    check('R3 其他員工的選項不包含本次無效的 M（保留選項只用來表示原值）', !options.some((o) => o.includes(mNo)), `${options.length} 個選項`)
    await assertNoUnhandled('R3', [admin.tracker])
  }
} catch (error) {
  check('腳本執行未中斷', false, String(error?.stack ?? error))
} finally {
  await browser?.close()
  stack?.stop()
}

finish()
