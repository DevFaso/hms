import { createContext, useContext, useState, useCallback, useEffect } from 'react'
import authService from '@/services/authService'
import portalService from '@/services/portalService'
import { clearTokens, getAccessToken } from '@/services/api'

const AuthContext = createContext(null)

function parseAllergies(raw) {
  if (Array.isArray(raw)) return raw
  if (typeof raw === 'string' && raw.length > 0) {
    return raw.split(',').map((a) => a.trim()).filter(Boolean)
  }
  return []
}

/**
 * Normalise the flat PatientProfileDTO from the backend into the
 * structured shape the UI pages expect.
 */
function normalizeProfile(p) {
  if (!p) return null
  return {
    // keep all raw fields as-is for pages that reference them directly
    ...p,
    // convenience aliases used by multiple pages
    firstName: p.firstName,
    lastName: p.lastName,
    dateOfBirth: p.dateOfBirth
      ? new Date(p.dateOfBirth).toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' })
      : '',
    gender: p.gender,
    bloodType: p.bloodType || 'Unknown',
    preferredLanguage: p.preferredLanguage || 'English',
    phone: p.phoneNumberPrimary || p.phone || '',
    email: p.email || '',
    memberSince: p.memberSince || '',
    primaryCareProvider: p.primaryCareProvider || '',
    facility: p.facility || '',
    lastVisit: p.lastVisit || '',
    // structured address
    address: {
      street: p.addressLine1 || '',
      apt: p.addressLine2 || '',
      city: p.city || '',
      state: p.state || '',
      zip: p.zipCode || '',
    },
    // allergies as array
    allergies: parseAllergies(p.allergies),
    // insurance
    insurance: {
      provider: p.insuranceProvider || '',
      plan: p.insurancePlan || '',
      memberId: p.insuranceMemberId || '',
      groupNumber: p.insuranceGroupNumber || '',
    },
    // emergency contact
    emergencyContact: {
      name: p.emergencyContactName || '',
      relationship: p.emergencyContactRelationship || '',
      phone: p.emergencyContactPhone || '',
    },
  }
}

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
          setUser(normalizeProfile(profile))
        }
      } catch {
        await clearTokens()
      } finally {
        setLoading(false)
      }
    })()
  }, [])

  const login = useCallback(async (username, password) => {
    const data = await authService.login({ username, password })
    // Fetch profile after login
    const profile = await portalService.getProfile()
    setUser(normalizeProfile(profile))
    return { success: true, data }
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
