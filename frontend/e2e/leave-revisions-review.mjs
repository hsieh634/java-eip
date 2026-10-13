// R1/R2＋展示初始化：真隔離API、真表單、完整DB副作用對照。
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { initializeDemo, ApiClient, readDatabase, DemoError } from '../../tools/seed-stmax-demo.mjs'
import { startIsolatedStack } from './isolated-stack.mjs'
import { createRunner, launchBrowser, loginAs, OUT } from './helpers.mjs'
import { newPage, click } from './ui.mjs'
const {check,finish}=createRunner()
// 每次新建自己的展示測試庫，不清空前輪展示，也不依賴共用renewflow_test。
const run=Date.now().toString(36)+'_'+crypto.randomBytes(3).toString('hex')
const database='renewflow_t051r1_demo_'+run+'_test'
const stateFile=path.join(OUT,'t05-1-r1-demo-state-'+run+'.json')
const password=crypto.randomBytes(24).toString('base64url')
let ownedDatabase=false
function provisionDatabase() {
 if(readDatabase('renewflow',"select count(*) from pg_database where datname='"+database+"';")!=='0') throw new Error('測試庫名稱碰撞，不接管')
 const r=spawnSync('docker',['exec','renewflow-db','sh','-c','exec createdb -U "$POSTGRES_USER" "$1"','sh',database],{encoding:'utf8',windowsHide:true})
 if(r.status!==0) throw new Error('本輪測試庫建立失敗，不輸出stderr')
 ownedDatabase=true;process.env.DB_TEST_NAME=database
}
const tables=['employee','department','organization_history','attendance','attendance_request','attendance_history','geofence_setting','geofence_setting_history','attendance_clock_event','project','timesheet_entry','leave_request','leave_history','leave_approver_setting','attendance_clock_submission','leave_document']
const json=q=>JSON.parse(readDatabase(database,q))
const snapshot=()=>json('select json_build_object('+tables.map(t=>`'${t}',(select json_build_object('count',count(*),'hash',md5(coalesce(string_agg(row_to_json(x)::text,E'\n' order by row_to_json(x)::text),''))) from ${t} x)`).join(',')+');')
function fixtureSql(q){
 if(readDatabase(database,'select current_database();')!==database) throw new Error('actual catalog不符')
 const r=spawnSync('docker',['exec','-i','renewflow-db','sh','-c','exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"','sh',database],{input:q,encoding:'utf8',windowsHide:true})
 if(r.status!==0) throw new Error('本輪碰撞fixture操作失敗，不輸出stderr')
 return r.stdout.trim()
}
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b)
let stack,browser,phase='初始化',options
try {
 provisionDatabase();stack=await startIsolatedStack();browser=await launchBrowser()
 const baseUrl='http://localhost:'+process.env.E2E_API_PORT
 options={enabled:true,profile:'e2e',database,baseUrl,environment:{...process.env,DB_NAME:'renewflow'},password,bootstrap:stack.users.admin,stateFile}
 const admin=new ApiClient(baseUrl);await admin.login(stack.users.admin.username,stack.users.admin.password)
 let before=snapshot()
 check('D01 未啟用不建立', (await initializeDemo({...options,enabled:false})).outcome==='DISABLED'&&same(before,snapshot()))
 check('D01 非dev/e2e不建立', (await initializeDemo({...options,profile:'production'})).outcome==='DISABLED_PROFILE'&&same(before,snapshot()))
 check('D01 缺展示密碼不建立', (await initializeDemo({...options,password:''})).outcome==='NO_PASSWORD'&&same(before,snapshot()))
 // 只在本輪新_test庫建立無歷程的碰撞fixture，精確ID清理；展示工具自身仍只有READ ONLY SQL。
 for(const kind of ['username','number','project']) {
  phase='碰撞 '+kind
  let foreign
  if(kind==='project') foreign=Number(fixtureSql("insert into project(code,name,description,created_at,updated_at) values ('DEMO-STMAX-WEB','非本批專案','碰撞fixture',now(),now()) returning id;"))
  else foreign=Number(fixtureSql("insert into employee(employee_no,username,display_name,password_hash,role) select "+(kind==='username'?"'FOREIGN-R1-NO','demo_leo'":"'DEMO-STX-003','r1_foreign'")+",'碰撞fixture',password_hash,'EMPLOYEE' from employee where username='"+stack.users.admin.username+"' returning id;"))
  before=snapshot();let collision=false
  try{await initializeDemo(options)}catch(e){collision=e instanceof DemoError&&e.code==='COLLISION'&&e.collisions.some(c=>c.startsWith(kind==='project'?'PROJECT:':'ACCOUNT:'))}
  check('D04 '+kind+'碰撞整批零寫入',collision&&same(before,snapshot()))
  fixtureSql('delete from '+(kind==='project'?'project':'employee')+' where id='+foreign+';')
 }
 const oldIds={}
 for(const t of tables) if(!['leave_approver_setting','attendance_clock_submission'].includes(t)) oldIds[t]=json(`select coalesce(json_agg(id order by id),'[]') from ${t};`)
 const original=()=>json('select json_build_object('+tables.map(t=>{
  const where=oldIds[t]?` where id=any(array[${oldIds[t].join(',')}]::bigint[])`:''
  return `'${t}',(select md5(coalesce(string_agg(row_to_json(x)::text,E'\n' order by row_to_json(x)::text),'')) from (select * from ${t}${where}) x)`
 }).join(',')+');')
 const originalBefore=original()
 phase='注入中斷'
 let interrupted=false
 try{await initializeDemo({...options,afterResponse:async key=>{if(key==='ACCOUNT:demo_mia') throw new DemoError('CONTROLLED_AFTER_COMMIT')}})}catch(e){interrupted=e.code==='CONTROLLED_AFTER_COMMIT'}
 check('D03 真帳號201後中斷且步驟意圖保留',interrupted&&JSON.parse(fs.readFileSync(stateFile,'utf8')).steps['ACCOUNT:demo_mia'].status==='PLANNED')
 phase='續跑建立'
 const first=await initializeDemo(options)
 check('D02 首次續跑全部完成',first.outcome==='DONE'&&first.cases.length===10)
 check('D03 中斷已建立帳號不重複',json("select count(*) from employee where username='demo_mia';")===1)
 check('D02 原有16表舊列保持一致',same(originalBefore,original()))
 const stats=json("select json_build_object('employees',(select count(*) from employee where username like 'demo_%'),'projects',(select count(*) from project where code like 'DEMO-STMAX-%'),'timesheets',(select count(*) from timesheet_entry where employee_id in (select id from employee where username like 'demo_%')),'minutes',(select sum(minutes) from timesheet_entry where employee_id in (select id from employee where username like 'demo_%')),'attendance',(select count(*) from attendance where employee_id in (select id from employee where username like 'demo_%')),'manual',(select count(*) from attendance where source='MANUAL' and employee_id in (select id from employee where username like 'demo_%')),'events',(select count(*) from attendance_clock_event where employee_id in (select id from employee where username like 'demo_%')),'documents',(select count(*) from leave_document where employee_id in (select id from employee where username like 'demo_%')),'requests',(select count(*) from leave_request where employee_id in (select id from employee where username like 'demo_%')));")
 check('D02 六帳號／三專案／10工時1560分／10業務單17提交',stats.employees===6&&stats.projects===3&&stats.timesheets===10&&stats.minutes===1560&&stats.documents===10&&stats.requests===17)
 check('D02 八組MANUAL且沒有偽造CLOCK事件',stats.attendance===8&&stats.manual===8&&stats.events===0)
 const cases=new Map(),clients={}
 for(const who of ['max','iris','leo','mia','ryan','zoe']) {const c=new ApiClient(baseUrl);await c.login('demo_'+who,password);clients[who]=c}
 const expected={L01:['PENDING',null],L02:['REJECTED',null],L03:['APPROVED','17:00'],L04:['PENDING','17:00'],L05:['PENDING','17:00'],L06:['PENDING','12:00'],L07:['PENDING',null],L08:['PENDING',null],L09:['APPROVED','14:00'],L10:['APPROVED',null]}
 for(const item of first.cases){const d=await admin.call('GET','/api/admin/leave/requests/'+item.documentId);cases.set(item.label,d);const latest=d.document.versions.at(-1)
  check('D02 DEMO-'+item.label+'提交／有效／歷程',latest.status===expected[item.label][0]&&(d.document.effective?.endTime??null)===expected[item.label][1]&&d.history.every(h=>h.leaveRequestId&&h.actor&&(!h.decision||h.decision.attendance)))
 }
 check('D02 L07舊版、L08退回理由、L10整段銷假保留',cases.get('L07').document.origin.status==='WITHDRAWN'&&cases.get('L08').document.origin.status==='REJECTED'&&cases.get('L08').document.origin.decisionReason.includes('請補充工作交接安排')&&cases.get('L10').document.canceled&&cases.get('L10').document.originalApproved!==null)
 phase='同批重跑'
 before=snapshot();const again=await initializeDemo(options)
 check('D03 同批重跑零新增／零更新／D不變',again.created.length===0&&again.D===first.D&&same(before,snapshot()))
 async function employee(username){return (await admin.call('GET','/api/admin/employees?q='+username+'&size=50')).items.find(e=>e.username===username)}
 async function manager(username,newManager){const e=await employee(username);await admin.call('PUT','/api/admin/employees/'+e.id,{displayName:e.displayName,role:e.role,enabled:e.enabled,departmentId:e.department?.id??null,managerId:newManager,expectedVersion:e.version,reason:'R1受控：展示員工主管調整'})}
 async function revise(who,root,operation,date,start,end,reason){const d=await clients[who].call('GET','/api/leave/me/'+root);return clients[who].call('POST','/api/leave/me/'+root+'/revisions',{requestId:crypto.randomUUID(),operation,expectedSourceVersion:d.request.version,expectedEffectiveRequestId:d.document.effective?.id??null,expectedEffectiveVersion:d.document.effectiveVersion,leaveType:d.request.leaveType,workDate:date,startTime:start,endTime:end,reason})}
 phase='R1歷史入口'
 const bId=(await admin.call('GET','/api/admin/attendance/employees')).find(e=>e.employeeNo==='EB-'+stack.runId).id
 await manager('demo_leo',bId)
 const root=cases.get('L01').document.id,newDay=new Date(Date.parse(first.D+'T00:00Z')+7*86400000).toISOString().slice(0,10)
 const revision=await revise('leo',root,'REVISE',newDay,'09:00','12:00','R1_SECRET_BROWSER_FUTURE')
 before=snapshot();const old=await clients.iris.call('GET','/api/leave/approvals/'+root)
 let denied=false;try{await clients.iris.call('GET','/api/leave/approvals/'+revision.request.id)}catch(e){denied=e.code.startsWith('HTTP_404')}
 check('R1 舊入口完整JSON沒有後續內容／日期／pending',old.document.versions.length===1&&old.document.pending===null&&!JSON.stringify(old).includes('R1_SECRET_BROWSER_FUTURE')&&!JSON.stringify(old).includes(newDay)&&old.document.viewMode==='REVIEW_HISTORY'&&denied)
 check('R1 歷史GET與被拒讀取零寫入',same(before,snapshot()))
 const iris=await newPage(await browser.createBrowserContext());await loginAs(iris.page,'demo_iris',password,stack.baseUrl)
 await iris.page.goto(stack.baseUrl+'/#/leave/approvals',{waitUntil:'networkidle0'});await iris.page.select('#approvals-status','ALL');await click(iris.page,'查詢')
 await iris.page.waitForSelector('[data-leave-id="'+root+'"] button');await iris.page.$eval('[data-leave-id="'+root+'"] button',e=>e.click());await iris.page.waitForSelector('[data-testid=leave-view-scope]')
 check('R1 真畫面明示受限歷史、未提供全局現在狀態',await iris.page.$eval('[data-testid=leave-view-scope]',e=>e.textContent.includes('不是業務單全局目前狀態')))
 phase='R2各類撤回'
 const general=await clients.ryan.call('POST','/api/leave/me',{requestId:crypto.randomUUID(),leaveType:'PERSONAL',workDate:new Date(Date.parse(first.D+'T00:00Z')+9*86400000).toISOString().slice(0,10),startTime:'09:00',endTime:'12:00',reason:'R2一般待審確認'})
 const items=[['ryan','GENERAL','新增請假'],['leo','L04','核准後變更'],['mia','L05','部分銷假'],['zoe','L06','整段銷假'],['leo',null,'修改／退回重送']]
 for(const [who,label,type] of items) {
  const source=label==='GENERAL'?general.request.id:label?cases.get(label).document.pending.id:revision.request.id,prior=await clients[who].call('GET','/api/leave/me/'+source)
  const ui=await newPage(await browser.createBrowserContext());await loginAs(ui.page,'demo_'+who,password,stack.baseUrl);await ui.page.goto(stack.baseUrl+'/#/leave',{waitUntil:'networkidle0'})
  await ui.page.waitForSelector('[data-leave-id="'+source+'"] button');await ui.page.$eval('[data-leave-id="'+source+'"] button',e=>e.click());await ui.page.waitForSelector('[data-testid=leave-document]');await click(ui.page,'撤回這筆申請');await ui.page.waitForSelector('[data-testid=withdraw-confirm]')
  const text=await ui.page.$eval('[data-testid=withdraw-confirm]',e=>e.textContent)
  check('R2 '+type+'確認列ID／操作／結案與有效保留',text.includes('#'+source)&&text.includes(type)&&text.includes('本次待審申請將結案')&&
    (prior.document.effective?text.includes('原有效請假仍保留')&&text.includes(prior.document.effective.endTime)&&text.includes('#'+prior.document.effective.id):text.includes('待審預留'))&&!text.includes('撤回後時段即釋放'))
  await click(ui.page,'確認撤回');await ui.page.waitForFunction(()=>document.querySelector('[data-testid=leave-summary]')?.dataset.status==='WITHDRAWN')
  const after=await clients[who].call('GET','/api/leave/me/'+source)
  check('R2 '+type+'實際撤回保留有效ID／版本／時段',after.request.status==='WITHDRAWN'&&after.document.effectiveVersion===prior.document.effectiveVersion&&same(after.document.effective,prior.document.effective))
 }
 phase='ADMIN審核換日期'
 await manager('demo_zoe',(await employee('demo_iris')).id)
 const zoeRoot=cases.get('L06').document.origin.id,pastDate=new Date(Date.parse(first.D+'T00:00Z')-6*86400000).toISOString().slice(0,10)
 await clients.max.call('POST','/api/admin/attendance/records/correct',{employeeId:(await employee('demo_zoe')).id,workDate:pastDate,clockInTime:'09:00',clockOutTime:'11:00',reason:'R1受控：展示新日期MANUAL資料'})
 const changed=await revise('zoe',zoeRoot,'CHANGE',pastDate,'09:00','12:00','R1_SECRET_ADMIN_DATE')
 const review=await clients.iris.call('GET','/api/leave/approvals/'+changed.request.id)
 check('R1 新合法審核人仍取得必要前序與新日出勤',review.document.versions.length===3&&review.evidence.dates.includes(pastDate)&&review.evidence.records.some(r=>r.workDate===pastDate))
 await clients.iris.call('POST','/api/leave/approvals/'+changed.request.id+'/approve',{expectedVersion:review.request.version,evidenceToken:review.evidence.token})
 before=snapshot();const restricted=await clients.max.call('GET','/api/leave/approvals/'+zoeRoot),full=await clients.max.call('GET','/api/admin/leave/requests/'+zoeRoot)
 check('R1 ADMIN審核不洩漏後續snapshot／日期／有效狀態',!JSON.stringify(restricted).includes(pastDate)&&!JSON.stringify(restricted).includes('R1_SECRET_ADMIN_DATE')&&restricted.document.effective.id===zoeRoot&&restricted.document.effectiveVersion===1&&restricted.history.every(h=>h.leaveRequestId===zoeRoot))
 check('R1 ADMIN管理入口完整、讀取零業務寫入',full.document.effective.id===changed.request.id&&JSON.stringify(full).includes(pastDate)&&same(before,snapshot()))
 const mia=await employee('demo_mia');await admin.call('PUT','/api/admin/employees/'+mia.id,{displayName:mia.displayName+'人工調整',role:'MANAGER',enabled:true,departmentId:mia.department.id,managerId:mia.manager.id,expectedVersion:mia.version,reason:'展示人工調整保護'})
 const project=(await clients.max.call('GET','/api/admin/projects')).projects.find(p=>p.code==='DEMO-STMAX-WEB')
 await clients.max.call('PUT','/api/admin/projects/'+project.id,{name:'【展示資料】客戶人工改名',description:'使用者自行調整說明',active:project.active,expectedVersion:project.version})
 phase='人工狀態後重跑'
 before=snapshot();const manual=await initializeDemo(options)
 check('D05 人工修改角色／主管／假單／專案說明後不恢復、零寫入',manual.created.length===0&&same(before,snapshot())&&manual.skipped.some(x=>x.reason.includes('人工')))
 check('D05 原有16表舊列仍相同',same(originalBefore,original()))
 const finalState=JSON.parse(fs.readFileSync(stateFile,'utf8'))
 check('D01 state與結果不含展示／bootstrap密碼',!JSON.stringify(finalState).includes(password)&&!JSON.stringify(finalState).includes(stack.users.admin.password)&&!JSON.stringify(manual).includes(password))
}catch(e){check('本輪腳本完整執行',false,phase+'／'+(e instanceof DemoError?e.code:String(e.message).split('\n')[0]))}
finally{
 if(browser) await browser.close();if(stack) await stack.stop()
 if(ownedDatabase){
  if(readDatabase(database,'select current_database();')!==database) check('清理catalog不符，保留資料庫',false)
  else {
   const r=spawnSync('docker',['exec','renewflow-db','sh','-c','exec dropdb -U "$POSTGRES_USER" "$1"','sh',database],{encoding:'utf8',windowsHide:true})
   check('隔離服務停止、只清理本次新建Demo測試庫',r.status===0)
  }
 }
 finish()
}
