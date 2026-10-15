// T05.2：本批專屬新庫，真HTTP／PostgreSQL及真表單；fetch本文／延遲故障為受控。
import crypto from 'node:crypto'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { ApiClient, readDatabase } from '../../tools/seed-stmax-demo.mjs'
import { createRunner, launchBrowser, loginAs, OUT } from './helpers.mjs'
import { click, newPage, setValue, taipeiDate, noHorizontalOverflow, waitText } from './ui.mjs'

process.env.E2E_API_PORT ??= '8082'; process.env.E2E_VITE_PORT ??= '5175'
const { startIsolatedStack } = await import('./isolated-stack.mjs')
const { check, finish } = createRunner()
const run = Date.now().toString(36) + '_' + crypto.randomBytes(3).toString('hex')
const database = 'renewflow_t052_ui_' + run + '_test', WF = '/api/admin/leave/workflow'
let owned = false, stack, browser, page, step = '建立本批測試庫'
const sql = q => readDatabase(database, q)
const tables = ['employee','department','organization_history','attendance','attendance_request','attendance_history','geofence_setting','geofence_setting_history','attendance_clock_event','project','timesheet_entry','leave_request','leave_history','leave_approver_setting','attendance_clock_submission','leave_document','leave_workflow_version','leave_workflow_history','leave_workflow_state']
const snapshot = () => sql('select json_build_object(' + tables.map(t => `'${t}',(select md5(coalesce(string_agg(row_to_json(x)::text,E'\n' order by row_to_json(x)::text),'')) from ${t} x)`).join(',') + ');')
const count = t => Number(sql('select count(*) from ' + t + ';'))
const same = (a,b) => JSON.stringify(a) === JSON.stringify(b)
try {
  if (readDatabase('renewflow',"select count(*) from pg_database where datname='"+database+"';") !== '0') throw new Error('名稱碰撞，不接管')
  const provision = spawnSync('docker',['exec','renewflow-db','sh','-c','exec createdb -U "$POSTGRES_USER" "$1"','sh',database],{windowsHide:true,encoding:'utf8'})
  if (provision.status !== 0) throw new Error('新測試庫建立失敗')
  owned = true; process.env.DB_TEST_NAME = database
  stack = await startIsolatedStack(); browser = await launchBrowser()
  const admin = new ApiClient(stack.baseUrl); await admin.login(stack.users.admin.username,stack.users.admin.password)
  const a = new ApiClient(stack.baseUrl); await a.login(stack.users.a.username,stack.users.a.password)
  const b = new ApiClient(stack.baseUrl); await b.login(stack.users.b.username,stack.users.b.password)
  const people = (await admin.call('GET','/api/admin/employees?status=ALL&page=0&size=20')).items
  const employee = people.find(e=>e.username===stack.users.a.username), manager = people.find(e=>e.username===stack.users.b.username)
  await admin.call('PUT','/api/admin/employees/'+employee.id,{displayName:employee.displayName,role:employee.role,enabled:true,departmentId:null,managerId:manager.id,expectedVersion:employee.version,reason:'本批流程測試主管'})
  const reviewer = await admin.call('POST','/api/admin/employees',{employeeNo:'WF-'+run,username:'wf_'+run,displayName:'流程測試審核人',role:'MANAGER',departmentId:null,managerId:null,initialPassword:stack.users.admin.password,reason:'本批隔離人員'})
  const today = taipeiDate()
  const submit = (key,start,end,reason) => a.call('POST','/api/leave/me',{requestId:key,leaveType:'PERSONAL',workDate:today,startTime:start,endTime:end,reason})
  const root = await submit(crypto.randomUUID(),'09:00','10:00','流程發布前提交')
  check('W02 無發布流程仍採原直屬主管、保存無流程ID的預設快照',root.request.approver.id===manager.id && root.request.approvalSnapshot.source==='DEFAULT_MANAGER' && root.request.approvalSnapshot.workflowVersionId===null)
  step = 'ADMIN設定真畫面'
  const ui = await newPage(await browser.createBrowserContext(),{viewport:{width:1200,height:900}}); page = ui.page
  await loginAs(page,stack.users.admin.username,stack.users.admin.password,stack.baseUrl)
  await page.goto(stack.baseUrl+'/#/admin/leave/workflow'); await page.waitForSelector('#wf-name')
  check('W01 ADMIN可看設定且明示個人優先與設定權≠審核權',(await page.evaluate(()=>document.body.innerText)).includes('個人指定審核人優先'))
  await page.evaluate(() => {
    const original = window.fetch.bind(window)
    window.__wfPosts = []; window.__wfFault = ''; window.__wfVerify404 = false; window.__wfHeld = []; window.__wfHoldSettings = false
    window.fetch = async (input,init) => {
      const url=String(input),write=init && ['POST','PUT'].includes(init.method) && url.includes('/leave/workflow/drafts')
      if(url==='/api/admin/leave/workflow' && (init?.method ?? 'GET')==='GET' && window.__wfHoldSettings) {
        window.__wfHoldSettings=false; const response=await original(input,init),text=await response.text()
        await new Promise(resolve=>window.__wfHeld.push(resolve))
        return new Response(text,{status:response.status,headers:{'Content-Type':'application/json'}})
      }
      if(url.includes('/leave/workflow/operations/') && window.__wfVerify404) return new Response(JSON.stringify({code:'WORKFLOW_OPERATION_NOT_FOUND',message:'受控：尚未查到'}),{status:404,headers:{'Content-Type':'application/json'}})
      if(write) {
        window.__wfPosts.push({url,body:JSON.parse(init.body),method:init.method})
        const mode=window.__wfFault;window.__wfFault=''
        if(mode==='403') return new Response(JSON.stringify({code:'CSRF',message:'受控拒絕'}),{status:403,headers:{'Content-Type':'application/json'}})
        if(mode==='hold') await new Promise(resolve=>window.__wfHeld.push(resolve))
        const response=await original(input,init)
        if(mode==='bad-json') {await response.text();return new Response('{',{status:response.status,headers:{'Content-Type':'application/json'}})}
        if(mode==='wrong-id') {const body=await response.json();body.requestId=crypto.randomUUID();return new Response(JSON.stringify(body),{status:response.status,headers:{'Content-Type':'application/json'}})}
        if(mode==='body-fail') {await response.text();return {ok:response.ok,status:response.status,json:()=>Promise.reject(new TypeError('受控本文讀取失敗'))}}
        if(mode==='missing') {await response.text();return new Response('{}',{status:response.status,headers:{'Content-Type':'application/json'}})}
        if(mode==='500') {await response.text();return new Response('{"code":"INTERNAL","message":"受控回應遺失"}',{status:500,headers:{'Content-Type':'application/json'}})}
        return response
      }
      return original(input,init)
    }
  })
  await setValue(page,'#wf-name','隔離請假流程一');await setValue(page,'#wf-description','一關測試說明')
  await setValue(page,'#wf-source','DESIGNATED');await setValue(page,'#wf-approver',String(reviewer.id))
  check('W15 尚未儲存提示存在',!!(await page.$('[data-testid=workflow-dirty]')))
  await page.evaluate(()=>{window.__wfFault='bad-json'}); await click(page,'儲存草稿');await page.waitForSelector('[data-testid=workflow-unknown]')
  const first = await page.evaluate(()=>window.__wfPosts.at(-1)), draftId=(await admin.call('GET',WF)).draft.id
  check('W14 真建立但成功JSON損壞→未知、原表單鎖定',count('leave_workflow_version')===1 && await page.$eval('#wf-name',e=>e.matches(':disabled')))
  await page.evaluate(()=>{window.__wfVerify404=true});await click(page,'查證原操作');await waitText(page,'結果尚未確認')
  check('W14 查證404不當作確定未提交',!!(await page.$('[data-testid=workflow-unknown]')))
  await page.evaluate(()=>{window.__wfFault='403'});await click(page,'以原識別碼重試');await waitText(page,'受控拒絕')
  const retry = await page.evaluate(()=>window.__wfPosts.at(-1))
  check('W14 未知後重試403保留完全相同識別碼與本文',same(first,retry) && !!(await page.$('[data-testid=workflow-unknown]')))
  await page.evaluate(()=>{window.__wfVerify404=false});await click(page,'以原識別碼重試');await waitText(page,'已確認儲存')
  check('W14 原鍵重試確認同草稿、無重複歷程',count('leave_workflow_version')===1 && count('leave_workflow_history')===1)
  step = '預覽及草稿不影響正式'
  await setValue(page,'#wf-search',stack.users.a.username);await click(page,'搜尋預覽員工')
  await page.waitForFunction(id=>[...document.querySelector('#wf-employee').options].some(o=>o.value===String(id)),{},employee.id)
  await setValue(page,'#wf-employee',String(employee.id));await page.click('#wf-preview-draft')
  const beforePreview=snapshot();await click(page,'預覽解析結果');await page.waitForSelector('[data-testid=workflow-preview]')
  check('W04/W09 草稿預覽指定人、零業務寫入',(await page.$eval('[data-testid=workflow-preview]',e=>e.innerText)).includes(reviewer.displayName) && snapshot()===beforePreview)
  const prior=await submit(crypto.randomUUID(),'10:00','11:00','草稿未發布提交')
  check('W04 草稿未改變正式提交',prior.request.approver.id===manager.id && prior.request.approvalSnapshot.workflowVersionId===null)
  step = '草稿並行衝突與輸入保留'
  await setValue(page,'#wf-description','我的未存說明')
  const fresh=(await admin.call('GET',WF)).draft
  await admin.call('PUT',WF+'/drafts/'+draftId,{requestId:crypto.randomUUID(),expectedPublishedId:null,expectedVersion:fresh.rowVersion,values:{...fresh.values,description:'其他管理員先存'}})
  await click(page,'儲存草稿');await waitText(page,'草稿或目前發布版本已變更')
  check('W06/W15 過期儲存409、保留我的輸入',await page.$eval('#wf-description',e=>e.value)==='我的未存說明')
  await click(page,'重新查詢版本（保留輸入）');await waitText(page,'保留目前輸入');check('W15 重新查詢不覆蓋未存內容',await page.$eval('#wf-description',e=>e.value)==='我的未存說明')
  await click(page,'儲存草稿');await waitText(page,'已確認儲存');await click(page,'檢查發布差異');await setValue(page,'#wf-reason','隔離首次發布')
  check('W05 發布確認顯示差異與僅影響新提交',(await page.$eval('[data-testid=workflow-publish-confirm]',e=>e.innerText)).includes('不修改既有待審'))
  await page.evaluate(()=>{window.__wfFault='body-fail'});await click(page,'確認發布');await page.waitForSelector('[data-testid=workflow-unknown]')
  const publishPost=await page.evaluate(()=>window.__wfPosts.at(-1));check('W14 發布真成功但本文讀取失敗不冒稱成功',(await admin.call('GET',WF)).current.id===draftId && !!(await page.$('[data-testid=workflow-unknown]')))
  await click(page,'查證原操作');await waitText(page,'已確認發布')
  check('W09 發布後自動回正式預覽，不困在已消失的草稿',!(await page.$eval('#wf-preview-draft',e=>e.checked)))
  const now=(await admin.call('GET',WF)).current
  check('W14 發布原鍵GET查證成功、沒有第二次發布',now.id===draftId && Number(sql("select count(*) from leave_workflow_history where action='PUBLISHED';"))===1)
  check('W16 版本與發布歷程載入',await page.waitForFunction(()=>document.querySelector('[data-testid=workflow-history]')?.innerText.includes('隔離首次發布')).then(()=>true))
  const key=crypto.randomUUID(), post=await submit(key,'11:00','12:00','已發布後提交')
  check('W05 新提交採完整流程版與人，既有待審不變',post.request.approvalSnapshot.workflowVersionId===now.id && post.request.approver.id===reviewer.id && (await a.call('GET','/api/leave/me/'+root.request.id)).request.approver.id===manager.id)
  step = '手機、後續草稿、錯配回應'
  await setValue(page,'#wf-name','隔離請假流程二');await setValue(page,'#wf-source','MANAGER')
  await page.setViewport({width:390,height:844});await page.evaluate(()=>{window.__wfFault='wrong-id'});await click(page,'儲存草稿');await page.waitForSelector('[data-testid=workflow-unknown]')
  check('W14 缺乏正確UUID的成功回應保持未知',await page.$eval('#wf-name',e=>e.matches(':disabled')))
  check('W18 手機表單與未知確認無橫向溢出',await noHorizontalOverflow(page))
  await page.screenshot({path:path.join(OUT,'t05-2-workflow-mobile.png'),fullPage:true})
  await click(page,'查證原操作');await waitText(page,'已確認儲存');await click(page,'檢查發布差異');await setValue(page,'#wf-reason','隔離第二次發布')
  check('W18 手機發布確認可操作且無橫向溢出',await noHorizontalOverflow(page))
  await click(page,'確認發布');await waitText(page,'已確認發布')
  const second=(await admin.call('GET',WF)).current
  const originalSnap=post.request.approvalSnapshot
  const replay=await submit(key,'11:00','12:00','已發布後提交')
  check('W10 發布新版後原requestId重試仍是原ID／快照',replay.replayed && replay.request.id===post.request.id && same(replay.request.approvalSnapshot,originalSnap) && second.id!==originalSnap.workflowVersionId)
  check('W16 原發布操作查證仍回原版本',(await admin.call('GET',WF+'/operations/'+publishPost.body.requestId)).version.id===now.id)
  step = '舊GET晚到、連按與其他未知回應'
  await page.evaluate(()=>{window.__wfHoldSettings=true});await click(page,'重新查詢版本（保留輸入）');await page.waitForFunction(()=>window.__wfHeld.length===1)
  await setValue(page,'#wf-name','隔離請假流程三');await page.evaluate(()=>{window.__wfFault='hold'})
  const beforePosts=await page.evaluate(()=>window.__wfPosts.length)
  await page.evaluate(()=>{const button=[...document.querySelectorAll('button')].find(b=>b.textContent==='儲存草稿');button.click();button.click()})
  await page.waitForFunction(()=>window.__wfHeld.length===2)
  check('W14 送出同步鎖阻擋連按',await page.evaluate(()=>window.__wfPosts.length)===beforePosts+1)
  await page.evaluate(()=>window.__wfHeld.pop()());await waitText(page,'已確認儲存')
  const thirdDraft=(await admin.call('GET',WF)).draft
  await page.evaluate(()=>window.__wfHeld.pop()());await page.evaluate(()=>new Promise(r=>setTimeout(r,150)))
  check('W15 舊GET晚到不覆蓋新草稿／輸入',await page.$eval('#wf-name',e=>e.value)==='隔離請假流程三' && (await admin.call('GET',WF)).draft.id===thirdDraft.id)
  for(const mode of ['missing','500']) {
    await setValue(page,'#wf-description','未知測試 '+mode);await page.evaluate(mode=>{window.__wfFault=mode},mode);await click(page,'儲存草稿');await page.waitForSelector('[data-testid=workflow-unknown]')
    check('W14 '+mode+'成功後回應無法確認仍鎖原本文',await page.$eval('#wf-description',e=>e.matches(':disabled')))
    await click(page,'查證原操作');await waitText(page,'已確認儲存')
  }
  step = '角色、詳情與巢狀快照'
  const owner=await newPage(await browser.createBrowserContext(),{viewport:{width:1200,height:900}})
  await loginAs(owner.page,stack.users.a.username,stack.users.a.password,stack.baseUrl);await owner.page.goto(stack.baseUrl+'/#/leave');await owner.page.waitForSelector('[data-leave-id="'+post.request.id+'"] button');await owner.page.$eval('[data-leave-id="'+post.request.id+'"] button',e=>e.click());await owner.page.waitForSelector('[data-testid=leave-approval-snapshot]')
  check('W05/W11 詳情呈現提交時版本與人、非目前共用設定',(await owner.page.$eval('[data-testid=leave-approval-snapshot]',e=>e.innerText)).includes('隔離請假流程一'))
  await click(owner.page,'關閉');await owner.page.goto(stack.baseUrl+'/#/admin/leave/workflow');await waitText(owner.page,'只有管理員可以設定流程')
  check('W01 員工無設定入口且強制hash顯示拒絕',!(await owner.page.$('a[href="#/admin/leave/workflow"]')))
  let forbidden=false;try{await b.call('GET',WF)}catch(e){forbidden=e.code.startsWith('HTTP_403')}
  check('W01 MANAGER設定API仍403',forbidden)
  const events=await page.evaluate(()=>window.__wfPosts)
  check('W14 沒有自動提交重送；所有原鍵重試沿用正文',events.filter(p=>p.body.requestId===first.body.requestId).every(p=>same(p,first)))
  check('W18 全程無未捕捉例外',(await ui.tracker.unhandledRejections()).length===0 && ui.tracker.pageErrors.length===0 && (await owner.tracker.unhandledRejections()).length===0 && owner.tracker.pageErrors.length===0)
  check('W17 新庫V10、只本批資料，未執行展示初始化',Number(sql('select max(version::int) from flyway_schema_history where success;'))===10 && Number(sql("select count(*) from employee where username like 'demo_%' or username like 'stmax_%';"))===0)
} catch(e) {check('本輪完整執行',false,step+'／'+String(e.message).split('\n')[0]);if(page) await page.screenshot({path:path.join(OUT,'t05-2-workflow-failure.png'),fullPage:true}).catch(()=>{})}
finally {
  if(browser) await browser.close();if(stack) await stack.stop()
  if(owned) {
    if(readDatabase(database,'select current_database();')!==database) check('清理實際catalog不符，保留',false)
    else {const drop=spawnSync('docker',['exec','renewflow-db','sh','-c','exec dropdb -U "$POSTGRES_USER" "$1"','sh',database],{encoding:'utf8',windowsHide:true});check('本批專屬UI測試庫清除、隔離服務停止',drop.status===0)}
  }
  finish()
}
