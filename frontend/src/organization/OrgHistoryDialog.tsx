import type { OrgHistoryEntry } from '../api/organization'
import { formatDateTime } from '../lib/time'
import { useLoad } from '../lib/useLoad'
import { ACTION_LABEL, fieldLabel, formatChangeValue } from './labels'

function actorLabel(e: OrgHistoryEntry): string {
  if (e.source === 'SYSTEM_DEMO_SEED') return '系統（開發環境示範初始化）'
  return e.actor ? `${e.actor.displayName}（${e.actor.employeeNo}）` : '（不明）'
}

/** 組織歷程（員工或部門）：誰、何時、原因、每個欄位的前後值。只讀；歷程不能修改或刪除。 */
export function OrgHistoryDialog({
  title,
  loadKey,
  loader,
  onClose,
}: Readonly<{ title: string; loadKey: string; loader: () => Promise<OrgHistoryEntry[]>; onClose: () => void }>) {
  const history = useLoad(loader, loadKey)
  return (
    <div className="modal-backdrop">
      <div className="modal modal--wide" role="dialog" aria-modal="true" aria-labelledby="org-history-title" data-testid="org-history-dialog">
        <h2 id="org-history-title">{title}</h2>
        {history.state.kind === 'loading' && <p className="muted">載入中…</p>}
        {history.state.kind === 'error' && (
          <div>
            <p className="form-error" role="alert">
              無法載入歷程：{history.state.message}
            </p>
            <button type="button" className="btn" onClick={history.reload}>
              重試
            </button>
          </div>
        )}
        {history.state.kind === 'ready' && history.state.data.length === 0 && <p className="muted">沒有歷程紀錄。</p>}
        {history.state.kind === 'ready' && history.state.data.length > 0 && (
          <ul className="history-list" data-testid="org-history-list">
            {history.state.data.map((e) => (
              <li key={e.id} className="history-item" data-action={e.action} data-source={e.source}>
                <div className="request-item__head">
                  <strong>{ACTION_LABEL[e.action]}</strong>
                  <span className="muted small">{formatDateTime(e.occurredAt)}</span>
                </div>
                <p className="small">操作者：{actorLabel(e)}</p>
                {e.reason && <p className="small">原因：{e.reason}</p>}
                <ul className="change-list">
                  {e.changes.map((c) => (
                    <li key={c.field} className="small">
                      {fieldLabel(c.field)}：{formatChangeValue(c, c.before)} → <strong>{formatChangeValue(c, c.after)}</strong>
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        )}
        <div className="actions">
          <button type="button" className="btn" onClick={onClose}>
            關閉
          </button>
        </div>
      </div>
    </div>
  )
}
