import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/contexts/AuthContext'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import {
  Calendar, Mail, Building2, TestTube, Pill, CreditCard,
  ChevronRight, Bell
} from 'lucide-react'
import { notifications as mockNotifications } from '@/data/notifications'
import notificationService from '@/services/notificationService'
import useApiData from '@/hooks/useApiData'

const quickLinks = [
  { icon: Calendar, title: 'Appointments', path: '/appointments', color: 'bg-green-500' },
  { icon: Mail, title: 'Messages', path: '/messages', color: 'bg-blue-500' },
  { icon: Building2, title: 'Visits', path: '/appointments', color: 'bg-indigo-500' },
  { icon: TestTube, title: 'Test Results', path: '/lab-results', color: 'bg-purple-500' },
  { icon: Pill, title: 'Medications', path: '/medications', color: 'bg-teal-500' },
  { icon: CreditCard, title: 'Billing', path: '/billing', color: 'bg-orange-500' },
]

export default function DashboardPage() {
  const navigate = useNavigate()
  const { user } = useAuth()
  const { data: notifications } = useApiData(
    () => notificationService.getAll(),
    mockNotifications,
  )
  const unreadNotifications = (notifications || []).filter((n) => !n.read)

  return (
    <div className="space-y-6">
      {/* Welcome */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-800">
            Welcome, {user?.firstName || 'Patient'}!
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            {new Date().toLocaleDateString('en-US', {
              weekday: 'long', month: 'long', day: 'numeric',
            })}
          </p>
        </div>
        {unreadNotifications.length > 0 && (
          <Button
            variant="outline"
            size="sm"
            className="relative"
            onClick={() => navigate('/notifications')}
          >
            <Bell className="h-4 w-4 mr-1" />
            {unreadNotifications.length}
          </Button>
        )}
      </div>

      {/* Quick Access Grid */}
      <div className="grid grid-cols-3 gap-3">
        {quickLinks.map((item, i) => (
          <Card
            key={i}
            className="cursor-pointer hover:shadow-lg transition-all duration-200 active:scale-95"
            onClick={() => navigate(item.path)}
          >
            <CardContent className="p-4 text-center">
              <div className={`${item.color} w-12 h-12 rounded-xl flex items-center justify-center mx-auto mb-2`}>
                <item.icon className="h-6 w-6 text-white" />
              </div>
              <h3 className="text-xs font-medium text-gray-700">{item.title}</h3>
            </CardContent>
          </Card>
        ))}
      </div>

      {/* Notification Cards */}
      <div className="space-y-3">
        <h2 className="text-lg font-semibold text-gray-800">Recent Notifications</h2>

        {unreadNotifications.map((notif) => {
          const iconMap = {
            'lab-result': TestTube,
            billing: CreditCard,
            appointment: Calendar,
            medication: Pill,
            message: Mail,
          }
          const colorMap = {
            'lab-result': 'blue',
            billing: 'green',
            appointment: 'indigo',
            medication: 'teal',
            message: 'purple',
          }
          const Icon = iconMap[notif.type] || Bell
          const color = colorMap[notif.type] || 'gray'

          return (
            <Card key={notif.id} className={`border-l-4 border-l-${color}-500`}>
              <CardContent className="p-4">
                <div className="flex items-start space-x-3">
                  <div className={`bg-${color}-500 rounded-full p-2 shrink-0`}>
                    <Icon className="h-4 w-4 text-white" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between mb-1">
                      <h3 className="font-semibold text-gray-800 text-sm">{notif.title}</h3>
                      <Badge variant="secondary" className="text-xs bg-blue-100 text-blue-800">
                        New
                      </Badge>
                    </div>
                    <p className="text-sm text-gray-600 mb-2">{notif.message}</p>
                    <div className="flex items-center justify-between">
                      <span className="text-xs text-gray-400">{notif.date}</span>
                      <Button
                        size="sm"
                        variant="outline"
                        className="text-xs h-7"
                        onClick={() => navigate(notif.actionLink)}
                      >
                        {notif.actionLabel}
                        <ChevronRight className="h-3 w-3 ml-1" />
                      </Button>
                    </div>
                  </div>
                </div>
              </CardContent>
            </Card>
          )
        })}

        {/* Amount Due summary card */}
        <Card className="border-l-4 border-l-orange-500">
          <CardContent className="p-4">
            <div className="flex items-start space-x-3">
              <div className="bg-orange-500 rounded-full p-2 shrink-0">
                <CreditCard className="h-4 w-4 text-white" />
              </div>
              <div className="flex-1">
                <h3 className="font-semibold text-gray-800 text-sm mb-1">Amount Due</h3>
                <div className="flex items-center space-x-2 mb-2">
                  <div className="bg-blue-700 rounded p-1">
                    <Building2 className="h-3 w-3 text-white" />
                  </div>
                  <div>
                    <p className="text-xs font-medium text-gray-800">NYC Health + Hospitals</p>
                    <p className="text-xs text-gray-500">Physician Services</p>
                  </div>
                </div>
                <p className="text-xl font-bold text-gray-800 mb-3">$54.00</p>
                <div className="flex space-x-2">
                  <Button
                    size="sm"
                    className="bg-blue-700 hover:bg-blue-800 text-xs h-8"
                    onClick={() => navigate('/billing/pay')}
                  >
                    Pay now
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    className="text-xs h-8"
                    onClick={() => navigate('/billing')}
                  >
                    View details
                  </Button>
                </div>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}

