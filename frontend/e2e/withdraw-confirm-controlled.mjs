// R2：執行Git基準與目前真實WithdrawAction；只替換React hooks／JSX容器，不寫入任何資料。
import fs from 'node:fs'
import { execFileSync } from 'node:child_process'
import vm from 'node:vm'
import ts from 'typescript'
const current=fs.readFileSync('src/pages/LeavePage.tsx','utf8')
const previous=execFileSync('git',['show','47f7fdfec33513bde50396e2864575f4c3f6e054:frontend/src/pages/LeavePage.tsx'],{encoding:'utf8'})
const labels={CREATE:'新增請假',REVISE:'修改／退回重送',CHANGE:'核准後變更',CANCEL_ALL:'整段銷假',CANCEL_PART:'部分銷假'}
const describe=r=>`事假 ${r.workDate} ${r.startTime}～${r.endTime}`
const text=v=>v==null?'':typeof v==='string'||typeof v==='number'?String(v):Array.isArray(v)?v.map(text).join(''):text(v.props?.children)
function render(source,detail){
 let n=0;const jsx=(type,props)=>({type,props}),context={exports:{},require:()=>({jsx,jsxs:jsx}),useState:value=>[n++===0?true:value,()=>{}],useApiErrorMessage:()=>()=>'',useLifetime:()=>({current:{alive:true}}),REVISION_LABEL:labels,describe}
 const start=source.lastIndexOf('function WithdrawAction(');if(start<0) throw new Error('找不到真實撤回元件')
 const js=ts.transpileModule('export '+source.slice(start),{compilerOptions:{module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX,target:ts.ScriptTarget.ES2022}}).outputText
 vm.runInNewContext(js,context);return text(context.exports.WithdrawAction({detail,onDone(){},onReload(){}}))
}
let before=0,after=0
for(const operation of Object.keys(labels)){
 const effective=['CHANGE','CANCEL_ALL','CANCEL_PART'].includes(operation)?{id:100,leaveType:'PERSONAL',workDate:'2026-10-15',startTime:'09:00',endTime:'17:00'}:null
 const detail={request:{id:101,leaveType:'PERSONAL',workDate:'2026-10-15',startTime:'09:00',endTime:'14:00',version:0,revision:{operation}},document:{effective,effectiveVersion:1}}
 const valid=s=>s.includes('#101')&&s.includes(labels[operation])&&s.includes('2026-10-15')&&s.includes('09:00～14:00')&&s.includes('本次待審申請將結案')&&
  (effective?s.includes('原有效請假仍保留')&&s.includes('#100')&&s.includes('09:00～17:00')&&!s.includes('撤回後時段即釋放'):s.includes('待審預留'))
 const b=valid(render(previous,detail)),a=valid(render(current,detail));before+=Number(b);after+=Number(a)
 console.log(JSON.stringify({operation,baselinePass:b,currentPass:a}))
}
console.log(`R2 baseline ${before}/5; current ${after}/5`)
process.exitCode=before===0&&after===5?0:1
