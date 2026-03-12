import { useState } from 'react'
import { Outlet, useNavigate } from 'react-router-dom'
import Header from './Header'
import BottomTabBar from './BottomTabBar'
import { useAuth } from '@/contexts/AuthContext'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Separator } from '@/components/ui/separator'
import {
  X, Calendar, Mail, Building2, TestTube, Pill, CreditCard,
  Shield, DollarSign, FileText, Settings, LogOut, ChevronRight,
  HelpCircle, User, FolderOpen, ClipboardList
} from 'lucide-react'

const menuSections = [
  {
    id: 'find-care', title: 'Find Care', items: [
      { path: '/appointments/schedule', title: 'Schedule an Appointment', icon: Calendar },
      { path: '/care-team', title: 'View Care Team', icon: User },
    ]
  },
  {
    id: 'communication', title: 'Communication', items: [
      { path: '/messages', title: 'Messages', icon: Mail },
      { path: '/messages/new', title: 'Ask a Question', icon: HelpCircle },
    ]
  },
  {
    id: 'my-record', title: 'My Record', items: [
      { path: '/visits', title: 'Visits', icon: Building2 },
      { path: '/lab-results', title: 'Test Results', icon: TestTube },
      { path: '/medications', title: 'Medications', icon: Pill },
      { path: '/profile', title: 'Health Summary', icon: FileText },
      { path: '/documents', title: 'Documents', icon: FolderOpen },
    ]
  },
  {
    id: 'billing', title: 'Billing', items: [
      { path: '/billing', title: 'Billing', icon: CreditCard },
      { path: '/billing', title: 'Insurance Summary', icon: Shield },
      { path: '/billing', title: 'Estimates', icon: DollarSign },
    ]
  },
  {
    id: 'forms', title: 'Forms & Consents', items: [
      { path: '/documents/consents', title: 'Data Sharing Consents', icon: Shield },
      { path: '/documents', title: 'All Documents', icon: ClipboardList },
    ]
  },
]

export default function AppLayout() {
  const [showMenu, setShowMenu] = useState(false)
  const navigate = useNavigate()
  const { logout } = useAuth()

  const handleNav = (path) => {
    setShowMenu(false)
    navigate(path)
  }

  const handleLogout = () => {
    setShowMenu(false)
    logout()
    navigate('/login')
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <Header onMenuToggle={() => setShowMenu(true)} />

      {/* Slide-over menu */}
      {showMenu && (
        <div className="fixed inset-0 bg-black/50 z-[60]" onClick={() => setShowMenu(false)}>
          <div
            className="bg-white w-80 h-full overflow-y-auto animate-in slide-in-from-left"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="p-4 border-b">
              <div className="flex items-center justify-between">
                <h2 className="text-lg font-semibold text-green-700">Menu</h2>
                <Button variant="ghost" size="sm" onClick={() => setShowMenu(false)}>
                  <X className="h-5 w-5" />
                </Button>
              </div>
              <div className="mt-3">
                <Input placeholder="Search the menu" className="w-full" />
              </div>
            </div>

            <div className="p-4 space-y-5">
              {menuSections.map((section) => (
                <div key={section.id}>
                  <h3 className="font-semibold text-gray-800 bg-green-100 px-3 py-2 rounded mb-2">
                    {section.title}
                  </h3>
                  <div className="space-y-1">
                    {section.items.map((item, idx) => (
                      <Button
                        key={idx}
                        variant="ghost"
                        className="w-full justify-between text-left"
                        onClick={() => handleNav(item.path)}
                      >
                        <div className="flex items-center space-x-3">
                          <item.icon className="h-5 w-5 text-green-600" />
                          <span>{item.title}</span>
                        </div>
                        <ChevronRight className="h-4 w-4 text-gray-400" />
                      </Button>
                    ))}
                  </div>
                </div>
              ))}

              <Separator />

              <Button
                variant="ghost"
                className="w-full justify-between text-left"
                onClick={() => handleNav('/profile')}
              >
                <div className="flex items-center space-x-3">
                  <Settings className="h-5 w-5 text-green-600" />
                  <span>Settings</span>
                </div>
                <ChevronRight className="h-4 w-4 text-gray-400" />
              </Button>
              <Button
                variant="ghost"
                className="w-full justify-between text-left text-red-600 hover:text-red-700"
                onClick={handleLogout}
              >
                <div className="flex items-center space-x-3">
                  <LogOut className="h-5 w-5" />
                  <span>Log Out</span>
                </div>
                <ChevronRight className="h-4 w-4 text-gray-400" />
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Page content */}
      <main className="max-w-lg mx-auto p-4 pb-24">
        <Outlet />
      </main>

      <BottomTabBar />
    </div>
  )
}

