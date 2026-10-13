// STMAX Demo v1：明確手動啟用的本機工具。所有業務寫入走既有HTTP API；SQL僅READ ONLY。
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
export const BATCH = 'STMAX-CUSTOMER-V1'
export const ACCOUNTS = [
  ['max','Max（展示）','ADMIN','MANAGEMENT',null],
  ['iris','Iris 林怡安（展示）','MANAGER','PROJECT','max'],
  ['leo','Leo 陳柏宇（展示）','EMPLOYEE','PROJECT','iris'],
  ['mia','Mia 林予晴（展示）','EMPLOYEE','PROJECT','iris'],
  ['ryan','Ryan 王子維（展示）','EMPLOYEE','PROJECT','iris'],
  ['zoe','Zoe 黃詩涵（展示）','EMPLOYEE','ADMINISTRATION','max'],
].map((r,i)=>({username:'demo_'+r[0],name:r[1],role:r[2],department:r[3],manager:r[4]?'demo_'+r[4]:null,employeeNo:'DEMO-STX-'+String(i+1).padStart(3,'0')}))
export const PROJECTS = [
  ['WEB','晨光咖啡品牌官網','虛構客戶：官網、產品頁與詢價流程'],
  ['ADS','青禾生活廣告代操','虛構客戶：廣告素材、成效追蹤與優化'],
  ['OPS','STMAX 內部流程優化','虛構內部專案：作業文件與流程改善'],
]
const ATTENDANCE = [['leo',-2,'09:00','12:00'],['leo',-2,'13:00','18:00'],['ryan',-2,'09:00','12:00'],['ryan',-2,'13:00','17:00'],
 ['mia',-1,'13:58','17:00'],['zoe',-2,'09:00','12:00'],['zoe',-2,'13:00','17:00'],['mia',-3,'14:00','17:00']]
const TIMESHEETS = [['leo',-2,'WEB',180,'官網首頁與詢價表單開發'],['leo',-2,'ADS',90,'轉換追蹤設定與驗證'],
 ['mia',-2,'WEB',120,'品牌視覺與產品頁設計'],['mia',-2,'OPS',60,'整理設計交付 SOP'],['ryan',-2,'ADS',240,'廣告素材、受眾與成效分析'],['zoe',-2,'OPS',90,'整理專案交付文件'],
 ['leo',-1,'WEB',240,'RWD 與表單錯誤處理'],['mia',-1,'WEB',180,'產品頁版型與素材調整'],['ryan',-1,'ADS',240,'活動素材與週報'],['zoe',-1,'OPS',120,'行政文件與交付檢查']]
export const LEAVES = [
 ['L01','leo',1,'09:00','12:00','PERSONAL','PENDING'],['L02','mia',2,'09:00','17:00','ANNUAL','REJECTED'],
 ['L03','ryan',3,'09:00','17:00','SICK','APPROVED'],['L04','leo',4,'09:00','17:00','PERSONAL','CHANGE_PENDING'],
 ['L05','mia',-1,'09:00','17:00','ANNUAL','PART_PENDING'],['L06','zoe',-2,'09:00','12:00','PERSONAL','ALL_PENDING'],
 ['L07','ryan',5,'09:00','17:00','PERSONAL','REVISE_PENDING'],['L08','leo',6,'09:00','12:00','ANNUAL','RESUBMIT_PENDING'],
 ['L09','mia',-3,'09:00','17:00','PERSONAL','PART_APPROVED'],['L10','zoe',-4,'09:00','12:00','SICK','ALL_APPROVED'],
]
export const marker = key => `【展示資料 STMAX Demo v1 ${key}】自動初始化：`
export function requestKey(key) {
 const b=crypto.createHash('sha256').update(BATCH+'|'+key).digest().subarray(0,16);b[6]=(b[6]&15)|80;b[8]=(b[8]&63)|128
 const h=b.toString('hex');return h.slice(0,8)+'-'+h.slice(8,12)+'-'+h.slice(12,16)+'-'+h.slice(16,20)+'-'+h.slice(20)
}
const quote = v => "'"+String(v).replaceAll("'","''")+"'"
const id = v => {if(!Number.isSafeInteger(v)||v<=0) throw new DemoError('INVALID_ID');return v}
export class DemoError extends Error {constructor(code,step=''){super('展示初始化停止：'+code+(step?'／'+step:''));this.code=code;this.step=step}}
export function readEnvironment(file=path.join(ROOT,'.env')) {
 const values={};if(fs.existsSync(file)) for(const line of fs.readFileSync(file,'utf8').replace(/^\uFEFF/,'').split(/\r?\n/)) {
  const m=/^([A-Z_][A-Z0-9_]*)=(.*)$/.exec(line);if(m) values[m[1]]=m[2].replace(/^(["'])(.*)\1$/,'$2')
 }
 return {...values,...process.env}
}
export function readDatabase(database,query) {
 if(!/^[a-z][a-z0-9_]*$/.test(database)) throw new DemoError('INVALID_CATALOG')
 const r=spawnSync('docker',['exec','-i','renewflow-db','sh','-c','exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"','sh',database],
  {input:'begin read only;\n'+query+'\ncommit;\n',encoding:'utf8',windowsHide:true})
 if(r.status!==0) throw new DemoError('READ_ONLY_DATABASE_FAILED')
 return r.stdout.trim()
}
export function guardLocalRuntime({baseUrl,profile,database,environment}) {
 const u=new URL(baseUrl)
 if(!['http:','https:'].includes(u.protocol)||!['localhost','127.0.0.1','[::1]'].includes(u.hostname)||u.username||u.password||u.pathname!=='/'||u.search||u.hash) throw new DemoError('NOT_LOCAL')
 if(!['dev','e2e'].includes(profile)) throw new DemoError('NOT_DEV')
 const devDb=environment.DB_NAME||'renewflow'
 if(profile==='dev' && database!==devDb) throw new DemoError('DEV_CATALOG_MISMATCH')
 if(profile==='e2e' && (!/^[a-z][a-z0-9_]*_test$/.test(database)||database===devDb||database!==environment.DB_TEST_NAME)) throw new DemoError('TEST_CATALOG_MISMATCH')
 const port=Number(u.port||(u.protocol==='https:'?443:80));if(!Number.isInteger(port)||port<1||port>65535) throw new DemoError('INVALID_PORT')
 if(process.platform!=='win32') throw new DemoError('LOCAL_RUNTIME_NOT_VERIFIED')
 const root=ROOT.replaceAll("'","''").replaceAll('/','\\')
 const ps=`$p=Get-NetTCPConnection -State Listen -LocalPort ${port} -ErrorAction Stop | Select-Object -First 1; $j=Get-CimInstance Win32_Process -Filter ('ProcessId='+$p.OwningProcess); $cmd=$j.CommandLine; $own=$false; $next=$j; for($i=0;$i -lt 5 -and $next;$i++){ if($next.CommandLine -like '*${root}*'){$own=$true}; $next=Get-CimInstance Win32_Process -Filter ('ProcessId='+$next.ParentProcessId) -ErrorAction SilentlyContinue }; [pscustomobject]@{project=$own;application=($cmd -like '*com.renewflow.eip.EipApplication*');profile=($cmd -match 'spring.profiles.active=${profile}(?:\\s|$)')} | ConvertTo-Json -Compress`
 const r=spawnSync('powershell.exe',['-NoProfile','-Command',ps],{encoding:'utf8',windowsHide:true})
 let state;try{state=JSON.parse(r.stdout)}catch{throw new DemoError('LOCAL_RUNTIME_NOT_VERIFIED')}
 if(r.status!==0||!state.project||!state.application||!state.profile) throw new DemoError('LOCAL_RUNTIME_NOT_VERIFIED')
 if(readDatabase(database,'select current_database();')!==database) throw new DemoError('ACTUAL_CATALOG_MISMATCH')
 if(readDatabase(database,"select max(version::int) from flyway_schema_history where success;")!=='9') throw new DemoError('SCHEMA_NOT_V9')
}
export class ApiClient {
 constructor(baseUrl){this.baseUrl=baseUrl;this.cookies=new Map()}
 async call(method,url,body) {
  const headers={Accept:'application/json',Cookie:[...this.cookies].map(([k,v])=>k+'='+v).join('; ')}
  if(body!==undefined) headers['Content-Type']='application/json'
  if(method!=='GET') headers['X-XSRF-TOKEN']=decodeURIComponent(this.cookies.get('XSRF-TOKEN')||'')
  let response;try{response=await fetch(this.baseUrl+url,{method,headers,body:body===undefined?undefined:JSON.stringify(body)})}catch{throw new DemoError('HTTP_UNKNOWN')}
  for(const c of response.headers.getSetCookie()) {const pair=c.split(';',1)[0],at=pair.indexOf('=');this.cookies.set(pair.slice(0,at),pair.slice(at+1))}
  let data;try{data=await response.json()}catch{throw new DemoError('BODY_UNKNOWN')}
  if(!response.ok) throw new DemoError('HTTP_'+response.status+'_'+(typeof data.code==='string'?data.code:'ERROR'))
  return data
 }
 async login(username,password){await this.call('GET','/api/auth/me').catch(e=>{if(!e.code.startsWith('HTTP_401')) throw e});return this.call('POST','/api/auth/login',{username,password})}
}
function save(file,state){const temp=file+'.tmp';fs.writeFileSync(temp,JSON.stringify(state,null,2));fs.renameSync(temp,file)}
function acquireLock(file) {
 try {const fd=fs.openSync(file,'wx');fs.writeFileSync(fd,JSON.stringify({pid:process.pid,batch:BATCH}));fs.closeSync(fd)}catch(e){
  if(e.code!=='EEXIST') throw e
  let owner;try{owner=JSON.parse(fs.readFileSync(file,'utf8'));if(owner.batch!==BATCH||!Number.isSafeInteger(owner.pid)||owner.pid<=0) throw new DemoError('STATE_LOCK_NOT_VERIFIED');process.kill(owner.pid,0)}catch(error){
   if(error.code==='ESRCH'){fs.unlinkSync(file);return acquireLock(file)}
   throw new DemoError('STATE_LOCK_NOT_VERIFIED')
  }
  throw new DemoError('BATCH_RUNNING')
 }
 return ()=>fs.unlinkSync(file)
}
export async function initializeDemo(options) {
 const {enabled=false,profile='dev',baseUrl='http://localhost:8080',database,environment=readEnvironment(),password,bootstrap,stateFile,afterResponse,onPlan}=options
 if(!enabled) return {outcome:'DISABLED',created:[],skipped:[]}
 if(!['dev','e2e'].includes(profile)) return {outcome:'DISABLED_PROFILE',created:[],skipped:[]}
 if(!password||!password.trim()) return {outcome:'NO_PASSWORD',created:[],skipped:[]}
 guardLocalRuntime({baseUrl,profile,database,environment})
 const file=path.resolve(stateFile),relative=path.relative(ROOT,file)
 if(!file.endsWith('.json')||(relative&&!relative.startsWith('..')&&!relative.startsWith(path.join('frontend','e2e','output')+path.sep))) throw new DemoError('STATE_MUST_BE_OUTSIDE_GIT')
 fs.mkdirSync(path.dirname(file),{recursive:true});const unlock=acquireLock(file+'.lock')
 const report={outcome:'DONE',created:[],skipped:[],cases:[]}
 try {
  const state=fs.existsSync(file)?JSON.parse(fs.readFileSync(file,'utf8')):{batch:BATCH,D:new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Taipei',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date()),catalog:database,steps:{},cases:{}}
  if(state.batch!==BATCH||state.catalog!==database||!/^\d{4}-\d{2}-\d{2}$/.test(state.D)) throw new DemoError('STATE_BATCH_MISMATCH')
  report.D=state.D
  const db=q=>readDatabase(database,q)
  const json=q=>JSON.parse(db(q)||'null')
  const admin=new ApiClient(baseUrl);const me=await admin.login(bootstrap.username,bootstrap.password)
  if(me.role!=='ADMIN'||Number(db('select id from employee where username='+quote(bootstrap.username)+';'))!==me.id) throw new DemoError('BOOTSTRAP_SCOPE_MISMATCH')
  const employeeRows=json("select coalesce(json_agg(json_build_object('id',id,'username',username,'employeeNo',employee_no)), '[]') from employee where username in ("+ACCOUNTS.map(a=>quote(a.username)).join(',')+") or employee_no in ("+ACCOUNTS.map(a=>quote(a.employeeNo)).join(',')+");")
  const projects=(await admin.call('GET','/api/admin/projects')).projects
  const collisions=[]
  for(const a of ACCOUNTS) for(const r of employeeRows.filter(r=>r.username===a.username||r.employeeNo===a.employeeNo)) {
   const planned=state.steps['ACCOUNT:'+a.username]
   if(r.username!==a.username||r.employeeNo!==a.employeeNo||!planned||(planned.id&&planned.id!==r.id)||
     db("select count(*) from organization_history where employee_id="+id(r.id)+" and reason="+quote(marker('ACCOUNT:'+a.username))+';')==='0') collisions.push('ACCOUNT:'+a.username)
  }
  for(const [code] of PROJECTS) {const r=projects.find(p=>p.code==='DEMO-STMAX-'+code);if(r){const planned=state.steps['PROJECT:'+code];if(!planned||(planned.id&&planned.id!==r.id)||(!planned.id&&!r.description?.startsWith(marker('PROJECT:'+code)))) collisions.push('PROJECT:'+code)}}
  if(collisions.length) {const error=new DemoError('COLLISION');error.collisions=[...new Set(collisions)];throw error}
  const departments=(await admin.call('GET','/api/admin/departments')).departments,departmentIds={}
  for(const [code,name] of [['MANAGEMENT','經營管理'],['PROJECT','專案服務'],['ADMINISTRATION','行政營運']]) {
   const r=departments.find(d=>d.code===code),fallback=departments.find(d=>d.code==='DEMO-STMAX-'+code)
   if(r&&(!r.active||r.name!==name)) throw new DemoError('DEPARTMENT_REFERENCE_INVALID',code)
   if(!r&&fallback&&(!state.steps['DEPARTMENT:'+code]||fallback.name!==name+'（展示資料）')) throw new DemoError('DEPARTMENT_COLLISION',code)
  }
  // 所有帳號／員編／專案碰撞均檢查通過後，才接受本批、保存D並開始寫入。
  if(onPlan) onPlan({batch:BATCH,D:state.D,accounts:ACCOUNTS.map(a=>({username:a.username,employeeNo:a.employeeNo,role:a.role})),projects:PROJECTS.map(p=>'DEMO-STMAX-'+p[0]),timesheets:10,manualAttendance:8,leaveDocuments:10})
  save(file,state)
  const day=offset=>new Date(Date.parse(state.D+'T00:00:00Z')+offset*86400000).toISOString().slice(0,10)
  async function step(key,intent,find,create) {
   let plan=state.steps[key]
   if(plan?.status==='DONE'){report.skipped.push({step:key,reason:'已完成，保持目前資料'});return plan.id}
   if(!plan) {plan={status:'PLANNED',intent,startedAt:new Date().toISOString()};state.steps[key]=plan;save(file,state)}
   let found=await find(plan.intent)
   if(!found){const result=await create(plan.intent);if(afterResponse) await afterResponse(key,result);found=id(result.id??result.request?.id);report.created.push(key)}
   else report.skipped.push({step:key,reason:'確認先前已提交，沒有重複寫入'})
   plan.id=id(found);plan.status='DONE';save(file,state);return plan.id
  }

  for(const [code,name] of [['MANAGEMENT','經營管理'],['PROJECT','專案服務'],['ADMINISTRATION','行政營運']]) {
   const existing=departments.find(d=>d.code===code)
   if(existing){if(!existing.active||existing.name!==name) throw new DemoError('DEPARTMENT_REFERENCE_INVALID',code);departmentIds[code]=existing.id}
   else {
    const key='DEPARTMENT:'+code,plannedCode='DEMO-STMAX-'+code,collision=departments.find(d=>d.code===plannedCode)
    if(collision&&!state.steps[key]) throw new DemoError('DEPARTMENT_COLLISION',code)
    departmentIds[code]=await step(key,{code:plannedCode,name:name+'（展示資料）'},async()=>departments.find(d=>d.code===plannedCode)?.id,
     body=>admin.call('POST','/api/admin/departments',body))
   }
  }
  const accounts={},clients=new Map(),changedAccounts=new Set()
  for(const a of ACCOUNTS) {
   const key='ACCOUNT:'+a.username
   const employeeId=await step(key,{employeeNo:a.employeeNo,username:a.username,displayName:a.name,role:a.role,departmentId:departmentIds[a.department],managerId:a.manager?accounts[a.manager].id:null,reason:marker(key)},
    async()=>employeeRows.find(r=>r.username===a.username)?.id,body=>admin.call('POST','/api/admin/employees',{...body,initialPassword:password}))
   const current=await admin.call('GET','/api/admin/employees/'+employeeId);accounts[a.username]=current
   if(current.role!==a.role||!current.enabled||current.department?.id!==departmentIds[a.department]||(current.manager?.id??null)!==(a.manager?accounts[a.manager].id:null)) changedAccounts.add(a.username)
  }
  async function client(username){if(!clients.has(username)){const c=new ApiClient(baseUrl);await c.login(username,password);clients.set(username,c)}return clients.get(username)}
  const projectIds={}
  for(const [code,name,description] of PROJECTS) projectIds[code]=await step('PROJECT:'+code,{code:'DEMO-STMAX-'+code,name:'【展示資料】'+name,description:marker('PROJECT:'+code)+description},
   async()=>projects.find(p=>p.code==='DEMO-STMAX-'+code)?.id,async body=>(await client('demo_max')).call('POST','/api/admin/projects',body))
  for(let i=0;i<ATTENDANCE.length;i++) {
   const [person,offset,start,end]=ATTENDANCE[i],key='ATT'+String(i+1).padStart(2,'0'),employeeId=accounts['demo_'+person].id
   await step(key,{employeeId,workDate:day(offset),clockInTime:start,clockOutTime:end,reason:marker(key)+'虛構歷史補登，非真實打卡'},
    async()=>Number(db('select attendance_id from attendance_history where employee_id='+id(employeeId)+' and reason='+quote(marker(key)+'虛構歷史補登，非真實打卡')+' order by id limit 1;'))||null,
    async body=>(await client('demo_max')).call('POST','/api/admin/attendance/records/correct',body))
  }
  for(let i=0;i<TIMESHEETS.length;i++) {
   const [person,offset,project,minutes,description]=TIMESHEETS[i],key='TS'+String(i+1).padStart(2,'0'),username='demo_'+person,employeeId=accounts[username].id
   if(changedAccounts.has(username)&&!state.steps[key]){report.skipped.push({step:key,reason:'展示員工已被調整，沒有恢復或新增此步驟'});continue}
   await step(key,{requestId:requestKey(key),workDate:day(offset),projectId:projectIds[project],minutes,description:marker(key)+description},
    async()=>Number(db('select id from timesheet_entry where employee_id='+id(employeeId)+' and request_id='+quote(requestKey(key))+'::uuid;'))||null,
    async body=>(await client(username)).call('POST','/api/timesheets/me',body))
  }
  for(const [label,person,offset,start,end,type,target] of LEAVES) {
   const username='demo_'+person,employeeId=accounts[username].id,reviewer=person==='zoe'?'demo_max':'demo_iris',existing=state.cases[label]
   const keys=[label+':CREATE',label+':REVISION']
   if(existing?.done){
    const current=await admin.call('GET','/api/admin/leave/requests/'+existing.documentId)
    const hash=crypto.createHash('sha256').update(JSON.stringify({document:current.document,history:current.history})).digest('hex')
    report.skipped.push({step:label,reason:hash===existing.hash?'業務案例已完成，保持目前狀態':'展示案例已被人工／後續操作修改，沒有恢復初始狀態'})
    report.cases.push({label,documentId:existing.documentId});continue
   }
   if(changedAccounts.has(username)||changedAccounts.has(reviewer)){report.skipped.push({step:label,reason:'員工／審核人已調整，沒有還原或建立流程'});continue}
   const findRequest=key=>Number(db('select id from leave_request where employee_id='+id(employeeId)+' and request_id='+quote(requestKey(key))+'::uuid;'))||null
   const root=await step(label+':CREATE',{requestId:requestKey(label+':CREATE'),leaveType:type,workDate:day(offset),startTime:start,endTime:end,reason:marker(label)+'虛構請假展示，請勿當作真人出勤'},
    async()=>findRequest(label+':CREATE'),async body=>(await client(username)).call('POST','/api/leave/me',body))
   state.cases[label]??={documentId:root};save(file,state)
   const full=await admin.call('GET','/api/admin/leave/requests/'+root)
   if(full.document.versions.some(r=>!keys.map(requestKey).includes(r.requestId))){report.skipped.push({step:label,reason:'有非初始化後續提交，保持人工資料'});continue}
   async function decision(requestId,approve,decisionKey) {
    const key=label+':'+decisionKey,desired=approve?'APPROVED':'REJECTED',current=await admin.call('GET','/api/admin/leave/requests/'+requestId)
    if(current.request.approver.id!==accounts[reviewer].id) throw new DemoError('MANUAL_CASE_CHANGED',label)
    if(current.request.status!==desired&&current.request.status!=='PENDING') throw new DemoError('MANUAL_CASE_CHANGED',label)
    if(current.request.status===desired&&!state.steps[key]) throw new DemoError('MANUAL_CASE_CHANGED',label)
    const reviewerClient=await client(reviewer),review=await reviewerClient.call('GET','/api/leave/approvals/'+requestId)
    await step(key,{expectedVersion:review.request.version,evidenceToken:review.evidence.token,reason:marker(label)+'請補充工作交接安排'},
     async()=>{const d=await admin.call('GET','/api/admin/leave/requests/'+requestId);return d.request.status===desired?requestId:null},
     body=>reviewerClient.call('POST','/api/leave/approvals/'+requestId+(approve?'/approve':'/reject'),body))
   }
   try {
    if(target==='REJECTED'||target==='RESUBMIT_PENDING') await decision(root,false,'REJECT_ROOT')
    if(['APPROVED','CHANGE_PENDING','PART_PENDING','ALL_PENDING','PART_APPROVED','ALL_APPROVED'].includes(target)) await decision(root,true,'APPROVE_ROOT')
    if(['CHANGE_PENDING','PART_PENDING','ALL_PENDING','REVISE_PENDING','RESUBMIT_PENDING','PART_APPROVED','ALL_APPROVED'].includes(target)) {
     const source=await (await client(username)).call('GET','/api/leave/me/'+root)
     const op=target.startsWith('CHANGE')?'CHANGE':target.startsWith('PART')?'CANCEL_PART':target.startsWith('ALL')?'CANCEL_ALL':'REVISE'
     const proposedStart=target==='REVISE_PENDING'||target==='RESUBMIT_PENDING'?'10:00':start
     const proposedEnd=target==='REVISE_PENDING'?'16:00':(op==='CHANGE'||op==='CANCEL_PART')?'14:00':end
     const reason=target==='PART_PENDING'?'提早回來處理官網需求':op==='CANCEL_ALL'?'當日改為正常工作':'展示修改／重送，原內容保留'
     const next=await step(label+':REVISION',{requestId:requestKey(label+':REVISION'),operation:op,expectedSourceVersion:source.request.version,
      expectedEffectiveRequestId:source.document.effective?.id??null,expectedEffectiveVersion:source.document.effectiveVersion,
      leaveType:type,workDate:day(offset),startTime:proposedStart,endTime:proposedEnd,reason:marker(label)+reason},
      async()=>findRequest(label+':REVISION'),body=>(async()=>{const result=await (await client(username)).call('POST','/api/leave/me/'+root+'/revisions',body);return {id:result.request.id}})())
     if(target==='PART_APPROVED'||target==='ALL_APPROVED') await decision(next,true,'APPROVE_REVISION')
    }
   }catch(e){if(e.code==='MANUAL_CASE_CHANGED'){report.skipped.push({step:label,reason:'人工狀態與待建立步驟不同，沒有還原'});continue}throw e}
   const final=await admin.call('GET','/api/admin/leave/requests/'+root)
   state.cases[label]={documentId:root,done:true,hash:crypto.createHash('sha256').update(JSON.stringify({document:final.document,history:final.history})).digest('hex')};save(file,state)
   report.cases.push({label,documentId:root,versions:final.document.versions.map(r=>({id:r.id,revision:r.revision.number,status:r.status,sourceId:r.revision.sourceRequestId})),effectiveId:final.document.effective?.id??null,pendingId:final.document.pending?.id??null})
  }
  return report
 } finally {unlock()}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
 const args=process.argv.slice(2),value=k=>{const i=args.indexOf(k);return i<0?undefined:args[i+1]}
 const env=readEnvironment(),profile=value('--profile')||'dev',database=value('--database')||(profile==='dev'?(env.DB_NAME||'renewflow'):env.DB_TEST_NAME)
 try {
  if(args.includes('--enable')&&profile==='dev') {
   const evidence=value('--backup-evidence');let backup;try{backup=JSON.parse(fs.readFileSync(evidence,'utf8'))}catch{throw new DemoError('BACKUP_REQUIRED')}
   if(backup.developmentCatalog!==database||!backup.restoreMatches||!fs.existsSync(backup.backup)) throw new DemoError('BACKUP_NOT_VERIFIED')
  }
  const result=await initializeDemo({enabled:args.includes('--enable'),profile,database,environment:env,baseUrl:value('--api')||'http://localhost:8080',
   onPlan:plan=>console.log(JSON.stringify({preflight:'PASS',...plan})),
   password:env.TEAMPORTAL_DEMO_PASSWORD,bootstrap:{username:'developer',password:env.RENEWFLOW_DEV_DEVELOPER_PASSWORD},
   stateFile:value('--state')||path.join(env.USERPROFILE,'.codex','local-state','java-eip','stmax-customer-v1.json')})
  console.log(JSON.stringify(result,null,2))
 }catch(e){console.error(JSON.stringify({outcome:'STOPPED',code:e instanceof DemoError?e.code:'UNEXPECTED',step:e instanceof DemoError?e.step:undefined,collisions:e.collisions}));process.exitCode=1}
}
