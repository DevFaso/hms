import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/contexts/AuthContext'
import biometricAuth from '@/services/biometricAuth'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Fingerprint, HelpCircle, UserPlus, AlertCircle, ChevronRight
} from 'lucide-react'

export default function LoginPage() {
  const [showUsernameLogin, setShowUsernameLogin] = useState(false)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loggingIn, setLoggingIn] = useState(false)
  const [biometricAvailable, setBiometricAvailable] = useState(false)
  const [biometricLabel, setBiometricLabel] = useState('Biometric')
  const { login } = useAuth()
  const navigate = useNavigate()

  // Check biometric availability on mount
  useEffect(() => {
    biometricAuth.isAvailable().then(({ available, biometryType }) => {
      setBiometricAvailable(available)
      if (biometryType) setBiometricLabel(biometryType)
    })
  }, [])

  const handleBiometricLogin = async () => {
    setLoggingIn(true)
    setError('')
    try {
      // 1. Prompt biometric verification
      await biometricAuth.authenticate(`Log in with ${biometricLabel}`)
      // 2. Retrieve stored credentials from OS keychain
      const creds = await biometricAuth.getCredentials()
      if (!creds) {
        setError('No saved credentials. Please log in with username first.')
        setShowUsernameLogin(true)
        return
      }
      // 3. Authenticate with backend using stored credentials
      await login(creds.username, creds.password)
      navigate('/dashboard', { replace: true })
    } catch (err) {
      if (err?.message?.includes('cancel') || err?.code === 'BIOMETRIC_DISMISSED') {
        // user dismissed — don't show error
        return
      }
      setError(err.message || 'Biometric login failed')
    } finally {
      setLoggingIn(false)
    }
  }

  const handleUsernameLogin = async (e) => {
    e.preventDefault()
    if (!username || !password) return
    setLoggingIn(true)
    setError('')
    try {
      await login(username, password)
      // Store credentials for future biometric logins
      if (biometricAvailable) {
        await biometricAuth.storeCredentials(username, password)
      }
      navigate('/dashboard', { replace: true })
    } catch (err) {
      setError(err.message || 'Invalid username or password')
    } finally {
      setLoggingIn(false)
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-blue-600 to-blue-800 relative overflow-hidden">
      {/* Burkina Faso coat of arms background */}
      <div className="absolute inset-0 flex items-center justify-center pointer-events-none" style={{ opacity: 0.15 }}>
        <img
          src="/images/bf-coat-of-arms.svg"
          alt=""
          className="w-96 h-96 object-contain"
          aria-hidden="true"
        />
      </div>

      <div className="relative z-10 flex flex-col items-center justify-center min-h-screen p-4">
        {/* Header */}
        <div className="flex items-center justify-between w-full max-w-md mb-8">
          <div className="flex items-center space-x-2">
            <span className="text-white font-bold text-lg">PatientChart</span>
            <span className="text-red-500 font-bold text-lg italic">Epic</span>
          </div>
          <Button variant="ghost" className="text-blue-300 hover:text-white">
            Edit organizations
          </Button>
        </div>

        {/* Main login card */}
        <Card className="w-full max-w-md bg-white/95 backdrop-blur-sm">
          <CardContent className="p-8">
            {/* Hospital branding */}
            <div className="text-center mb-8">
              <h1 className="text-4xl font-bold text-orange-500 mb-2">BF</h1>
              <h2 className="text-2xl font-bold text-blue-700 leading-tight">
                HEALTH<span className="text-blue-500">+</span><br />
                HOSPITALS
              </h2>
            </div>

            {!showUsernameLogin ? (
              <div className="space-y-6">
                {error && (
                  <div className="bg-red-50 text-red-700 text-sm rounded-lg p-3 text-center">
                    {error}
                  </div>
                )}
                <Button
                  onClick={handleBiometricLogin}
                  disabled={loggingIn}
                  className="w-full bg-blue-700 hover:bg-blue-800 text-white py-4 text-lg"
                >
                  <Fingerprint className="mr-3 h-6 w-6" />
                  {biometricAvailable ? `Log in with ${biometricLabel}` : 'Log in with Biometric'}
                </Button>

                <div className="text-center">
                  <Button
                    variant="link"
                    className="text-blue-600 hover:text-blue-800"
                    onClick={() => setShowUsernameLogin(true)}
                  >
                    Or log in with username and password
                  </Button>
                </div>

                <div className="flex justify-between items-center pt-4">
                  <Button variant="ghost" className="flex flex-col items-center space-y-1 text-blue-600">
                    <HelpCircle className="h-6 w-6" />
                    <span className="text-sm">Need help?</span>
                  </Button>
                  <Button variant="ghost" className="flex flex-col items-center space-y-1 text-blue-600">
                    <UserPlus className="h-6 w-6" />
                    <span className="text-sm">Sign up</span>
                  </Button>
                </div>
              </div>
            ) : (
              <form onSubmit={handleUsernameLogin} className="space-y-4">
                {error && (
                  <div className="bg-red-50 text-red-700 text-sm rounded-lg p-3 text-center">
                    {error}
                  </div>
                )}
                <div className="space-y-2">
                  <Label htmlFor="username">Username</Label>
                  <Input
                    id="username"
                    type="text"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    placeholder="Enter your username"
                    required
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="password">Password</Label>
                  <Input
                    id="password"
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="Enter your password"
                    required
                  />
                </div>
                <Button type="submit" disabled={loggingIn} className="w-full bg-blue-700 hover:bg-blue-800">
                  {loggingIn ? 'Logging in…' : 'Log In'}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  className="w-full"
                  onClick={() => setShowUsernameLogin(false)}
                >
                  Back to {biometricAvailable ? biometricLabel : 'Biometric'} Login
                </Button>
              </form>
            )}

            <div className="mt-8 pt-6 border-t border-gray-200">
              <Button variant="ghost" className="w-full flex items-center justify-between text-gray-700 hover:text-gray-900">
                <div className="flex items-center">
                  <div className="bg-blue-700 rounded-full p-2 mr-3">
                    <AlertCircle className="h-4 w-4 text-white" />
                  </div>
                  <span>Get Help</span>
                </div>
                <ChevronRight className="h-4 w-4 text-gray-400" />
              </Button>
            </div>
          </CardContent>
        </Card>

        <div className="mt-8 text-center text-white/80 text-sm">
          PatientChart®, Epic Systems Corporation, © 1999 - 2026
        </div>
      </div>
    </div>
  )
}

