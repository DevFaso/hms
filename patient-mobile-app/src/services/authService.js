/**
 * Auth service — wraps /auth/* endpoints
 *
 * POST /auth/login           → { username, password } → tokens + user
 * POST /auth/logout           → invalidate session
 * POST /auth/token/refresh    → rotate tokens
 * POST /auth/verify-password  → screen-unlock check
 * POST /auth/register         → self-service signup
 */
import api, { setTokens, clearTokens } from './api'

const authService = {
  /**
   * @param {{ username: string, password: string }} creds
   * @returns {Promise<{ accessToken, refreshToken, user }>}
   */
  async login(creds) {
    const data = await api.post('/auth/login', creds)
    const accessToken = data.accessToken ?? data.token
    const refreshToken = data.refreshToken
    await setTokens(accessToken, refreshToken)
    return data
  },

  async logout() {
    try {
      await api.post('/auth/logout')
    } finally {
      await clearTokens()
    }
  },

  async verifyPassword(creds) {
    return api.post('/auth/verify-password', creds)
  },

  async register(payload) {
    return api.post('/auth/register', payload)
  },

  async requestPasswordReset(email) {
    return api.post('/auth/password/request-reset', { email })
  },

  async resetPassword(payload) {
    return api.post('/auth/password/reset-password', payload)
  },

  async resendVerification(email) {
    return api.post(`/auth/resend-verification?email=${encodeURIComponent(email)}`)
  },
}

export default authService

