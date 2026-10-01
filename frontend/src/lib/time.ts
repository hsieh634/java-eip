/** 業務時區固定為台北（與後端 BusinessTime.ZONE 一致）。後端回傳的是 ISO 8601 時間點，顯示時才換算。 */
export const BUSINESS_TIME_ZONE = 'Asia/Taipei'

const timeFormat = new Intl.DateTimeFormat('zh-TW', {
  timeZone: BUSINESS_TIME_ZONE,
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
})

const weekdayFormat = new Intl.DateTimeFormat('zh-TW', { timeZone: 'UTC', weekday: 'short' })

/** 2026-10-01T01:05:00Z → 09:05:00（台北）。沒有時間則回傳破折號。 */
export function formatTime(iso: string | null): string {
  return iso ? timeFormat.format(new Date(iso)) : '—'
}

/** '2026-10-01' → '2026-10-01（四）'。業務日期是日曆日，不涉及時區換算。 */
export function formatWorkDate(workDate: string): string {
  const [y, m, d] = workDate.split('-').map(Number)
  const weekday = weekdayFormat.format(new Date(Date.UTC(y, m - 1, d)))
  return `${workDate}（${weekday.replace('週', '')}）`
}

/** 兩個 YYYY-MM-DD 之間含起訖的日曆日數（用 UTC 計算，避免夏令時間等干擾）。 */
export function inclusiveDays(from: string, to: string): number {
  const toUtc = (s: string) => {
    const [y, m, d] = s.split('-').map(Number)
    return Date.UTC(y, m - 1, d)
  }
  return Math.round((toUtc(to) - toUtc(from)) / 86_400_000) + 1
}
