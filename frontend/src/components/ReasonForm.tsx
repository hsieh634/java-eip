import { useState, type FormEvent } from 'react'
import { useApiErrorMessage } from '../auth/useApiErrorMessage'

interface ReasonFormProps {
  /** 例如「申請重新打卡」 */
  label: string
  placeholder?: string
  submitLabel: string
  /** 失敗時請拋出例外；成功時由外層關閉表單（此元件隨之卸載） */
  onSubmit: (reason: string) => Promise<void>
  onCancel: () => void
}

/** 只需要「原因」的小表單（申請重新打卡、退回申請…）。送出中停用；原因空白在前端就擋下，後端仍會驗證。 */
export function ReasonForm({ label, placeholder, submitLabel, onSubmit, onCancel }: Readonly<ReasonFormProps>) {
  const messageOf = useApiErrorMessage()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (submitting) return
    if (!reason.trim()) {
      setError('請填寫原因。')
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      await onSubmit(reason.trim())
    } catch (e) {
      setError(messageOf(e))
      setSubmitting(false)
    }
  }

  return (
    <form className="reason-form" onSubmit={handleSubmit} noValidate>
      <label htmlFor="reason-text">{label}</label>
      <textarea
        id="reason-text"
        rows={3}
        maxLength={500}
        placeholder={placeholder}
        value={reason}
        onChange={(e) => setReason(e.target.value)}
      />
      <p className="form-error" role="alert">
        {error}
      </p>
      <div className="actions">
        <button type="submit" className="btn btn--primary" disabled={submitting}>
          {submitting ? '送出中…' : submitLabel}
        </button>
        <button type="button" className="btn" onClick={onCancel} disabled={submitting}>
          取消
        </button>
      </div>
    </form>
  )
}
