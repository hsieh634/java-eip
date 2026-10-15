import type { ReactNode } from 'react'
import type { LeaveDetail, LeaveHistoryEntry } from '../api/leave'
import { formatDateTime, formatWorkDate } from '../lib/time'
import { useLoad } from '../lib/useLoad'
import { ACTION_LABEL, APPROVER_SOURCE_LABEL, durationText, LEAVE_STATUS_CHIP, LEAVE_STATUS_LABEL, LEAVE_TYPE_LABEL } from './labels'
import { LeaveAttendanceEvidence, LeaveDocumentSummary } from './LeaveDocumentSummary'

/** 一筆申請的詳情與歷程。actions 由各頁決定（本人：撤回；審核人：核准／退回；管理員：改派）。 */
export function LeaveDetailDialog({
  title,
  loadKey,
  loader,
  onClose,
  actions,
}: Readonly<{
  title: string
  loadKey: string
  loader: () => Promise<LeaveDetail>
  onClose: () => void
  actions?: (detail: LeaveDetail, reload: () => void) => ReactNode
}>) {
  const detail = useLoad(loader, loadKey)
  return (
    <div className="modal-backdrop">
      <div className="modal modal--wide" role="dialog" aria-modal="true" aria-labelledby="leave-detail-title" data-testid="leave-detail">
        <h2 id="leave-detail-title">{title}</h2>
        {detail.state.kind === 'loading' && <p className="muted">載入中…</p>}
        {detail.state.kind === 'error' && (
          <div>
            <p className="form-error" role="alert">
              無法載入這筆申請：{detail.state.message}
            </p>
            <button type="button" className="btn" onClick={detail.reload}>
              重試
            </button>
          </div>
        )}
        {detail.state.kind === 'ready' && (
          <>
            <LeaveSummary detail={detail.state.data} />
            <LeaveDocumentSummary detail={detail.state.data} />
            {detail.state.data.evidence && <LeaveAttendanceEvidence evidence={detail.state.data.evidence} />}
            <button type="button" className="btn btn--small" onClick={detail.reload}>重新整理詳情與出勤依據</button>
            <h3>歷程</h3>
            <LeaveHistoryList entries={detail.state.data.history} />
            {actions?.(detail.state.data, detail.reload)}
          </>
        )}
        <div className="actions">
          <button type="button" className="btn" onClick={onClose}>
            關閉
          </button>
        </div>
      </div>
    </div>
  )
}

export function LeaveSummary({ detail }: Readonly<{ detail: LeaveDetail }>) {
  const r = detail.request
  return (
    <dl className="compare" data-testid="leave-summary" data-status={r.status} data-version={r.version}>
      <dt>申請人</dt>
      <dd>
        {r.applicant.displayName}（{r.applicant.employeeNo}）
      </dd>
      <dt>假別</dt>
      <dd>{LEAVE_TYPE_LABEL[r.leaveType]}</dd>
      <dt>日期與時間</dt>
      <dd>
        {formatWorkDate(r.workDate)} {r.startTime}～{r.endTime}
      </dd>
      <dt>時段長度</dt>
      <dd className="small">{durationText(r.minutes)}</dd>
      <dt>原因</dt>
      <dd className="cell--wrap">{r.reason}</dd>
      <dt>狀態</dt>
      <dd>
        <span className={LEAVE_STATUS_CHIP[r.status]}>{LEAVE_STATUS_LABEL[r.status]}</span>
      </dd>
      <dt>審核人</dt>
      <dd>
        {r.approver.displayName}（{APPROVER_SOURCE_LABEL[r.approverSource]}）
        {r.status === 'PENDING' && !r.approver.valid && <span className="tag">目前已無效，需由管理員改派</span>}
      </dd>
      {r.revision && <><dt>送出時審核人</dt><dd>{r.revision.submittedApprover.displayName}（
        {r.revision.submittedApproverSource === 'DESIGNATED' ? '指定請假審核人' :
          r.revision.submittedApproverSource === 'MANAGER' ? '直屬主管' :
            r.revision.submittedApproverSource === 'WORKFLOW' ? '已發布請假流程' : '歷史來源未記錄'}）</dd></>}
      <dt>提交時流程快照</dt><dd data-testid="leave-approval-snapshot">{r.approvalSnapshot ? <>
        {r.approvalSnapshot.source === 'PERSONAL' ? '個人指定優先' : r.approvalSnapshot.source === 'WORKFLOW' ? '已發布請假流程' : '原直屬主管預設'}
        ／{r.approvalSnapshot.workflowVersionNumber !== null ? `流程版本 ${r.approvalSnapshot.workflowVersionNumber}：${r.approvalSnapshot.workflowName}` : '無流程版本'}
        ／{r.approvalSnapshot.stageName}／{r.approvalSnapshot.rule === 'MANAGER' ? '直屬主管' : '指定審核人'}
        ／送出時：{r.approvalSnapshot.approver.displayName}（{r.approvalSnapshot.approver.employeeNo}）。原快照保留，目前指派以上方為準。
      </> : '舊制提交：未保存新流程快照，不推造歷史流程版本。'}</dd>
      {r.decidedBy && (
        <>
          <dt>{r.status === 'WITHDRAWN' ? '撤回' : '處理者'}</dt>
          <dd>
            {r.decidedBy.displayName}（{formatDateTime(r.decidedAt)}）
          </dd>
        </>
      )}
      {r.decisionReason && (
        <>
          <dt>{r.status === 'REJECTED' ? '退回原因' : '撤回原因'}</dt>
          <dd className="cell--wrap" data-testid="decision-reason">
            {r.decisionReason}
          </dd>
        </>
      )}
    </dl>
  )
}

function approverChange(h: LeaveHistoryEntry): string | null {
  if (h.action === 'SUBMITTED') return h.afterApprover ? `審核人：${h.afterApprover.displayName}` : null
  if (h.action === 'REASSIGNED') return `審核人：${h.beforeApprover?.displayName ?? '（無）'} → ${h.afterApprover?.displayName ?? '（無）'}`
  if (h.action === 'APPROVER_SETTING_CHANGED') {
    return `指定審核人：${h.beforeApprover?.displayName ?? '（未指定）'} → ${h.afterApprover?.displayName ?? '（未指定；新提交依共用流程／主管預設）'}`
  }
  return null
}

export function LeaveHistoryList({ entries }: Readonly<{ entries: LeaveHistoryEntry[] }>) {
  if (entries.length === 0) return <p className="muted">沒有歷程紀錄。</p>
  return (
    <ul className="history-list" data-testid="leave-history">
      {entries.map((h) => (
        <li key={h.id} className="history-item" data-action={h.action}>
          <div className="request-item__head">
            <strong>{ACTION_LABEL[h.action]}{h.leaveRequestId != null && '／提交 #' + h.leaveRequestId}</strong>
            <span className="muted small">{formatDateTime(h.occurredAt)}</span>
          </div>
          <p className="small">
            操作者：{h.actor.displayName}（{h.actor.employeeNo}）
          </p>
          {approverChange(h) && <p className="small">{approverChange(h)}</p>}
          {h.reason && <p className="small">原因：{h.reason}</p>}
          {h.decision && <><p className="small">有效版本 {h.decision.beforeEffectiveVersion} → {h.decision.afterEffectiveVersion}</p>
            <LeaveAttendanceEvidence evidence={h.decision.attendance} historical /></>}
          {h.decisionRestricted && <p className="muted small">此入口未提供超出授權範圍的審核證據摘要。</p>}
          {!h.decision && !h.decisionRestricted && (h.action === 'APPROVED' || h.action === 'REJECTED') &&
            <p className="muted small">此歷史審核未保存出勤摘要，不代表當時沒有出勤。</p>}
        </li>
      ))}
    </ul>
  )
}
