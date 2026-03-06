import { useNavigate, useLocation } from 'react-router-dom'
import { useAuth } from '@/contexts/AuthContext'
import { Button } from '@/components/ui/button'
import { ArrowLeft, Menu, User, Bell } from 'lucide-react'

export default function Header({ onMenuToggle }) {
  const navigate = useNavigate()
  const location = useLocation()
  const { user } = useAuth()
  const isDashboard = location.pathname === '/' || location.pathname === '/dashboard'

  return (
    <div className="bg-blue-700 text-white p-4 sticky top-0 z-50 safe-area-top">
      <div className="flex items-center justify-between max-w-lg mx-auto">
        <div className="flex items-center space-x-3">
          {!isDashboard ? (
            <Button
              variant="ghost"
              size="sm"
              className="text-white hover:bg-blue-600 p-1"
              onClick={() => navigate(-1)}
            >
              <ArrowLeft className="h-5 w-5" />
            </Button>
          ) : (
            <Button
              variant="ghost"
              size="sm"
              className="text-white hover:bg-blue-600 p-1"
              onClick={onMenuToggle}
            >
              <Menu className="h-5 w-5" />
            </Button>
          )}
          <div className="flex items-center space-x-2">
            <span className="text-sm font-medium">NYC HEALTH + HOSPITALS</span>
          </div>
        </div>
        <div className="flex items-center space-x-2">
          <Button
            variant="ghost"
            size="sm"
            className="text-white hover:bg-blue-600 p-1 relative"
            onClick={() => navigate('/notifications')}
          >
            <Bell className="h-5 w-5" />
            <span className="absolute -top-0.5 -right-0.5 bg-red-500 text-white text-xs rounded-full h-4 w-4 flex items-center justify-center">
              2
            </span>
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="p-0"
            onClick={() => navigate('/profile')}
          >
            <div className="w-8 h-8 bg-green-500 rounded-full flex items-center justify-center">
              <User className="h-4 w-4 text-white" />
            </div>
          </Button>
        </div>
      </div>
    </div>
  )
}

