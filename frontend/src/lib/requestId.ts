/**
 * 產生一次「邏輯上的新增」的識別碼（UUID v4）。
 * crypto.randomUUID 只在安全來源（HTTPS、localhost）可用；在其他情況改用 crypto.getRandomValues 自行組成，
 * 兩者都是密碼學等級的亂數，不用 Math.random。
 */
export function newRequestId(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40 // 版本 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80 // RFC 4122 變體
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
