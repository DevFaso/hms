import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/contexts/AuthContext'
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
  const { login } = useAuth()
  const navigate = useNavigate()

  const handleFaceIdLogin = () => {
    setTimeout(() => {
      login('tiego')
      navigate('/dashboard', { replace: true })
    }, 800)
  }

  const handleUsernameLogin = (e) => {
    e.preventDefault()
    if (username && password) {
      login(username)
      navigate('/dashboard', { replace: true })
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-blue-600 to-blue-800 relative overflow-hidden">
      {/* City skyline background */}
      <div className="absolute bottom-0 left-0 right-0 h-64 opacity-20">
        <svg viewBox="0 0 800 200" className="w-full h-full">
          <rect x="50" y="120" width="40" height="80" fill="white" />
          <rect x="100" y="80" width="60" height="120" fill="white" />
          <rect x="170" y="100" width="45" height="100" fill="white" />
          <rect x="220" y="60" width="50" height="140" fill="white" />
          <rect x="280" y="90" width="35" height="110" fill="white" />
          <rect x="320" y="70" width="55" height="130" fill="white" />
          <rect x="380" y="110" width="40" height="90" fill="white" />
          <rect x="430" y="50" width="65" height="150" fill="white" />
          <rect x="500" y="85" width="45" height="115" fill="white" />
          <rect x="550" y="95" width="50" height="105" fill="white" />
          <rect x="610" y="75" width="40" height="125" fill="white" />
          <rect x="660" y="105" width="35" height="95" fill="white" />
          <rect x="700" y="65" width="50" height="135" fill="white" />
        </svg>
      </div>

      <div className="relative z-10 flex flex-col items-center justify-center min-h-screen p-4">
        {/* Header */}
        <div className="flex items-center justify-between w-full max-w-md mb-8">
          <div className="flex items-center space-x-2">
            <span className="text-white font-bold text-lg">MyChart</span>
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
              <h1 className="text-4xl font-bold text-orange-500 mb-2">NYC</h1>
              <h2 className="text-2xl font-bold text-blue-700 leading-tight">
                HEALTH<span className="text-blue-500">+</span><br />
                HOSPITALS
              </h2>
            </div>

            {!showUsernameLogin ? (
              <div className="space-y-6">
                <Button
                  onClick={handleFaceIdLogin}
                  className="w-full bg-blue-700 hover:bg-blue-800 text-white py-4 text-lg"
                >
                  <Fingerprint className="mr-3 h-6 w-6" />
                  Log in with Face ID
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
                <Button type="submit" className="w-full bg-blue-700 hover:bg-blue-800">
                  Log In
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  className="w-full"
                  onClick={() => setShowUsernameLogin(false)}
                >
                  Back to Face ID
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
          MyChart®, Epic Systems Corporation, © 1999 - 2026
        </div>
      </div>
    </div>
  )
}

