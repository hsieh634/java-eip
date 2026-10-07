// T03.3 瀏覽器、實際隔離後端、專用員工；json() 故障是受控替身。
import path from 'node:path'
import { OUT, createRunner, launchBrowser, loginAs } from './helpers.mjs'
import { startIsolatedStack } from './isolated-stack.mjs'
import { newPage, click, waitText } from './ui.mjs'
const { check, finish } = createRunner()
let stack, browser
const call = (page, action, body) => page.evaluate(async (a,b) => {
  const token = decodeURIComponent(document.cookie.split('; ').find(x => x.startsWith('XSRF-TOKEN='))?.slice(11) ?? '')
  const response = await fetch(a, { method: b ? 'POST' : 'GET', headers: { 'Content-Type':'application/json', 'X-XSRF-TOKEN':token }, body: b ? JSON.stringify(b) : undefined })
  return { status: response.status, body: await response.json() }
}, action, body)
try {
  stack = await startIsolatedStack(); browser = await launchBrowser()
  const context = await browser.createBrowserContext()
  const { page, tracker } = await newPage(context, { viewport: { width: 1200, height: 900 } })
  await loginAs(page, stack.users.a.username, stack.users.a.password, stack.baseUrl)
  await page.waitForSelector('[data-testid=today-status]')
  const bodies = []
  page.on('request', r => { if (r.method() === 'POST' && /\/api\/attendance\/clock-/.test(r.url())) bodies.push({ path: new URL(r.url()).pathname, body: JSON.parse(r.postData()) }) })
  for (let i=1; i<=3; i++) {
    await click(page, '上班打卡'); await waitText(page, '上班打卡成功')
    await page.waitForFunction(() => document.querySelector('[data-testid=today-status]')?.textContent === '工作中')
    await click(page, '下班打卡')
    await page.waitForFunction(() => document.querySelector('[data-testid=today-status]')?.textContent.includes('可再次上班'))
    check(`M01 第 ${i} 組保留獨立紀錄`, await page.$$eval('[data-testid=today-sessions] li', rows => rows.length) === i)
  }
  const original = await call(page, '/api/attendance/me/today')
  check('M01 三組 ID 與每次邏輯 requestId 都不同', new Set(original.body.records.map(r=>r.id)).size === 3 && new Set(bodies.map(r=>r.body.requestId)).size === 6)
  const sum = original.body.records.reduce((n,r)=>n+(Date.parse(r.clockOutAt)-Date.parse(r.clockInAt))/1000,0)
  check('M01 完成合計與實際時段一致（顯示至秒）', Math.abs(original.body.completedSeconds-Math.floor(sum)) <= 1)
  const replay = await call(page, bodies[0].path, bodies[0].body)
  const after = await call(page, '/api/attendance/me/today')
  check('M03 延遲重送第一組上班不建立第四組', replay.status === 200 && replay.body.id === original.body.records[0].id && after.body.records.length === 3)
  await page.select('[data-testid=today-target]', String(original.body.records[0].id))
  await click(page, '申請整組作廢並重新打卡')
  check('M07 作廢表單清楚顯示選中 ID 與其他時段不變', (await page.$eval('[data-testid=reset-form]',f=>f.textContent)).includes(`#${original.body.records[0].id}`) && (await page.$eval('[data-testid=reset-form]',f=>f.textContent)).includes('其他時段不變'))
  await click(page, '取消')
  // 送出成功但本文損壞：必須追蹤原上班意圖，不換成下班。
  await page.evaluate(() => {
    const originalFetch = window.fetch
    window.fetch = async (...args) => {
      const response = await originalFetch(...args)
      if (args[0] === '/api/attendance/clock-in' && args[1]?.method === 'POST' && response.ok) {
        window.fetch = originalFetch; response.json = async () => JSON.parse('{broken')
      }
      return response
    }
  })
  const offset = bodies.length
  await click(page, '上班打卡'); await page.waitForSelector('[data-testid=clock-unconfirmed]')
  check('M12 成功本文不可用：仍追蹤原上班，兩個新操作停用', await page.$$eval('.card--attendance .btn--large', buttons=>buttons.every(b=>b.disabled)))
  await click(page, '重送原打卡確認'); await page.waitForFunction(()=>!document.querySelector('[data-testid=clock-unconfirmed]'))
  check('M12 手動重試相同操作、原本文與 requestId', JSON.stringify(bodies[offset]) === JSON.stringify(bodies[offset+1]))
  check('M12 回應確認後仍只有四組', (await call(page, '/api/attendance/me/today')).body.records.length === 4)
  for (const mode of ['read', 'structure', 'request']) {
    await click(page, '下班打卡')
    await page.waitForFunction(() => document.querySelector('[data-testid=today-status]')?.textContent.includes('可再次上班'))
    await page.evaluate(mode => {
      const originalFetch = window.fetch
      window.fetch = async (...args) => {
        const response = await originalFetch(...args)
        if (args[0] === '/api/attendance/clock-in' && args[1]?.method === 'POST' && response.ok) {
          window.fetch = originalFetch
          const read = response.json.bind(response)
          if (mode === 'read') response.json = async () => { throw new TypeError('controlled body read failure') }
          else if (mode === 'structure') response.json = async () => ({ id: 1 })
          else response.json = async () => ({ ...await read(), requestId: crypto.randomUUID() })
        }
        return response
      }
    }, mode)
    const offset = bodies.length
    await click(page, '上班打卡'); await page.waitForSelector('[data-testid=clock-unconfirmed]')
    await click(page, '重送原打卡確認'); await page.waitForFunction(()=>!document.querySelector('[data-testid=clock-unconfirmed]'))
    check(`M12 ${mode}：成功本文不可用後仍重送相同打卡與原本文`, JSON.stringify(bodies[offset]) === JSON.stringify(bodies[offset+1]))
  }
  await page.click('a[href="#/attendance"]')
  await click(page, '申請更正／補登時間'); await page.waitForSelector('#corr-target')
  check('M07 多組更正必須明確選目標或補登', await page.$eval('#corr-target',s=>s.value === 'choose' && s.options.length === 9))
  await page.select('#corr-target', String(original.body.records[0].id))
  check('M07 更正所選第一組顯示原時間', await page.$eval('[data-testid=corr-original]',e=>!e.textContent.includes('沒有出勤')))
  await page.setViewport({ width:390,height:844,isMobile:true,hasTouch:true })
  check('U01 手機模擬尺寸無橫向溢出', await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1))
  await page.screenshot({ path: path.join(OUT,'multiple-attendance-mobile.png'), fullPage:true })
  check('M12 無未捕捉例外', tracker.pageErrors.length===0 && (await tracker.unhandledRejections()).length===0)
} catch (error) { check('腳本完整執行', false, String(error?.message ?? error).split('\n')[0]) }
finally { if (browser) await browser.close(); if (stack) await stack.stop(); finish() }
