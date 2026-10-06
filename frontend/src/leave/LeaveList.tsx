import { useState, type FormEvent, type ReactNode } from 'react'
import type { LeavePage, LeaveRequest, ListQuery, StatusFilter } from '../api/leave'
import { formatMinutes } from '../lib/hours'
import { formatWorkDate } from '../lib/time'
import { useLoad } from '../lib/useLoad'
import { LEAVE_STATUS_CHIP, LEAVE_STATUS_LABEL, LEAVE_TYPE_LABEL } from './labels'

interface LeaveListProps {
  /** 載入函式；key 用來區分不同清單（本人／審核／管理） */
  loadKey: string
  load: (q: ListQuery) => Promise<LeavePage>
  initialStatus?: StatusFilter
  /** 外部要求重新載入時遞增（例如送出或審核完成） */
  refreshSeq: number
  showApplicant?: boolean
  emptyText: string
  onOpen: (r: LeaveRequest) => void
  testId: string
  /** 篩選列額外的欄位（例如管理端的員工篩選） */
  extraFilters?: ReactNode
  extraKey?: string
}

/** 請假清單：狀態、日期範圍（同時填或都不填，最多 366 天）、分頁；載入中、錯誤＋重試、空狀態。 */
export function LeaveList({ loadKey, load, initialStatus = 'ALL', refreshSeq, showApplicant = false, emptyText, onOpen, testId, extraFilters, extraKey = '' }: Readonly<LeaveListProps>) {
  const [draft, setDraft] = useState({ status: initialStatus, from: '', to: '' })
  const [query, setQuery] = useState<ListQuery>({ status: initialStatus, from: '', to: '', page: 0 })
  const [formError, setFormError] = useState<string | null>(null)
  const list = useLoad(() => load(query), `${loadKey}:${JSON.stringify(query)}:${extraKey}:${refreshSeq}`)

  function apply(event: FormEvent) {
    event.preventDefault()
    if (Boolean(draft.from) !== Boolean(draft.to)) return setFormError('請同時填寫起日與迄日，或兩者都不填（不限日期）。')
    if (draft.from && draft.from > draft.to) return setFormError('起日不可晚於迄日。')
    setFormError(null)
    setQuery({ ...draft, page: 0 })
  }

  const ready = list.state.kind === 'ready' ? list.state.data : null
  const totalPages = ready ? Math.max(1, Math.ceil(ready.total / ready.size)) : 1
  return (
    <div data-testid={testId}>
      <form className="range-form" onSubmit={apply} data-testid={`${testId}-filters`}>
        <div className="range-field">
          <label htmlFor={`${testId}-status`}>狀態</label>
          <select id={`${testId}-status`} value={draft.status} onChange={(e) => setDraft((d) => ({ ...d, status: e.target.value as StatusFilter }))}>
            <option value="ALL">全部</option>
            <option value="PENDING">待審</option>
            <option value="APPROVED">已核准</option>
            <option value="REJECTED">已退回</option>
            <option value="WITHDRAWN">已撤回</option>
          </select>
        </div>
        <div className="range-field">
          <label htmlFor={`${testId}-from`}>起日</label>
          <input id={`${testId}-from`} type="date" value={draft.from} onChange={(e) => setDraft((d) => ({ ...d, from: e.target.value }))} />
        </div>
        <div className="range-field">
          <label htmlFor={`${testId}-to`}>迄日</label>
          <input id={`${testId}-to`} type="date" value={draft.to} onChange={(e) => setDraft((d) => ({ ...d, to: e.target.value }))} />
        </div>
        {extraFilters}
        <button type="submit" className="btn">
          查詢
        </button>
      </form>
      {formError && (
        <p className="form-error" role="alert">
          {formError}
        </p>
      )}
      {list.state.kind === 'loading' && <p className="muted">載入中…</p>}
      {list.state.kind === 'error' && (
        <div>
          <p className="form-error" role="alert">
            無法載入請假清單：{list.state.message}
          </p>
          <button type="button" className="btn" onClick={list.reload}>
            重試
          </button>
        </div>
      )}
      {ready && ready.items.length === 0 && (
        <p className="muted" data-testid={`${testId}-empty`}>
          {emptyText}
        </p>
      )}
      {ready && ready.items.length > 0 && (
        <>
          <table className="records-table" data-testid={`${testId}-table`}>
            <thead>
              <tr>
                {showApplicant && <th scope="col">申請人</th>}
                <th scope="col">假別</th>
                <th scope="col">日期</th>
                <th scope="col">時間</th>
                <th scope="col">時段長度</th>
                <th scope="col">狀態</th>
                <th scope="col">審核人</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              {ready.items.map((r) => (
                <tr key={r.id} data-leave-id={r.id} data-status={r.status} data-reason={r.reason}>
                  {showApplicant && <td data-label="申請人">{r.applicant.displayName}</td>}
                  <td data-label="假別">{LEAVE_TYPE_LABEL[r.leaveType]}</td>
                  <td data-label="日期">{formatWorkDate(r.workDate)}</td>
                  <td data-label="時間">
                    {r.startTime}～{r.endTime}
                  </td>
                  <td data-label="時段長度">{formatMinutes(r.minutes)}</td>
                  <td data-label="狀態">
                    <span className={LEAVE_STATUS_CHIP[r.status]}>{LEAVE_STATUS_LABEL[r.status]}</span>
                  </td>
                  <td data-label="審核人">
                    {r.approver.displayName}
                    {r.status === 'PENDING' && !r.approver.valid && <span className="tag">已無效，需改派</span>}
                  </td>
                  <td data-label="操作">
                    <button type="button" className="btn btn--small" onClick={() => onOpen(r)}>
                      詳情
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="pager">
            <button type="button" className="btn btn--small" disabled={query.page === 0} onClick={() => setQuery((q) => ({ ...q, page: q.page - 1 }))}>
              上一頁
            </button>
            <span className="small">
              第 {ready.page + 1} / {totalPages} 頁，共 {ready.total} 筆
            </span>
            <button type="button" className="btn btn--small" disabled={ready.page + 1 >= totalPages} onClick={() => setQuery((q) => ({ ...q, page: q.page + 1 }))}>
              下一頁
            </button>
          </div>
        </>
      )}
    </div>
  )
}
