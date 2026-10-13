import type { AttendanceEvidence, LeaveDetail, LeaveRequest } from '../api/leave'
import { RECORD_STATUS_LABEL } from '../attendance/labels'
import { formatDateTime, formatTime } from '../lib/time'
import { describe, LEAVE_STATUS_LABEL, REVISION_LABEL } from './labels'

function removed(base: LeaveRequest, next: LeaveRequest): string {
  if (next.revision?.operation === 'CANCEL_ALL') return base.workDate + ' ' + base.startTime + '～' + base.endTime + '（整段）'
  if (base.workDate !== next.workDate || base.leaveType !== next.leaveType)
    return describe(base) + ' → ' + describe(next) + '（原時段在核准前仍有效）'
  const parts: string[] = []
  if (next.startTime > base.startTime) parts.push(base.startTime + '～' + (next.startTime < base.endTime ? next.startTime : base.endTime))
  if (next.endTime < base.endTime) parts.push((next.endTime > base.startTime ? next.endTime : base.startTime) + '～' + base.endTime)
  return parts.length ? base.workDate + ' ' + parts.join('、') : '沒有移除原時段；核准後依本次內容調整'
}
export function LeaveDocumentSummary({ detail }: Readonly<{ detail: LeaveDetail }>) {
  const d = detail.document, r = detail.request
  if (!d) return null
  const restricted = d.viewMode === 'REVIEW_HISTORY'
  const base = d.versions.find(v => v.id === r.revision?.baseEffectiveRequestId)
  return <section className="banner banner--info" data-testid="leave-document">
    <h3>業務假單 #{d.id}／本次內容版本 {r.revision?.number}</h3>
    {restricted && <p className="small" data-testid="leave-view-scope">審核入口的受限歷史檢視：截至所選提交版本 {d.throughRevision}，只提供本次與必要前序。未提供後續內容；以下不是業務單全局目前狀態。</p>}
    <dl className="compare">
      <dt>原提交</dt><dd>#{d.origin.id} {describe(d.origin)}；{d.origin.reason}</dd>
      <dt>原核准內容</dt><dd>{d.originalApproved ? <span>#{d.originalApproved.id} {describe(d.originalApproved)}</span> : '尚未有核准內容'}</dd>
      <dt>{restricted ? '此歷史檢視的有效內容／審核基礎' : '目前有效內容'}</dt><dd data-testid="leave-effective" data-effective-id={d.effective?.id ?? ''}>
        有效版本 {d.effectiveVersion}：{d.effective ? <span>#{d.effective.id} {describe(d.effective)}（{d.effective.minutes} 分鐘）</span> :
          d.canceled ? (restricted ? '此歷史檢視已完成整段銷假；不表示後續狀態' : '已完成整段銷假；原核准內容與歷程保留') : (restricted ? '此歷史檢視尚無核准內容；未提供全局目前狀態' : '尚無有效請假')}
      </dd>
      <dt>本次操作</dt><dd>{r.revision ? REVISION_LABEL[r.revision.operation] : '新增請假'}：{describe(r)}</dd>
      {base && <><dt>移除／銷假範圍</dt><dd data-testid="leave-removed">{removed(base, r)}</dd></>}
    </dl>
    <p className="small">變更／銷假待審、撤回或退回都不改原有效內容；只有核准才生效。歷史「已核准」不等於目前仍有效。</p>
    <h4>提交版本（原內容保留）</h4>
    <ul className="history-list" data-testid="leave-versions">
      {d.versions.map(v => <li key={v.id}>#{v.id}／版本 {v.revision?.number} {describe(v)}：{LEAVE_STATUS_LABEL[v.status]}
        {v.revision?.currentlyEffective ? (restricted ? '（此歷史檢視有效）' : '（目前有效）') : v.status === 'APPROVED' ? '（歷史核准）' : ''}
        {v.decisionReason && '；' + v.decisionReason}
      </li>)}
    </ul>
  </section>
}
export function LeaveAttendanceEvidence({ evidence, historical = false }: Readonly<{ evidence: AttendanceEvidence; historical?: boolean }>) {
  return <section className="banner banner--info" data-testid={historical ? 'decision-evidence' : 'leave-attendance-evidence'}>
    <h3>{historical ? '審核當時保存的出勤摘要' : '相關日期出勤依據'}</h3>
    <p className="small">資料取得：{formatDateTime(evidence.capturedAt)}；日期：{evidence.dates.join('、')}。
      {historical ? '這是當時快照，不隨事後更正改動。' : '重新整理會讀取最新資料；審核前資料變動時需重新確認。'}</p>
    <p className="small">打卡只作參考，不自動核准，也不自動證明實際工作；資料不足由審核人判斷。</p>
    <h4>目前紀錄{historical ? '（當時所見）' : '（可能已更正）'}</h4>
    {evidence.records.length === 0 && <p>沒有出勤紀錄，請依其他資料判斷。</p>}
    <ul className="history-list">{evidence.records.map(a => <li key={a.id}>
      #{a.id}／版本 {a.version} {a.workDate} {formatTime(a.clockInAt)}～{formatTime(a.clockOutAt)}；
      {a.source === 'MANUAL' ? '補登' : '打卡'}{a.corrected ? '／已更正' : ''}／{RECORD_STATUS_LABEL[a.status]}
    </li>)}</ul>
    <h4>原始打卡事件（不被更正覆寫）</h4>
    {evidence.events.length === 0 && <p>沒有原始打卡事件；補登不會製造打卡事件。</p>}
    <ul className="history-list">{evidence.events.map(e => <li key={e.id}>
      事件 #{e.id}／時段 #{e.attendanceId} {e.action === 'CLOCK_IN' ? '上班' : '下班'} {formatDateTime(e.acceptedAt)}
    </li>)}</ul>
  </section>
}
