/**
 * API Client — thin HTTP wrapper with JWT auth
 *
 * Base URL: Relative "/api" in production (nginx reverse-proxies to backend),
 *           or http://localhost:8081/api for local development (via .env).
 * Auth: Bearer token stored via secureStorage (Capacitor Preferences
 *       on native, localStorage on web)
 * Refresh: automatic silent refresh on 401
 */
import secureStorage from './secureStorage'

// When VITE_API_URL is explicitly set to "" (Docker build), use relative "/api"
// so nginx can reverse-proxy /api/* to the backend service.
// Only fall back to the full localhost URL when the variable is completely
// absent (local dev without .env override).
const API_BASE = import.meta.env.VITE_API_URL || '/api'

// ── token helpers (async — backed by secureStorage) ─────────────
export async function getAccessToken() {
  return secureStorage.getItem('accessToken')
}
export async function getRefreshToken() {
  return secureStorage.getItem('refreshToken')
}
export async function setTokens(access, refresh) {
  if (access) await secureStorage.setItem('accessToken', access)
  if (refresh) await secureStorage.setItem('refreshToken', refresh)
}
export async function clearTokens() {
  await secureStorage.removeItem('accessToken')
  await secureStorage.removeItem('refreshToken')
}

// ── refresh lock (prevent parallel refresh calls) ───────────────
let refreshPromise = null

async function refreshAccessToken() {
  if (refreshPromise) return refreshPromise

  refreshPromise = (async () => {
    const rt = await getRefreshToken()
    if (!rt) throw new Error('No refresh token')

    const res = await fetch(`${API_BASE}/auth/token/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: rt }),
    })

    if (!res.ok) {
      clearTokens()
      throw new Error('Refresh failed')
    }

    const data = await res.json()
    setTokens(data.accessToken ?? data.token, data.refreshToken)
    return data.accessToken ?? data.token
  })()

  try {
    return await refreshPromise
  } finally {
    refreshPromise = null
  }
}

// ── core request function ───────────────────────────────────────
async function request(path, options = {}) {
  const { body, method = 'GET', headers = {}, params, ...rest } = options

  let url = `${API_BASE}${path}`
  if (params) {
    const qs = new URLSearchParams(params).toString()
    url += `?${qs}`
  }

  const buildHeaders = async () => {
    const h = { ...headers }
    const token = await getAccessToken()
    if (token) h['Authorization'] = `Bearer ${token}`
    if (body && !(body instanceof FormData)) {
      h['Content-Type'] = 'application/json'
    }
    return h
  }

  let res = await fetch(url, {
    method,
    headers: await buildHeaders(),
    body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
    ...rest,
  })

  // automatic retry on 401
  if (res.status === 401 && (await getRefreshToken())) {
    try {
      await refreshAccessToken()
      res = await fetch(url, {
        method,
        headers: await buildHeaders(),
        body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
        ...rest,
      })
    } catch {
      // refresh failed — caller handles auth redirect
    }
  }

  return res
}

// ── convenience wrappers ────────────────────────────────────────
async function parseResponse(res) {
  if (res.status === 204) return null
  const contentType = res.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    const json = await res.json()
    if (!res.ok) {
      const err = new Error(json.message || json.error || `HTTP ${res.status}`)
      err.status = res.status
      err.body = json
      throw err
    }
    // unwrap ApiResponseWrapper if present
    return json.data !== undefined ? json.data : json
  }
  if (!res.ok) {
    const err = new Error(`HTTP ${res.status}`)
    err.status = res.status
    throw err
  }
  return res
}

export const api = {
  get: (path, params) => request(path, { method: 'GET', params }).then(parseResponse),
  post: (path, body) => request(path, { method: 'POST', body }).then(parseResponse),
  put: (path, body) => request(path, { method: 'PUT', body }).then(parseResponse),
  patch: (path, body) => request(path, { method: 'PATCH', body }).then(parseResponse),
  delete: (path, body, options = {}) => request(path, { method: 'DELETE', body, ...options }).then(parseResponse),
  /** Raw fetch — caller handles response (e.g. PDF blob) */
  raw: (path, options) => request(path, options),
}

export default api

