import { useState } from 'react'
import { fetchGeofence, fetchGeofenceHistory, updateGeofence, type GeofenceSettings, type GeofenceValues } from '../api/adminGeofence'
import type { CurrentUser } from '../api/auth'
import { ApiError } from '../api/http'
import { SCOPE_LABEL, describeCenter, parseForm, sameValues, toForm, type GeofenceForm } from '../attendance/geofenceForm'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { FormError } from '../lib/formError'
import { formatDateTime } from '../lib/time'
import { useLoad } from '../lib/useLoad'

/** 定位打卡設定（僅 ADMIN）。入口只對 ADMIN 顯示，但真正的權限由後端驗證。 */
export function GeofenceSettingsPage({ user }: Readonly<{ user: CurrentUser }>) {
  if (user.role !== 'ADMIN') {
    return (
      <>
        <h1>定位打卡設定</h1>
        <p className="form-error" role="alert">
          只有管理員可以使用這個功能。
        </p>
      </>
    )
  }
  return <GeofenceContent />
}

function GeofenceContent() {
  // seq：儲存成功或重新載入時 +1，讓設定與歷程都重新向後端取得
  const [seq, setSeq] = useState(0)
  const settings = useLoad(() => fetchGeofence(), `geofence:${seq}`)
  const history = useLoad(() => fetchGeofenceHistory(), `geofence-history:${seq}`)
  const [notice, setNotice] = useState<string | null>(null)
  const [conflict, setConflict] = useState(false)
  const reloadAll = () => {
    setConflict(false)
    setSeq((s) => s + 1)
  }

  return (
    <>
      <h1>定位打卡設定</h1>
      <p className="muted">
        設定公司中心與允許半徑後，員工按下受限制的打卡按鈕時，瀏覽器會詢問位置，由後端計算距離決定是否接受。
        這是以瀏覽器定位為基礎的規則，定位可能被模擬或偽造，不能完全防止代打卡。管理員自己的打卡也同樣受限制。
      </p>
      {notice && (
        <p className="notice notice--success" role="status" data-testid="geofence-notice">
          {notice}
        </p>
      )}
      {conflict && (
        <div className="banner banner--warn" role="alert" data-testid="geofence-conflict">
          <p>定位設定已被其他管理員更新，你的修改沒有儲存（不會覆蓋較新的設定）。請重新載入最新設定後再修改。</p>
          <button type="button" className="btn btn--small" onClick={reloadAll}>
            重新載入最新設定
          </button>
        </div>
      )}
      <div className="stack">
        <section className="card" aria-labelledby="geofence-title">
          <h2 id="geofence-title">目前設定</h2>
          {settings.state.kind === 'loading' && <p className="muted">載入中…</p>}
          {settings.state.kind === 'error' && (
            <div>
              <p className="form-error" role="alert">
                無法載入定位設定：{settings.state.message}
              </p>
              <button type="button" className="btn" onClick={settings.reload}>
                重試
              </button>
            </div>
          )}
          {settings.state.kind === 'ready' && (
            // key＝版本：載入新版本時表單以最新值重新初始化
            <SettingsForm
              key={settings.state.data.version}
              settings={settings.state.data}
              onSaved={(saved) => {
                setNotice(`已儲存定位設定（版本 ${saved.version}）。`)
                reloadAll()
              }}
              onConflict={() => {
                setNotice(null)
                setConflict(true)
              }}
            />
          )}
        </section>

        <section className="card" aria-labelledby="geofence-history-title">
          <h2 id="geofence-history-title">設定歷程</h2>
          {history.state.kind === 'loading' && <p className="muted">載入中…</p>}
          {history.state.kind === 'error' && (
            <div>
              <p className="form-error" role="alert">
                無法載入設定歷程：{history.state.message}
              </p>
              <button type="button" className="btn" onClick={history.reload}>
                重試
              </button>
            </div>
          )}
          {history.state.kind === 'ready' && history.state.data.length === 0 && <p className="muted">尚未修改過設定。</p>}
          {history.state.kind === 'ready' && history.state.data.length > 0 && (
            <ul className="history-list" data-testid="geofence-history">
              {history.state.data.map((h) => (
                <li key={h.id} className="history-item" data-version={h.version}>
                  <div className="request-item__head">
                    <strong>版本 {h.version}</strong>
                    <span>{h.actor ? `${h.actor.displayName}（${h.actor.employeeNo}）` : '—'}</span>
                    <span className="muted small">{formatDateTime(h.occurredAt)}</span>
                  </div>
                  <p className="small">原因：{h.reason}</p>
                  <p className="small">修改前：{describe(h.before)}</p>
                  <p className="small">修改後：{describe(h.after)}</p>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </>
  )
}

function describe(v: GeofenceValues): string {
  return `${v.enabled ? '啟用' : '關閉'}／中心 ${describeCenter(v)}／半徑 ${v.radiusMeters} 公尺／${SCOPE_LABEL[v.scope]}`
}

interface SettingsFormProps {
  settings: GeofenceSettings
  onSaved: (saved: GeofenceSettings) => void
  onConflict: () => void
}

function SettingsForm({ settings, onSaved, onConflict }: Readonly<SettingsFormProps>) {
  const [form, setForm] = useState<GeofenceForm>(() => toForm(settings))
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState<GeofenceValues | null>(null)
  const update = (patch: Partial<GeofenceForm>) => setForm((f) => ({ ...f, ...patch }))
  const { limits } = settings

  function review() {
    const parsed = parseForm(form, limits)
    if (!parsed.ok) return setError(parsed.message)
    if (sameValues(parsed.values, settings)) return setError('設定與目前相同，沒有需要儲存的變更。')
    if (!reason.trim()) return setError('請填寫修改原因。')
    setError(null)
    setPending(parsed.values)
  }

  async function save(values: GeofenceValues) {
    if (!reason.trim()) throw new FormError('請填寫修改原因。')
    try {
      const saved = await updateGeofence({ ...values, expectedVersion: settings.version, reason: reason.trim() })
      setPending(null)
      onSaved(saved)
    } catch (e) {
      if (e instanceof ApiError && e.code === 'SETTING_CHANGED') {
        setPending(null)
        onConflict()
        return
      }
      throw e
    }
  }

  return (
    <div data-testid="geofence-form" data-version={settings.version}>
      <p className="small" data-testid="geofence-updated">
        {settings.updatedBy
          ? `最近修改：${settings.updatedBy.displayName}（${settings.updatedBy.employeeNo}），${formatDateTime(settings.updatedAt)}；版本 ${settings.version}`
          : `尚未修改（初始設定）；版本 ${settings.version}`}
      </p>
      <div className="form-grid">
        <label htmlFor="geo-enabled">啟用定位限制</label>
        <input id="geo-enabled" type="checkbox" checked={form.enabled} onChange={(e) => update({ enabled: e.target.checked })} />
        <label htmlFor="geo-lat">公司緯度</label>
        <input id="geo-lat" type="text" inputMode="decimal" placeholder="例如 25.033964" value={form.latitude} onChange={(e) => update({ latitude: e.target.value })} />
        <label htmlFor="geo-lon">公司經度</label>
        <input id="geo-lon" type="text" inputMode="decimal" placeholder="例如 121.564468" value={form.longitude} onChange={(e) => update({ longitude: e.target.value })} />
        <label htmlFor="geo-radius">允許半徑（公尺）</label>
        <input id="geo-radius" type="text" inputMode="numeric" value={form.radius} onChange={(e) => update({ radius: e.target.value })} />
        <label htmlFor="geo-scope">適用操作</label>
        <select id="geo-scope" value={form.scope} onChange={(e) => update({ scope: e.target.value as GeofenceForm['scope'] })}>
          {(Object.keys(SCOPE_LABEL) as GeofenceForm['scope'][]).map((s) => (
            <option key={s} value={s}>
              {SCOPE_LABEL[s]}
            </option>
          ))}
        </select>
      </div>
      <p className="muted small">
        半徑 {limits.radiusMin}～{limits.radiusMax} 公尺；員工定位精度需在 {limits.maxAccuracyMeters} 公尺以內，且精度不會加進半徑放寬範圍。
        公司中心未設定時不能啟用。座標請以十進位度數輸入（0 是合法值）。
      </p>
      <label htmlFor="geo-reason">修改原因（必填）</label>
      <textarea id="geo-reason" rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      <p className="form-error" role="alert">
        {error}
      </p>
      <div className="actions">
        <button type="button" className="btn btn--primary" onClick={review}>
          儲存設定
        </button>
      </div>
      {pending && (
        <ConfirmDialog title="確認修改定位打卡設定" confirmLabel="確認儲存" onCancel={() => setPending(null)} onConfirm={() => save(pending)}>
          <table className="records-table" data-testid="geofence-diff">
            <thead>
              <tr>
                <th scope="col">項目</th>
                <th scope="col">修改前</th>
                <th scope="col">修改後</th>
              </tr>
            </thead>
            <tbody>
              <DiffRow label="定位限制" before={settings.enabled ? '啟用' : '關閉'} after={pending.enabled ? '啟用' : '關閉'} />
              <DiffRow label="公司中心" before={describeCenter(settings)} after={describeCenter(pending)} />
              <DiffRow label="允許半徑" before={`${settings.radiusMeters} 公尺`} after={`${pending.radiusMeters} 公尺`} />
              <DiffRow label="適用操作" before={SCOPE_LABEL[settings.scope]} after={SCOPE_LABEL[pending.scope]} />
            </tbody>
          </table>
          <p className="small">原因：{reason.trim()}</p>
          {pending.enabled && <p className="small">儲存後立即生效：之後的受限制打卡都要在範圍內（包含管理員自己）。</p>}
        </ConfirmDialog>
      )}
    </div>
  )
}

function DiffRow({ label, before, after }: Readonly<{ label: string; before: string; after: string }>) {
  return (
    <tr data-changed={before === after ? undefined : 'true'}>
      <td data-label="項目">{label}</td>
      <td data-label="修改前">{before}</td>
      <td data-label="修改後">{before === after ? after : <strong>{after}</strong>}</td>
    </tr>
  )
}
