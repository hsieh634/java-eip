/**
 * 工時的「小時」輸入 ↔ 整數分鐘。
 *
 * 畫面讓使用者輸入小時（0.25、1.5、7.75），送給後端的一律是整數分鐘。換算只用整數運算：
 * 把「小時」拆成整數部分與最多兩位小數，換成「百分之一小時」再換分鐘，不經過浮點數，
 * 也絕不把不合法的值（例如 0.1 小時＝6 分鐘）四捨五入成合法工時。
 */

export const STEP_MINUTES = 15
export const MIN_MINUTES = 15
export const MAX_MINUTES = 1440

// 最多兩位整數、最多兩位小數：0.25、1.5、.75、24、7.75
const HOURS = /^(\d{1,2})?(?:\.(\d{1,2}))?$/

export type HoursParse = { ok: true; minutes: number } | { ok: false; message: string }

export function parseHours(input: string): HoursParse {
  const text = input.trim()
  const match = HOURS.exec(text)
  if (!text || !match || (match[1] === undefined && match[2] === undefined)) {
    return { ok: false, message: '請輸入時數（小時），例如 1.5' }
  }
  const whole = Number(match[1] ?? '0')
  const fraction = (match[2] ?? '').padEnd(2, '0') // ".5" → "50"
  const hundredths = whole * 100 + Number(fraction) // 1.5 小時 → 150
  // 15 分鐘＝0.25 小時＝25 個百分之一小時；不是 25 的倍數就不是 15 分鐘的倍數
  if (hundredths % 25 !== 0) {
    return { ok: false, message: '時數需以 0.25 小時（15 分鐘）為單位，例如 0.25、1.5、7.75' }
  }
  const minutes = (hundredths / 25) * STEP_MINUTES
  if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
    return { ok: false, message: '時數需在 0.25～24 小時之間' }
  }
  return { ok: true, minutes }
}

/** 90 → "1.5"（給輸入欄位預填）。分鐘一定是 15 的倍數，所以最多兩位小數且精確。 */
export function minutesToHoursText(minutes: number): string {
  const hundredths = (minutes / STEP_MINUTES) * 25
  const whole = Math.floor(hundredths / 100)
  const rest = hundredths % 100
  if (rest === 0) return String(whole)
  return `${whole}.${String(rest).padStart(2, '0').replace(/0$/, '')}`
}

/** 90 → "1 小時 30 分"；0 → "0 分"。 */
export function formatMinutes(minutes: number): string {
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (hours === 0) return `${rest} 分`
  return rest === 0 ? `${hours} 小時` : `${hours} 小時 ${rest} 分`
}
