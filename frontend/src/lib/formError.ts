/** 前端自己檢查出來的表單錯誤（訊息本身就是要顯示給使用者看的文字）。 */
export class FormError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'FormError'
  }
}
