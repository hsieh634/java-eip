import { useCallback, useEffect, useState, type FormEvent } from 'react'
import * as attendanceApi from '../api/attendance'
import type { AttendanceList } from '../api/attendance'
import { fetchMyHistory, type HistoryEntry } from '../api/attendanceRequests'
import { ApiError } from '../api/http'
import { RECORD_STATUS_LABEL } from '../attendance/labels'
import { useAutoRefresh } from '../attendance/useAutoRefresh'
import { useMyRequests } from '../attendance/useMyRequests'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'
import { useAuth } from '../auth/useAuth'
import { CorrectionForm } from '../components/CorrectionForm'
import { HistoryList } from '../components/HistoryList'
import { RequestsPanel } from '../components/RequestsPanel'
import { TodayAttendanceCard } from '../components/TodayAttendanceCard'
import { formatTime, formatWorkDate, inclusiveDays } from '../lib/time'

const MAX_RANGE_DAYS = 31 // 與後端一致；後端仍會自行驗證

type ListState =
  | { kind: 'loading' }
  | { kind: 'ready'; data: AttendanceList }
  | { kind: 'error'; message: string }

type HistoryState =
  | { kind: 'closed' }
  | { kind: 'loading' }
  | { kind: 'ready'; entries: HistoryEntry[] }
  | { kind: 'error'; message: string }

interface Range {
  from: string
  to: string
}

/** 本人出勤頁：今日打卡、日期範圍查詢（含已作廢的紀錄）、申請更正／補登、我的申請、更正歷程。 */
export function AttendancePage() {
  const { refresh } = useAuth()
  const messageOf = useApiErrorMessage()
  const [list, setList] = useState<ListState>({ kind: 'loading' })
  // appliedRange：目前套用的查詢範圍；null＝使用後端預設（本月）
  const [appliedRange, setAppliedRange] = useState<Range | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [rangeError, setRangeError] = useState<string | null>(null)
  const [correction, setCorrection] = useState<{ date?: string; id?: number } | null>(null)
  const [history, setHistory] = useState<HistoryState>({ kind: 'closed' })

  const [cardKey, setCardKey] = useState(0)
  const reloadList = useCallback(() => setReloadKey((k) => k + 1), [])
  const requests = useMyRequests(reloadList)
  const reloadRequests = requests.reload
  // 今日卡片自己動作（打卡、申請重新打卡、撤回）後：同步更新「我的申請」與紀錄列表
  const handleCardChanged = useCallback(() => {
    reloadList()
    void reloadRequests()
  }, [reloadList, reloadRequests])
  // 「我的申請」或更正表單造成的變動：卡片有自己的狀態，換 key 讓它重新向後端查詢
  const handleRequestsChanged = useCallback(() => {
    reloadList()
    setCardKey((k) => k + 1)
  }, [reloadList])
  useAutoRefresh(reloadList) // 視窗回到前景時重新取得紀錄

  // 載入列表。appliedRange 或 reloadKey 改變就重新載入；active 旗標丟棄過期（較早發出）的回應。
  useEffect(() => {
    let active = true
    attendanceApi.fetchRecords(appliedRange ?? undefined).then(
      (data) => {
        if (!active) return
        setList({ kind: 'ready', data })
        setFrom((prev) => prev || data.from) // 只在尚未有輸入時填入預設值，不覆蓋使用者正在輸入的日期
        setTo((prev) => prev || data.to)
      },
      (error: unknown) => {
        if (!active) return
        if (error instanceof ApiError && error.status === 401) {
          void refresh() // 登入失效：回登入頁
          return
        }
        setList({ kind: 'error', message: error instanceof ApiError ? error.message : '發生未預期的錯誤' })
      },
    )
    return () => {
      active = false
    }
  }, [appliedRange, reloadKey, refresh])

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    // 前端先檢查以便即時提示；後端仍會驗證
    if (!from || !to) {
      setRangeError('請選擇起日與迄日。')
      return
    }
    if (from > to) {
      setRangeError('起日不可晚於迄日。')
      return
    }
    if (inclusiveDays(from, to) > MAX_RANGE_DAYS) {
      setRangeError(`日期範圍最多 ${MAX_RANGE_DAYS} 天（含起訖日）。`)
      return
    }
    setRangeError(null)
    setList({ kind: 'loading' })
    setAppliedRange({ from, to })
  }

  function retry() {
    setList({ kind: 'loading' })
    setReloadKey((k) => k + 1)
  }

  async function toggleHistory() {
    if (history.kind !== 'closed') {
      setHistory({ kind: 'closed' })
      return
    }
    setHistory({ kind: 'loading' })
    try {
      setHistory({ kind: 'ready', entries: await fetchMyHistory() })
    } catch (e) {
      setHistory({ kind: 'error', message: messageOf(e) })
    }
  }

  return (
    <>
      <h1>出勤打卡</h1>
      <p className="muted">在這裡打卡、查看自己的出勤紀錄；誤按或漏打請提出申請，由管理員審核。</p>

      <div className="attendance-layout">
        <div className="stack">
          <TodayAttendanceCard key={cardKey} onChanged={handleCardChanged} />
          <RequestsPanel state={requests.state} reload={requests.reload} onChanged={handleRequestsChanged} />
        </div>

        <div className="stack">
          <section className="card card--records" aria-labelledby="records-title">
            <div className="card__title-row">
              <h2 id="records-title">我的出勤紀錄</h2>
              <button type="button" className="btn btn--small" onClick={() => setCorrection({})}>
                申請更正／補登時間
              </button>
            </div>

            {correction && (
              <CorrectionForm
                key={`${correction.date ?? 'new'}:${correction.id ?? 'select'}`}
                initialDate={correction.date}
                initialRecordId={correction.id}
                onSubmitted={() => {
                  setCorrection(null)
                  void requests.reload()
                  handleRequestsChanged()
                }}
                onCancel={() => setCorrection(null)}
              />
            )}

            <form className="range-form" onSubmit={handleSubmit} noValidate>
              <div className="range-field">
                <label htmlFor="range-from">起日</label>
                <input id="range-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
              </div>
              <div className="range-field">
                <label htmlFor="range-to">迄日</label>
                <input id="range-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
              </div>
              <button type="submit" className="btn">
                查詢
              </button>
            </form>
            <p className="form-error" role="alert">
              {rangeError}
            </p>

            {list.kind === 'loading' && <p className="muted">載入中…</p>}

            {list.kind === 'error' && (
              <div>
                <p className="form-error" role="alert">
                  無法載入出勤紀錄：{list.message}
                </p>
                <button type="button" className="btn" onClick={retry}>
                  重試
                </button>
              </div>
            )}

            {list.kind === 'ready' && list.data.records.length === 0 && (
              <p className="muted" data-testid="records-empty">
                {list.data.from} 至 {list.data.to} 沒有出勤紀錄。
              </p>
            )}

            {list.kind === 'ready' && list.data.records.length > 0 && (
              <table className="records-table" data-testid="records-table">
                <thead>
                  <tr>
                    <th scope="col">日期</th>
                    <th scope="col">上班</th>
                    <th scope="col">下班</th>
                    <th scope="col">狀態</th>
                    <th scope="col">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {list.data.records.map((r) => (
                    // 同一日期可能有多筆（作廢＋有效），key 必須是紀錄 id，不能只用日期
                    <tr key={r.id} className={r.status === 'VOIDED' ? 'row--voided' : undefined} data-record-id={r.id} data-status={r.status}>
                      <td data-label="日期">{formatWorkDate(r.workDate)}</td>
                      <td data-label="上班">{formatTime(r.clockInAt)}</td>
                      <td data-label="下班">{formatTime(r.clockOutAt)}</td>
                      <td data-label="狀態">
                        <span className={`status-chip status-chip--${r.status.toLowerCase()}`}>{RECORD_STATUS_LABEL[r.status]}</span>
                        {r.source === 'MANUAL' && <span className="status-chip status-chip--manual">補登</span>}
                        {r.corrected && r.source !== 'MANUAL' && <span className="status-chip status-chip--manual">已更正</span>}
                      </td>
                      <td data-label="操作">
                        {r.status !== 'VOIDED' && (
                          <button type="button" className="btn btn--small" onClick={() => setCorrection({ date: r.workDate, id: r.id })}>
                            申請更正
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>

          <section className="card" aria-labelledby="history-title">
            <div className="card__title-row">
              <h2 id="history-title">更正歷程</h2>
              <button type="button" className="btn btn--small" onClick={() => void toggleHistory()}>
                {history.kind === 'closed' ? '查看歷程' : '收合'}
              </button>
            </div>
            {history.kind === 'loading' && <p className="muted">載入中…</p>}
            {history.kind === 'error' && (
              <p className="form-error" role="alert">
                {history.message}
              </p>
            )}
            {history.kind === 'ready' && <HistoryList entries={history.entries} />}
            {history.kind === 'closed' && <p className="muted small">包含你提出的申請、管理員的核准／退回，與對你的出勤所做的更正。</p>}
          </section>
        </div>
      </div>
    </>
  )
}
