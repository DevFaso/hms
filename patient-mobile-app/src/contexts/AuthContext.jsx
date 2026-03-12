import { createContext, useContext, useState, useCallback, useEffect } from 'react'
import authService from '@/services/authService'
import portalService from '@/services/portalService'
import { clearTokens, getAccessToken } from '@/services/api'
import { patient as mockPatient } from '@/data/patient'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [loading, setLoading] = useState(true)

  // Restore session from secure storage on mount
  useEffect(() => {
    ;(async () => {
      try {
        const token = await getAccessToken()
        if (token) {
          const profile = await portalService.getProfile()
          setUser(profile)
        }
      } catch {
        await clearTokens()
      } finally {
        setLoading(false)
      }
    })()
  }, [])

  const login = useCallback(async (username, password) => {
    try {
      const data = await authService.login({ username, password })
      // Fetch profile after login
      const profile = await portalService.getProfile()
      setUser(profile)
      return { success: true, data }
    } catch (err) {
      // Fallback to mock data if backend is unreachable (dev mode)
      if (import.meta.env.DEV) {
        console.warn('API unreachable — using mock login')
        setUser({ ...mockPatient, username })
        return { success: true, mock: true }
      }
      throw err
    }
  }, [])

  const logout = useCallback(async () => {
    try {
      await authService.logout()
    } catch {
      // silent — clear local state regardless
    }
    await clearTokens()
    setUser(null)
  }, [])

  return (
    <AuthContext.Provider value={{ user, isLoggedIn: !!user, loading, login, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}
