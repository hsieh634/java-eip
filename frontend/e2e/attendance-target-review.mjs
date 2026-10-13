// R1：受控舊清單／延遲 GET，實際表單、POST、隔離後端與 PostgreSQL 資料比較。
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { OUT, createRunner, launchBrowser, loginAs } from './helpers.mjs'
import { startIsolatedStack } from './isolated-stack.mjs'
import { newPage, click, setValue, taipeiDate } from './ui.mjs'

const { check, finish } = createRunner()
let stack, browser
const call = (page, method, url, body) => page.evaluate(async (method, url, body) => {
  const token = decodeURIComponent(document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='))?.slice(11) ?? '')
  const response = await fetch(url, { method, headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: body ? JSON.stringify(body) : undefined })
  return { status: response.status, body: await response.json() }
}, method, url, body)

function database(employeeId) {
  if (!Number.isSafeInteger(employeeId) || employeeId <= 0) throw new Error('無效測試員工 ID')
  const sql = `select json_build_object('catalog',current_database(),
    'records',(select coalesce(json_agg(a order by a.id),'[]') from attendance a where employee_id=${employeeId}),
    'requests',(select coalesce(json_agg(r order by r.id),'[]') from attendance_request r where employee_id=${employeeId}),
    'history',(select coalesce(json_agg(h order by h.id),'[]') from attendance_history h where employee_id=${employeeId}),
    'events',(select coalesce(json_agg(e order by e.id),'[]') from attendance_clock_event e where employee_id=${employeeId}))
    where current_database()='renewflow_test';`
  const testDb = process.env.DB_TEST_NAME ?? 'renewflow_test'
  if (!/^[a-z][a-z0-9_]*_test$/.test(testDb) || testDb === (process.env.DB_NAME ?? 'renewflow')) throw new Error('測試資料庫不符合隔離規則')
  const guardedSql = sql.replace("current_database()='renewflow_test'", "current_database()='" + testDb + "'")
  const result = spawnSync('docker', ['compose', 'exec', '-T', 'db', 'sh', '-c', 'exec psql -U "$POSTGRES_USER" -d "$1" -At', 'sh', testDb],
    { cwd: path.resolve('..'), input: guardedSql, encoding: 'utf8', windowsHide: true })
  if (result.status !== 0 || !result.stdout.trim()) throw new Error('隔離資料庫唯讀比較失敗（不輸出連線或敏感資料）')
  const state = JSON.parse(result.stdout.trim())
  if (state.catalog !== testDb) throw new Error('實際 catalog 不在本腳本 allowlist')
  return state
}
const unchanged = (a, b) => ['records', 'events'].every(k => JSON.stringify(a[k]) === JSON.stringify(b[k]))
const identical = (a, b) => JSON.stringify(a) === JSON.stringify(b)

try {
  stack = await startIsolatedStack(); browser = await launchBrowser()
  const adminContext = await browser.createBrowserContext()
  const admin = await newPage(adminContext)
  await loginAs(admin.page, stack.users.admin.username, stack.users.admin.password, stack.baseUrl)
  const employees = (await call(admin.page, 'GET', '/api/admin/attendance/employees')).body
  const employeeA = employees.find(e => e.employeeNo === `EA-${stack.runId}`).id
  const employeeC = employees.find(e => e.employeeNo === `EC-${stack.runId}`).id
  const a = await newPage(await browser.createBrowserContext())
  const c = await newPage(await browser.createBrowserContext())
  await loginAs(a.page, stack.users.a.username, stack.users.a.password, stack.baseUrl)
  await loginAs(c.page, stack.users.c.username, stack.users.c.password, stack.baseUrl)
  const today = taipeiDate(), yesterday = taipeiDate(-1), beforeYesterday = taipeiDate(-2)
  const requests = new Map([[a.page, []], [c.page, []]])
  for (const page of requests.keys()) page.on('request', r => {
    if (r.method() === 'POST' && new URL(r.url()).pathname === '/api/attendance/requests') requests.get(page).push(JSON.parse(r.postData()))
  })
  async function seed(employeeId, workDate, clockInTime, clockOutTime) {
    const result = await call(admin.page, 'POST', '/api/admin/attendance/records/correct', { employeeId, workDate, clockInTime, clockOutTime, reason: 'R1 專用時段' })
    if (result.status !== 200) throw new Error(`專用補登失敗 HTTP ${result.status}`)
    return result.body
  }
  const first = await seed(employeeA, today, '00:10:00.123456', '00:20:00.654321')
  const second = await seed(employeeA, today, '00:30:00', '00:40:00')
  await seed(employeeA, yesterday, '08:00:00', '09:00:00')
  const only = await seed(employeeC, today, '00:10:00', '00:20:00')
  // 建立真打卡事件，再作廢這一組；R1 操作必須保留這些原事件。
  const eventIn = await call(a.page, 'POST', '/api/attendance/clock-in', { requestId: crypto.randomUUID() })
  const eventOut = await call(a.page, 'POST', '/api/attendance/clock-out', { requestId: crypto.randomUUID(), expectedAttendanceId: eventIn.body.id, expectedVersion: eventIn.body.version })
  if (eventOut.status !== 200) throw new Error('專用打卡事件前置失敗')
  async function voidRecord(employeeId, record) {
    const result = await call(admin.page, 'POST', '/api/admin/attendance/records/void', { employeeId, attendanceId: record.id, expectedVersion: record.version, reason: 'R1 專用作廢' })
    if (result.status !== 200) throw new Error(`專用作廢失敗 HTTP ${result.status}`)
  }
  await voidRecord(employeeA, eventOut.body)

  async function open(page, id, oldList) {
    if (oldList) await page.evaluate(list => {
      const original = window.fetch
      window.__r1RestoreList = () => { window.fetch = original }
      window.fetch = async (...args) => {
        if (args[0] === '/api/attendance/me' && (!args[1]?.method || args[1].method === 'GET')) {
          return new Response(JSON.stringify(list), { status: 200, headers: { 'Content-Type': 'application/json' } })
        }
        return original(...args)
      }
    }, oldList)
    if (oldList) {
      // 同頁切到其他路由再回來，保留受控 fetch；不是重載整個 window。
      await page.evaluate(() => { location.hash = '#/' })
      await page.waitForFunction(() => !document.querySelector('[data-testid=records-table]'))
      await page.evaluate(() => { location.hash = '#/attendance' })
    } else await page.goto(`${stack.baseUrl}/#/attendance`, { waitUntil: 'networkidle0' })
    await page.waitForSelector('[data-testid=records-table]')
    if (id) await page.$eval(`[data-record-id="${id}"] button`, b => b.click())
    else await click(page, '申請更正／補登時間')
    await page.waitForFunction(() => document.querySelector('[data-testid=corr-original]')?.dataset.state === 'ready')
    if (oldList) await page.evaluate(() => window.__r1RestoreList())
  }
  async function fill(page, reason, inTime = '00:50:00', outTime = '01:00:00') {
    await setValue(page, '#corr-in', inTime); await setValue(page, '#corr-out', outTime); await setValue(page, '#corr-reason', reason)
  }
  async function submit(page, force = false) {
    if (force) await page.$eval('#corr-date', e => e.closest('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })))
    else await click(page, '送出申請')
    await page.waitForFunction(() => !document.querySelector('#corr-date') || [...document.querySelectorAll('.correction-form .form-error')].some(e => e.textContent.trim()))
  }
  async function cancelRequests(page, employeeId) {
    for (const r of database(employeeId).requests.filter(r => r.status === 'PENDING')) {
      const result = await call(page, 'POST', `/api/attendance/requests/${r.id}/cancel`)
      if (result.status !== 200) throw new Error('專用申請清理失敗')
    }
  }
  async function confirmRequest(page, employeeId, name, expectedId, expectedVersion, expectedDate) {
    const before = database(employeeId), offset = requests.get(page).length
    await submit(page)
    const after = database(employeeId), body = requests.get(page)[offset], saved = after.requests.find(r => r.reason === name)
    check(`${name} POST 與資料庫目標正確`, body?.expectedAttendanceId === expectedId && body?.expectedVersion === expectedVersion && body?.workDate === expectedDate &&
      saved?.target_attendance_id === expectedId && saved?.target_version === expectedVersion && saved?.status === 'PENDING' && saved?.work_date === expectedDate)
    check(`${name} 核准前其他組與原事件不變`, unchanged(before, after) && after.requests.length === before.requests.length + 1 && after.history.length === before.history.length + 1)
    await cancelRequests(page, employeeId)
    return body
  }
  async function invalid(page, employeeId, record, oldList, name) {
    await open(page, record.id, oldList)
    const chosen = await page.$eval('#corr-target', e => e.value)
    const original = await page.$eval('[data-testid=corr-original]', e => e.textContent)
    check(`${name} 指定失效仍待選並標示原 ID`, chosen === 'choose' && original.includes(String(record.id)) && original.includes('失效'))
    check(`${name} 待選按鈕不可提交`, await page.$eval('#corr-date', e => e.closest('form').querySelector('button[type=submit]').disabled))
    await fill(page, name)
    const before = database(employeeId), count = requests.get(page).length
    await submit(page, true)
    const after = database(employeeId)
    check(`${name} 強制 submit 仍無 POST 與資料變更`, requests.get(page).length === count && identical(before, after))
    await cancelRequests(page, employeeId) // 原版可能錯誤建立；只撤回本次隔離員工的申請，讓同套案例繼續。
  }

  // R1-05：原目標有效時，仍選它的最新資料；未改的上班時間保留六位小數秒。
  await open(a.page, first.id)
  check('R1-05 多組指定入口維持原 ID 與時間', await a.page.$eval('#corr-target', e => e.value) === String(first.id) && await a.page.$eval('#corr-in', e => e.value) === '00:10:00')
  await fill(a.page, 'R1-05', '00:10:00', '00:21:00')
  const precise = await confirmRequest(a.page, employeeA, 'R1-05', first.id, first.version, today)
  check('R1-05 未改預填值保留微秒', precise?.clockInTime === '00:10:00.123456')

  // R1-06：表單取得快照後被更正；拒絕不新增申請／歷程，其他組與事件不變。
  await open(a.page, first.id)
  const changed = await call(admin.page, 'POST', '/api/admin/attendance/records/correct', { employeeId: employeeA, attendanceId: first.id, expectedVersion: first.version, workDate: today, clockInTime: '00:10:00.123456', clockOutTime: '00:22:00', reason: 'R1 版本變動' })
  if (changed.status !== 200) throw new Error('版本變動前置失敗')
  await fill(a.page, 'R1-06 更正', '00:10:00', '00:23:00')
  const beforeStale = database(employeeA)
  const rejected = a.page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/attendance/requests')
  await submit(a.page)
  const response = await rejected
  check('R1-06 更正後舊快照 POST 為原 ID／版本且 409 RECORD_CHANGED', response.status() === 409 && (await response.json()).code === 'RECORD_CHANGED' && requests.get(a.page).at(-1).expectedAttendanceId === first.id && requests.get(a.page).at(-1).expectedVersion === first.version)
  check('R1-06 拒絕零副作用', identical(beforeStale, database(employeeA)))

  const oldA = (await call(a.page, 'GET', '/api/attendance/me')).body
  await voidRecord(employeeA, changed.body)
  await invalid(a.page, employeeA, first, oldA, 'R1-01')
  // R1-03：同一失效入口，使用者明確選另一組。
  await open(a.page, first.id, oldA)
  await a.page.select('#corr-target', String(second.id))
  check('R1-03 明選另一組更新原與申請時間', await a.page.$eval('#corr-in', e => e.value) === '00:30:00' && (await a.page.$eval('[data-testid=corr-original]', e => e.textContent)).includes('00:40:00'))
  await fill(a.page, 'R1-03', '00:30:00', '00:41:00')
  await confirmRequest(a.page, employeeA, 'R1-03', second.id, second.version, today)
  // R1-04：有其他有效組，明選補登不能誤顯示成完全沒有紀錄。
  await open(a.page, first.id, oldA)
  await a.page.select('#corr-target', 'new')
  check('R1-04 明選補登清空時間並正確說明其他組', await a.page.$eval('#corr-in', e => e.value) === '' && await a.page.$eval('#corr-out', e => e.value) === '' && !(await a.page.$eval('[data-testid=corr-original]', e => e.textContent)).includes('這一天沒有出勤紀錄'))
  await fill(a.page, 'R1-04')
  await confirmRequest(a.page, employeeA, 'R1-04', null, null, today)

  // R1-06 作廢分支，隨後 R1-02：沒有有效組時也不能自動補登。
  await open(c.page, only.id)
  const oldC = (await call(c.page, 'GET', '/api/attendance/me')).body
  await voidRecord(employeeC, only)
  await fill(c.page, 'R1-06 作廢')
  const beforeVoidStale = database(employeeC)
  const voidRejected = c.page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/attendance/requests')
  await submit(c.page)
  check('R1-06 作廢後舊快照 409 且零副作用', (await voidRejected).status() === 409 && identical(beforeVoidStale, database(employeeC)))
  await invalid(c.page, employeeC, only, oldC, 'R1-02')
  await open(c.page, only.id, oldC); await c.page.select('#corr-target', 'new'); await fill(c.page, 'R1-04 無有效組')
  await confirmRequest(c.page, employeeC, 'R1-04 無有效組', null, null, today)

  // R1-07：通用入口，日期換到一組與無組仍沿用正常預設。
  await open(a.page)
  await setValue(a.page, '#corr-date', yesterday)
  await a.page.waitForFunction(() => document.querySelector('[data-testid=corr-original]')?.dataset.state === 'ready' && document.querySelector('#corr-in')?.value === '08:00:00')
  const yesterdayRecord = database(employeeA).records.find(r => r.work_date === yesterday)
  await fill(a.page, 'R1-07 更正', '08:00:00', '09:10:00')
  await confirmRequest(a.page, employeeA, 'R1-07 更正', yesterdayRecord.id, yesterdayRecord.version, yesterday)
  await open(c.page); await fill(c.page, 'R1-07 補登')
  await confirmRequest(c.page, employeeC, 'R1-07 補登', null, null, today)

  // R1-08：已收到昨天的真回應，但延遲交給表單；新日期先完成，再釋出舊回應。
  await open(a.page, second.id)
  await a.page.evaluate(date => {
    const original = window.fetch
    window.__r1Held = false; window.__r1Released = false
    window.fetch = async (...args) => {
      if (String(args[0]).includes(`/api/attendance/me?from=${date}&to=${date}`)) {
        const response = await original(...args)
        window.__r1Held = true
        await new Promise(resolve => { window.__r1Release = resolve })
        window.__r1Released = true
        return response
      }
      return original(...args)
    }
  }, yesterday)
  await setValue(a.page, '#corr-date', yesterday)
  await a.page.waitForFunction(() => window.__r1Held)
  await setValue(a.page, '#corr-date', beforeYesterday)
  await a.page.waitForFunction(() => document.querySelector('[data-testid=corr-original]')?.dataset.state === 'ready')
  await a.page.evaluate(() => window.__r1Release())
  await a.page.waitForFunction(() => window.__r1Released)
  await new Promise(resolve => setTimeout(resolve, 100))
  check('R1-08 舊回應晚到只保留新日期待選且清空時間', await a.page.$eval('#corr-date', e => e.value) === beforeYesterday && await a.page.$eval('#corr-target', e => e.value) === 'choose' && await a.page.$eval('#corr-in', e => e.value) === '' && await a.page.$eval('#corr-out', e => e.value) === '')
  await a.page.select('#corr-target', 'new'); await fill(a.page, 'R1-08')
  await confirmRequest(a.page, employeeA, 'R1-08', null, null, beforeYesterday)
  await open(a.page, first.id, oldA)
  await a.page.screenshot({ path: path.join(OUT, 'r1-target-invalid.png'), fullPage: true })
  check('R1 無未捕捉例外', [a, c, admin].every(x => x.tracker.pageErrors.length === 0) && (await Promise.all([a, c, admin].map(x => x.tracker.unhandledRejections()))).every(x => x.length === 0))
} catch (error) {
  check('R1 腳本完整執行', false, String(error?.message ?? error).split('\n')[0])
} finally {
  if (browser) await browser.close()
  if (stack) await stack.stop()
  finish()
}
