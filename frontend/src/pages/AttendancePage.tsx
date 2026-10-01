import { useCallback, useEffect, useState, type FormEvent } from 'react'
import * as attendanceApi from '../api/attendance'
import type { AttendanceList, AttendanceStatus } from '../api/attendance'
import { ApiError } from '../api/http'
import { useAuth } from '../auth/useAuth'
import { TodayAttendanceCard } from '../components/TodayAttendanceCard'
import { formatTime, formatWorkDate, inclusiveDays } from '../lib/time'

const MAX_RANGE_DAYS = 31 // 與後端一致；後端仍會自行驗證

const STATUS_LABEL: Record<AttendanceStatus, string> = {
  NOT_STARTED: '尚未上班',
  WORKING: '工作中',
  COMPLETED: '已完成',
  INCOMPLETE: '未完成（未下班）',
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'ready'; data: AttendanceList }
  | { kind: 'error'; message: string }

interface Range {
  from: string
  to: string
}

/** 本人出勤頁：今日打卡＋日期範圍查詢。預設查本月（由後端決定預設範圍）。 */
export function AttendancePage() {
  const { refresh } = useAuth()
  const [list, setList] = useState<ListState>({ kind: 'loading' })
  // appliedRange：目前套用的查詢範圍；null＝使用後端預設（本月）
  const [appliedRange, setAppliedRange] = useState<Range | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [rangeError, setRangeError] = useState<string | null>(null)

  const handleChanged = useCallback(() => setReloadKey((k) => k + 1), [])

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

  return (
    <>
      <h1>出勤打卡</h1>
      <p className="muted">在這裡打卡，並查看自己的出勤紀錄。</p>

      <div className="attendance-layout">
        <TodayAttendanceCard onChanged={handleChanged} />

        <section className="card card--records" aria-labelledby="records-title">
          <h2 id="records-title">我的出勤紀錄</h2>

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
                </tr>
              </thead>
              <tbody>
                {list.data.records.map((r) => (
                  <tr key={r.workDate}>
                    <td data-label="日期">{formatWorkDate(r.workDate)}</td>
                    <td data-label="上班">{formatTime(r.clockInAt)}</td>
                    <td data-label="下班">{formatTime(r.clockOutAt)}</td>
                    <td data-label="狀態">
                      <span className={`status-chip status-chip--${r.status.toLowerCase()}`}>{STATUS_LABEL[r.status]}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>
    </>
  )
}
