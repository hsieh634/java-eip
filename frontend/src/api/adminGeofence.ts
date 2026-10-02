import { api } from './http'

export type GeofenceScope = 'CLOCK_IN' | 'CLOCK_OUT' | 'BOTH'

export interface ActorSummary {
  id: number
  employeeNo: string
  displayName: string
}

export interface GeofenceLimits {
  radiusMin: number
  radiusMax: number
  maxAccuracyMeters: number
}

/** 對應後端 SettingsResponse。latitude／longitude 為 null＝尚未設定公司中心（0 是合法座標）。 */
export interface GeofenceSettings {
  enabled: boolean
  latitude: number | null
  longitude: number | null
  radiusMeters: number
  scope: GeofenceScope
  version: number
  updatedAt: string | null
  updatedBy: ActorSummary | null
  limits: GeofenceLimits
}

export interface GeofenceValues {
  enabled: boolean
  latitude: number | null
  longitude: number | null
  radiusMeters: number
  scope: GeofenceScope
}

export interface GeofenceHistoryEntry {
  id: number
  version: number
  before: GeofenceValues
  after: GeofenceValues
  reason: string
  actor: ActorSummary | null
  occurredAt: string
}

/** expectedVersion：畫面載入時的版本；設定已被別人改過時後端回 409 SETTING_CHANGED，不會覆蓋。 */
export type UpdateGeofenceBody = GeofenceValues & { expectedVersion: number; reason: string }

const BASE = '/api/admin/geofence'

export const fetchGeofence = () => api<GeofenceSettings>(BASE)
export const updateGeofence = (body: UpdateGeofenceBody) => api<GeofenceSettings>(BASE, { method: 'PUT', body })
export const fetchGeofenceHistory = () => api<{ entries: GeofenceHistoryEntry[] }>(`${BASE}/history`).then((r) => r.entries)
