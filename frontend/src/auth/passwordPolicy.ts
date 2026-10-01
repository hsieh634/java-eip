/**
 * 與後端 LoginRequest.PASSWORD_MAX_BYTES 對應：bcrypt 只使用密碼前 72 個 UTF-8 位元組。
 * 這裡只是「提早提示」改善操作；真正的限制在後端驗證，這份檢查不能取代它。
 * 不 trim、不截斷使用者輸入的密碼。
 */
export const PASSWORD_MAX_BYTES = 72

const encoder = new TextEncoder()

/** 字串編碼成 UTF-8 後的位元組數（ASCII 1、中文 3、emoji 4）。 */
export function utf8ByteLength(value: string): number {
  return encoder.encode(value).length
}
