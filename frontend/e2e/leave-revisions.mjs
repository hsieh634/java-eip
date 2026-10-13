// 真隔離後端／瀏覽器；成功本文故障及目標 GET 損毀是受控替身。
import path from 'node:path'
import crypto from 'node:crypto'
import { createRunner, launchBrowser, loginAs, OUT } from './helpers.mjs'
import { startIsolatedStack } from './isolated-stack.mjs'
import { newPage, click, setValue, taipeiDate, noHorizontalOverflow } from './ui.mjs'
const { check, finish } = createRunner()
let stack, browser, activePage
let step = '初始化'
const recentHttp = []
const api = (page, method, url, body) => page.evaluate(async (method, url, body) => {
  const token = decodeURIComponent(document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='))?.slice(11) ?? '')
  const response = await fetch(url, { method, headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: body ? JSON.stringify(body) : undefined })
  return { status: response.status, body: await response.json() }
}, method, url, body)
const detail = (page, id) => api(page, 'GET', '/api/leave/me/' + id)
const clockTime = iso => new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Taipei', hourCycle: 'h23', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(iso)) +
  (/T\d{2}:\d{2}:\d{2}(\.\d+)/.exec(iso)?.[1] ?? '')
try {
  stack = await startIsolatedStack(); browser = await launchBrowser()
  const a = await newPage(await browser.createBrowserContext(), { viewport: { width: 1200, height: 900 } })
  const b = await newPage(await browser.createBrowserContext(), { viewport: { width: 1200, height: 900 } })
  const admin = await newPage(await browser.createBrowserContext())
  const c = await newPage(await browser.createBrowserContext())
  for (const item of [a, b, c, admin]) item.page.on('response', response => {
    const p = new URL(response.url()).pathname
    if (p.startsWith('/api/leave/') && response.request().method() === 'POST') recentHttp.push(response.request().method() + ' ' + p + ' status=' + response.status())
  })
  await loginAs(admin.page, stack.users.admin.username, stack.users.admin.password, stack.baseUrl)
  const employees = (await api(admin.page, 'GET', '/api/admin/attendance/employees')).body
  const employee = employees.find(e => e.employeeNo === 'EA-' + stack.runId)
  const manager = employees.find(e => e.employeeNo === 'EB-' + stack.runId)
  const setting = await api(admin.page, 'PUT', '/api/admin/leave/approver-settings/' + employee.id, { approverId: manager.id, expectedVersion: 0, reason: '本批專用審核人' })
  if (setting.status !== 200) throw new Error('專用審核人前置失敗')
  await loginAs(a.page, stack.users.a.username, stack.users.a.password, stack.baseUrl)
  await loginAs(b.page, stack.users.b.username, stack.users.b.password, stack.baseUrl)
  await loginAs(c.page, stack.users.c.username, stack.users.c.password, stack.baseUrl)
  const day = taipeiDate(), bodies = []
  a.page.on('request', r => {
    if (r.method() === 'POST' && new URL(r.url()).pathname.endsWith('/revisions')) bodies.push({ path: new URL(r.url()).pathname, body: r.postData() })
  })
  async function go(page, route) { activePage = page; step = '導覽 ' + route; const url = stack.baseUrl + '/#/' + route; if (page.url() === url) await page.reload({ waitUntil: 'networkidle0' }); else await page.goto(url, { waitUntil: 'networkidle0' }) }
  async function open(page, id) {
    activePage = page; step = '開詳情 #' + id
    await page.waitForSelector('[data-leave-id="' + id + '"] button')
    await page.$eval('[data-leave-id="' + id + '"] button', el => el.click())
    await page.waitForSelector('[data-testid=leave-document]')
  }
  async function close(page) { await click(page, '關閉'); await page.waitForFunction(() => !document.querySelector('[data-testid=leave-detail]')) }
  async function submitted(page) { await page.waitForFunction(() => !document.querySelector('[data-testid=leave-revision-dialog]')) }
  async function byReason(reason) { return (await api(a.page, 'GET', '/api/leave/me')).body.items.find(r => r.reason === reason) }
  async function revise(source, label, reason, start, end) {
    await go(a.page, 'leave'); await open(a.page, source); step = '來源 #' + source + ' ' + label; await click(a.page, label)
    await a.page.waitForSelector('[data-testid=leave-revision-form]')
    if (start) await a.page.select('#rev-start', start)
    if (end) await a.page.select('#rev-end', end)
    await setValue(a.page, '#rev-reason', reason); await click(a.page, '送出新版本申請'); await submitted(a.page)
    return byReason(reason)
  }
  async function review(id, approve, reason) {
    await go(b.page, 'leave/approvals'); await open(b.page, id)
    step = '開始審核 #' + id; await click(b.page, approve ? '核准' : '退回')
    if (!approve) await setValue(b.page, '#reject-reason', reason)
    step = '確認審核 #' + id; await click(b.page, approve ? '確認核准' : '確認退回')
    await b.page.waitForFunction(status => document.querySelector('[data-testid=leave-summary]')?.dataset.status === status, {}, approve ? 'APPROVED' : 'REJECTED')
    await close(b.page)
  }
  // 一般新增→待審修改→退回重送→核准，原內容與退回原因保留。
  await go(a.page, 'leave')
  await setValue(a.page, '#lv-date', day); await a.page.select('#lv-end', '17:00'); await setValue(a.page, '#lv-reason', 'L51 原提交')
  await click(a.page, '送出申請'); await a.page.waitForFunction(() => document.querySelector('[data-testid=leave-notice]')?.textContent.includes('已送出'))
  const root = await byReason('L51 原提交')
  const modified = await revise(root.id, '修改並重新送審', 'L51 待審修改', '09:30', '16:30')
  const md = (await detail(a.page, modified.id)).body
  check('L51-01 新版本與原內容、單一待審', modified.id !== root.id && md.document.origin.startTime === '09:00' && md.document.origin.status === 'WITHDRAWN' &&
    md.document.versions.filter(v => v.status === 'PENDING').length === 1 && md.document.effective === null)
  const oldApproval = await api(b.page, 'POST', '/api/leave/approvals/' + root.id + '/approve', { expectedVersion: 0 })
  check('L51-02 舊待審版本不能核准', oldApproval.status === 409)
  await review(modified.id, false, 'L51 請補充說明')
  const resubmitted = await revise(modified.id, '修改後重新送審', 'L51 退回重送', null, null)
  const rd = (await detail(a.page, resubmitted.id)).body
  check('L51-03 保留退回與原因、明確來源', rd.document.versions.find(v => v.id === modified.id).status === 'REJECTED' &&
    rd.document.versions.find(v => v.id === modified.id).decisionReason === 'L51 請補充說明' && resubmitted.revision.sourceRequestId === modified.id)
  await review(resubmitted.id, true)
  check('L51-05 首次核准有效版本1', (await detail(a.page, resubmitted.id)).body.document.effectiveVersion === 1)
  // 指定目標 GET 本文混入其他ID，不得改另一單或默默新增。
  await go(a.page, 'leave'); await open(a.page, resubmitted.id)
  await a.page.evaluate(id => {
    const original = window.fetch
    window.fetch = async (...args) => {
      const response = await original(...args)
      if (args[0] === '/api/leave/me/' + id && response.ok) {
        window.fetch = original; const read = response.json.bind(response)
        response.json = async () => { const d = await read(); return { ...d, request: { ...d.request, id: id + 999 } } }
      }
      return response
    }
  }, resubmitted.id)
  const noPost = bodies.length
  await click(a.page, '申請變更'); await a.page.waitForFunction(() => document.querySelector('[data-testid=leave-revision-dialog]')?.textContent.includes('無法確認原選擇'))
  check('L51-24 明確ID的錯誤目標不顯示可提交表單、零POST', !(await a.page.$('[data-testid=leave-revision-form]')) && bodies.length === noPost)
  await click(a.page, '取消修改')
  const changed = await revise(resubmitted.id, '申請變更', 'L51 核准後變更', null, '14:00')
  const cd = (await detail(a.page, changed.id)).body
  check('L51-05 變更待審仍保留原有效內容', cd.document.effective.id === resubmitted.id && cd.document.effective.endTime === '16:30' && cd.document.effectiveVersion === 1)
  // 真實打卡兩組，原事件4筆；再更正／作廢／補登，證據須分開呈現。
  const sessions = []
  for (let i = 0; i < 2; i++) {
    const start = await api(a.page, 'POST', '/api/attendance/clock-in', { requestId: crypto.randomUUID() })
    const end = await api(a.page, 'POST', '/api/attendance/clock-out', { requestId: crypto.randomUUID(), expectedAttendanceId: start.body.id, expectedVersion: start.body.version })
    if (end.status !== 200) throw new Error('專用打卡前置失敗')
    sessions.push(end.body)
  }
  const corrected = await api(admin.page, 'POST', '/api/admin/attendance/records/correct', { employeeId: employee.id, attendanceId: sessions[0].id, expectedVersion: sessions[0].version,
    workDate: day, clockInTime: clockTime(new Date(Date.parse(sessions[0].clockInAt) - 60000).toISOString()), clockOutTime: clockTime(sessions[0].clockOutAt), reason: 'L51 更正證據' })
  if (corrected.status !== 200) throw new Error('更正證據前置失敗')
  await api(admin.page, 'POST', '/api/admin/attendance/records/void', { employeeId: employee.id, attendanceId: sessions[1].id, expectedVersion: sessions[1].version, reason: 'L51 作廢證據' })
  await api(admin.page, 'POST', '/api/admin/attendance/records/correct', { employeeId: employee.id, workDate: day, clockInTime: '00:10:00', clockOutTime: '00:20:00', reason: 'L51 補登證據' })
  await api(admin.page, 'POST', '/api/admin/attendance/records/correct', { employeeId: employee.id, workDate: taipeiDate(-1), clockInTime: '09:00:00', clockOutTime: '10:00:00', reason: 'L51 無關日期' })
  await go(b.page, 'leave/approvals'); await open(b.page, changed.id)
  const evidence = (await api(b.page, 'GET', '/api/leave/approvals/' + changed.id)).body.evidence
  check('L51-18 多組、更正、作廢、補登與原事件，限定日期且無GPS', evidence.records.length === 3 && evidence.events.length === 4 &&
    evidence.records.some(r => r.corrected) && evidence.records.some(r => r.status === 'VOIDED') && evidence.records.some(r => r.source === 'MANUAL') &&
    evidence.dates.length === 1 && evidence.dates[0] === day && !JSON.stringify(evidence).includes('latitude') && !JSON.stringify(evidence).includes('longitude'))
  check('L51-18 審核畫面分開有效內容、範圍與出勤', (await b.page.$eval('[data-testid=leave-removed]', e => e.textContent)).includes('14:00～16:30') &&
    (await b.page.$eval('[data-testid=leave-attendance-evidence]', e => e.textContent)).includes('原始打卡事件'))
  const moved = await api(admin.page, 'POST', '/api/admin/attendance/records/correct', { employeeId: employee.id, attendanceId: corrected.body.id, expectedVersion: corrected.body.version,
    workDate: day, clockInTime: clockTime(new Date(Date.parse(sessions[0].clockInAt) - 120000).toISOString()), clockOutTime: clockTime(sessions[0].clockOutAt), reason: 'L51 檢視後變動' })
  if (moved.status !== 200) throw new Error('證據版本變動前置失敗')
  await click(b.page, '核准'); await click(b.page, '確認核准')
  await b.page.waitForFunction(() => document.querySelector('[data-testid=decision-confirm]')?.textContent.includes('出勤依據'))
  check('L51-19 證據變更阻止舊確認，仍待審', (await detail(a.page, changed.id)).body.request.status === 'PENDING')
  await click(b.page, '重新載入'); await b.page.waitForSelector('[data-testid=leave-attendance-evidence]')
  await click(b.page, '核准'); await click(b.page, '確認核准')
  await b.page.waitForFunction(() => document.querySelector('[data-testid=leave-summary]')?.dataset.status === 'APPROVED')
  const frozen = (await detail(a.page, changed.id)).body.history.find(h => h.decision?.afterEffectiveRequestId === changed.id && h.action === 'APPROVED').decision
  check('L51-19 刷新後核准切換有效內容與保存證據', (await detail(a.page, changed.id)).body.document.effective.id === changed.id &&
    frozen.attendance.events.length === 4 && frozen.beforeEffectiveVersion === 1 && frozen.afterEffectiveVersion === 2)
  await close(b.page)
  // 部分銷假201後json損壞→原快照鎖定；403重試也不能放棄快照。
  await go(a.page, 'leave'); await open(a.page, changed.id); await click(a.page, '申請部分銷假')
  await a.page.waitForSelector('[data-testid=leave-revision-form]'); await a.page.select('#rev-start', '10:00'); await setValue(a.page, '#rev-reason', 'L51 部分銷假')
  await a.page.evaluate(() => {
    const original = window.fetch
    window.fetch = async (...args) => {
      const response = await original(...args)
      if (String(args[0]).endsWith('/revisions') && response.ok) {
        window.fetch = original; response.json = async () => JSON.parse('{broken')
      }
      return response
    }
  })
  const offset = bodies.length
  await click(a.page, '送出新版本申請'); await a.page.waitForFunction(() => document.querySelector('[data-testid=leave-revision-form]')?.dataset.phase === 'unknown')
  check('L51-22 成功本文損壞鎖原表單與來源，不能切其他表單', await a.page.$eval('[data-testid=leave-revision-form] fieldset', e => e.disabled) &&
    await a.page.$$eval('[data-testid=my-leaves-table] button', buttons => buttons.every(b => b.disabled)) &&
    await a.page.$$eval('[data-testid=leave-revision-dialog] button', buttons => buttons.find(b => b.textContent === '取消修改').disabled))
  const partial = await byReason('L51 部分銷假')
  check('L51-22 真後端僅一個新流程、原有效未釋放', partial.status === 'PENDING' && (await detail(a.page, partial.id)).body.document.effective.id === changed.id)
  await a.page.setRequestInterception(true)
  let deny = true
  const intercept = async request => {
    if (deny && request.method() === 'POST' && new URL(request.url()).pathname.endsWith('/revisions')) {
      deny = false; await request.respond({ status: 403, contentType: 'application/json', body: JSON.stringify({ code: 'FORBIDDEN', message: '受控拒絕' }) })
    } else await request.continue()
  }
  a.page.on('request', intercept)
  await click(a.page, '重新送出原內容'); await a.page.waitForFunction(() => document.querySelector('[data-testid=revision-error]')?.textContent.includes('受控拒絕'))
  check('L51-23 重試403仍保持未確認快照', await a.page.$eval('[data-testid=leave-revision-form]', e => e.dataset.phase === 'unknown' && e.dataset.unconfirmed === 'true'))
  a.page.off('request', intercept); await a.page.setRequestInterception(false)
  await click(a.page, '重新送出原內容'); await submitted(a.page)
  check('L51-23 三次重送相同操作、UUID、來源與完整本文', bodies.length === offset + 3 && bodies.slice(offset).every(x => x.path === bodies[offset].path && x.body === bodies[offset].body))
  check('L51-23 確認沒有新增第二筆', (await api(a.page, 'GET', '/api/leave/me')).body.items.filter(r => r.reason === 'L51 部分銷假').length === 1)
  await review(partial.id, true)
  check('L51-08 部分銷假核准才切換，保留原核准與歷程', (await detail(a.page, partial.id)).body.document.effective.minutes === 240 &&
    (await detail(a.page, partial.id)).body.document.originalApproved.id === resubmitted.id)
  const full = await revise(partial.id, '申請整段銷假', 'L51 整段銷假', null, null)
  check('L51-07 全文銷假待審仍有效', (await detail(a.page, full.id)).body.document.effective.id === partial.id)
  await review(full.id, false, 'L51 維持有效')
  const retriedFull = await revise(full.id, '申請整段銷假', 'L51 銷假退回重送', null, null)
  await review(retriedFull.id, true)
  const done = (await detail(a.page, retriedFull.id)).body
  check('L51-07 全文核准停止有效，舊核准與退回保留', done.document.canceled && done.document.effective === null &&
    done.document.versions.find(v => v.id === full.id).decisionReason === 'L51 維持有效' && done.document.originalApproved.id === resubmitted.id)
  const forbidden = await api(c.page, 'GET', '/api/leave/me/' + retriedFull.id)
  const attendanceForbidden = await api(b.page, 'GET', '/api/admin/attendance/employees')
  check('L51-16/20 他人單據404、MANAGER未取得全公司出勤權', forbidden.status === 404 && attendanceForbidden.status === 403)
  // 真後端已提交，僅在瀏覽器破壞成功本文；每次使用獨立新UUID並核对資料。
  await go(a.page, 'leave')
  await setValue(a.page, '#lv-date', day); await setValue(a.page, '#lv-reason', 'L51 邊界來源')
  await click(a.page, '送出申請'); await a.page.waitForFunction(() => document.querySelector('[data-testid=leave-notice]')?.textContent.includes('已送出'))
  let boundary = await byReason('L51 邊界來源')
  for (const mode of ['read', 'missing', 'uuid', 'operation', 'source', 'document', 'content']) {
    await go(a.page, 'leave'); await open(a.page, boundary.id); await click(a.page, '修改並重新送審')
    await a.page.waitForSelector('[data-testid=leave-revision-form]'); await setValue(a.page, '#rev-reason', 'L51 邊界 ' + mode)
    await a.page.evaluate(mode => {
      const original = window.fetch
      window.fetch = async (...args) => {
        const response = await original(...args)
        if (String(args[0]).endsWith('/revisions') && response.ok) {
          window.fetch = original; const read = response.json.bind(response)
          response.json = async () => {
            if (mode === 'read') throw new TypeError('受控本文讀取失敗')
            const d = await read()
            if (mode === 'missing') { delete d.request.revision; return d }
            if (mode === 'uuid') return { ...d, requestId: '00000000-0000-4000-8000-000000000000' }
            if (mode === 'operation') return { ...d, operation: 'CANCEL_ALL' }
            if (mode === 'source') return { ...d, sourceRequestId: d.sourceRequestId + 999 }
            if (mode === 'document') return { ...d, request: { ...d.request, revision: { ...d.request.revision, documentId: d.request.revision.documentId + 999 } } }
            return { ...d, request: { ...d.request, reason: '另一內容' } }
          }
        }
        return response
      }
    }, mode)
    const offset = bodies.length
    await click(a.page, '送出新版本申請'); await a.page.waitForFunction(() => document.querySelector('[data-testid=leave-revision-form]')?.dataset.phase === 'unknown')
    check('L51-22 ' + mode + '成功本文拒絕、保留來源與快照', await a.page.$eval('[data-testid=leave-revision-form] fieldset', e => e.disabled))
    await click(a.page, '放棄追蹤'); await a.page.waitForSelector('[data-testid=revision-abandon-warning]')
    check('L51-23 ' + mode + '放棄前警告已可能建立', await a.page.$eval('[data-testid=revision-abandon-warning]', e => e.textContent.includes('原提交可能已建立')))
    await click(a.page, '繼續確認原提交')
    await a.page.evaluate(() => {
      window.__revision503 = false
      const original = window.fetch
      window.fetch = async (...args) => {
        if (String(args[0]).endsWith('/revisions')) { window.__revision503 = true; window.fetch = original; return new Response(JSON.stringify({ code: 'SERVER_ERROR', message: '受控5xx' }), { status: 503, headers: { 'Content-Type': 'application/json' } }) }
        return original(...args)
      }
    })
    await click(a.page, '重新送出原內容'); await a.page.waitForFunction(() => window.__revision503 && document.querySelector('[data-testid=leave-revision-form]')?.dataset.phase === 'unknown')
    check('L51-23 ' + mode + '重試5xx仍鎖定', await a.page.$eval('[data-testid=leave-revision-form]', e => e.dataset.phase === 'unknown' && e.dataset.unconfirmed === 'true'))
    await click(a.page, '重新送出原內容'); await submitted(a.page)
    boundary = await byReason('L51 邊界 ' + mode)
    check('L51-23 ' + mode + '真HTTP原本文重播、只一筆', bodies.length === offset + 2 && bodies[offset].body === bodies[offset + 1].body &&
      (await api(a.page, 'GET', '/api/leave/me')).body.items.filter(r => r.reason === 'L51 邊界 ' + mode).length === 1)
  }
  // 明確放棄只解除追蹤，真後端已建立的提交不被撤回。
  await go(a.page, 'leave'); await open(a.page, boundary.id); await click(a.page, '修改並重新送審')
  await a.page.waitForSelector('[data-testid=leave-revision-form]'); await setValue(a.page, '#rev-reason', 'L51 放棄追蹤')
  await a.page.evaluate(() => {
    const original = window.fetch
    window.fetch = async (...args) => {
      const response = await original(...args)
      if (String(args[0]).endsWith('/revisions') && response.ok) { window.fetch = original; response.json = async () => JSON.parse('{broken') }
      return response
    }
  })
  await click(a.page, '送出新版本申請'); await a.page.waitForFunction(() => document.querySelector('[data-testid=leave-revision-form]')?.dataset.phase === 'unknown')
  await click(a.page, '放棄追蹤'); await a.page.waitForSelector('[data-testid=revision-abandon-warning]'); await click(a.page, '確定放棄追蹤'); await submitted(a.page)
  boundary = await byReason('L51 放棄追蹤')
  check('L51-23 明確放棄不撤回後端已建立版本', boundary.status === 'PENDING' && !(await a.page.$eval('[data-testid=leave-form] fieldset', e => e.disabled)))
  // 收到成功Response後延遲交给表單，切頁卸載後不能更新新頁。
  await go(a.page, 'leave'); await open(a.page, boundary.id); await click(a.page, '修改並重新送審')
  await a.page.waitForSelector('[data-testid=leave-revision-form]'); await setValue(a.page, '#rev-reason', 'L51 卸載晚到')
  await a.page.evaluate(() => {
    const original = window.fetch; window.__postHeld = false
    window.fetch = async (...args) => {
      const response = await original(...args)
      if (String(args[0]).endsWith('/revisions') && response.ok) {
        window.fetch = original; window.__postHeld = true; await new Promise(resolve => { window.__releaseRevision = resolve })
      }
      return response
    }
  })
  const lateOffset = bodies.length
  await click(a.page, '送出新版本申請'); await a.page.waitForFunction(() => window.__postHeld)
  await a.page.evaluate(() => { location.hash = '/attendance' }); await a.page.waitForSelector('[data-testid=today-status]')
  await a.page.evaluate(() => window.__releaseRevision())
  await a.page.waitForFunction(() => document.querySelector('[data-testid=today-status]') && !document.querySelector('[data-testid=leave-notice]'))
  boundary = await byReason('L51 卸載晚到')
  check('L51-23 卸載晚到不改新畫面、無自動重送', bodies.length === lateOffset + 1 && boundary.status === 'PENDING' && a.page.url().endsWith('/attendance'))
  // 真正登出Session后回傳401，沿用登入失效流程，重新登入不会自動重送。
  await go(a.page, 'leave'); await open(a.page, boundary.id); await click(a.page, '修改並重新送審')
  await a.page.waitForSelector('[data-testid=leave-revision-form]'); await setValue(a.page, '#rev-reason', 'L51 401不重送')
  await a.page.evaluate(() => {
    const original = window.fetch
    window.fetch = async (...args) => {
      if (String(args[0]).endsWith('/revisions')) {
        window.fetch = original
        const token = decodeURIComponent(document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='))?.slice(11) ?? '')
        await original('/api/auth/logout', { method: 'POST', headers: { 'X-XSRF-TOKEN': token } })
        return new Response(JSON.stringify({ code: 'UNAUTHENTICATED', message: '受控401' }), { status: 401, headers: { 'Content-Type': 'application/json' } })
      }
      return original(...args)
    }
  })
  const authOffset = bodies.length
  await click(a.page, '送出新版本申請'); await a.page.waitForSelector('#username')
  await loginAs(a.page, stack.users.a.username, stack.users.a.password, stack.baseUrl)
  await go(a.page, 'leave')
  check('L51-23 401返回登入、重登不重播另一意圖', bodies.length === authOffset && !(await byReason('L51 401不重送')) && (await detail(a.page, boundary.id)).body.request.status === 'PENDING')
  await a.page.setViewport({ width: 390, height: 844, isMobile: true, hasTouch: true })
  await go(a.page, 'leave'); await open(a.page, retriedFull.id)
  check('L51-24 新版詳情手機模擬尺寸無橫向溢出', !!(await a.page.$('[data-testid=leave-document]')) && await noHorizontalOverflow(a.page))
  await a.page.screenshot({ path: path.join(OUT, 't05-1-mobile.png'), fullPage: true })
  check('L51 無未捕捉例外', [a,b,c,admin].every(x => x.tracker.pageErrors.length === 0) &&
    (await Promise.all([a,b,c,admin].map(x => x.tracker.unhandledRejections()))).every(x => x.length === 0))
} catch (error) {
  check('T05.1 腳本完整執行', false, step + ': ' + String(error?.message ?? error).split('\n')[0])
  console.log('最近寫入狀態：' + recentHttp.slice(-6).join('; '))
  if (activePage) await activePage.screenshot({ path: path.join(OUT, 't05-1-failure.png'), fullPage: true })
} finally {
  if (browser) await browser.close()
  if (stack) await stack.stop()
  finish()
}
