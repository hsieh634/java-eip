import { useState, type ReactNode } from 'react'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'

interface ConfirmDialogProps {
  title: string
  /** 執行前要讓使用者看清楚的內容：員工、日期、原值、預計結果 */
  children: ReactNode
  confirmLabel: string
  danger?: boolean
  /** 失敗時請拋出例外（會顯示在對話框內）；成功時由外層關閉對話框 */
  onConfirm: () => Promise<void>
  onCancel: () => void
}

/** 管理操作的確認視窗。沒有確認就不會送出；送出中不可重複點擊。 */
export function ConfirmDialog({ title, children, confirmLabel, danger = false, onConfirm, onCancel }: Readonly<ConfirmDialogProps>) {
  const messageOf = useApiErrorMessage()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function confirm() {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      await onConfirm()
    } catch (e) {
      setError(messageOf(e))
      setBusy(false)
    }
  }

  return (
    <div className="modal-backdrop">
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
        <h2 id="confirm-title">{title}</h2>
        <div className="modal__body">{children}</div>
        <p className="form-error" role="alert">
          {error}
        </p>
        <div className="actions">
          <button type="button" className={danger ? 'btn btn--danger' : 'btn btn--primary'} onClick={() => void confirm()} disabled={busy}>
            {busy ? '處理中…' : confirmLabel}
          </button>
          <button type="button" className="btn" onClick={onCancel} disabled={busy}>
            取消
          </button>
        </div>
      </div>
    </div>
  )
}
