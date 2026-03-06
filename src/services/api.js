/**
 * API Client — thin HTTP wrapper with JWT auth
 *
 * Base URL: http://localhost:8081/api   (configurable via VITE_API_URL)
 * Auth: Bearer token stored in localStorage
 * Refresh: automatic silent refresh on 401
 */

const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8081/api'

// ── token helpers ───────────────────────────────────────────────
function getAccessToken() {
  return localStorage.getItem('accessToken')
}
function getRefreshToken() {
  return localStorage.getItem('refreshToken')
}
export function setTokens(access, refresh) {
  if (access) localStorage.setItem('accessToken', access)
  if (refresh) localStorage.setItem('refreshToken', refresh)
}
export function clearTokens() {
  localStorage.removeItem('accessToken')
  localStorage.removeItem('refreshToken')
}

// ── refresh lock (prevent parallel refresh calls) ───────────────
let refreshPromise = null

async function refreshAccessToken() {
  if (refreshPromise) return refreshPromise

  refreshPromise = (async () => {
    const rt = getRefreshToken()
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

  const buildHeaders = () => {
    const h = { ...headers }
    const token = getAccessToken()
    if (token) h['Authorization'] = `Bearer ${token}`
    if (body && !(body instanceof FormData)) {
      h['Content-Type'] = 'application/json'
    }
    return h
  }

  let res = await fetch(url, {
    method,
    headers: buildHeaders(),
    body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
    ...rest,
  })

  // automatic retry on 401
  if (res.status === 401 && getRefreshToken()) {
    try {
      await refreshAccessToken()
      res = await fetch(url, {
        method,
        headers: buildHeaders(),
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
  delete: (path, body) => request(path, { method: 'DELETE', body }).then(parseResponse),
  /** Raw fetch — caller handles response (e.g. PDF blob) */
  raw: (path, options) => request(path, options),
}

export default api

