import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import {
  Bell, Calendar, TestTube, CreditCard, Pill, Mail,
  ChevronRight, Check
} from 'lucide-react'
import { notifications as initialNotifications } from '@/data/notifications'

const iconMap = {
  'lab-result': TestTube,
  billing: CreditCard,
  appointment: Calendar,
  medication: Pill,
  message: Mail,
}
const colorMap = {
  'lab-result': 'bg-blue-500',
  billing: 'bg-green-500',
  appointment: 'bg-indigo-500',
  medication: 'bg-teal-500',
  message: 'bg-purple-500',
}

export default function NotificationsPage() {
  const navigate = useNavigate()
  const [notifs, setNotifs] = useState(initialNotifications)

  const unread = notifs.filter((n) => !n.read)
  const read = notifs.filter((n) => n.read)

  const markAllRead = () => {
    setNotifs((prev) => prev.map((n) => ({ ...n, read: true })))
  }

  const markRead = (id) => {
    setNotifs((prev) =>
      prev.map((n) => (n.id === id ? { ...n, read: true } : n))
    )
  }

  const renderNotif = (notif) => {
    const Icon = iconMap[notif.type] || Bell
    const color = colorMap[notif.type] || 'bg-gray-500'

    return (
      <Card
        key={notif.id}
        className={`hover:shadow-md transition-shadow cursor-pointer ${
          !notif.read ? 'border-l-4 border-l-blue-500 bg-blue-50/30' : ''
        }`}
        onClick={() => {
          markRead(notif.id)
          navigate(notif.actionLink)
        }}
      >
        <CardContent className="p-4">
          <div className="flex items-start space-x-3">
            <div className={`${color} rounded-full p-2 shrink-0`}>
              <Icon className="h-4 w-4 text-white" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="flex items-center justify-between mb-1">
                <h4 className={`text-sm ${!notif.read ? 'font-semibold' : 'font-medium'} text-gray-800`}>
                  {notif.title}
                </h4>
                {!notif.read && (
                  <Badge className="bg-blue-100 text-blue-800 text-xs">New</Badge>
                )}
              </div>
              <p className="text-sm text-gray-600 mb-1">{notif.message}</p>
              <div className="flex items-center justify-between">
                <span className="text-xs text-gray-400">
                  {notif.date} · {notif.time}
                </span>
                <Button
                  size="sm"
                  variant="ghost"
                  className="text-xs h-6 text-blue-600 hover:text-blue-800 p-0"
                  onClick={(e) => {
                    e.stopPropagation()
                    markRead(notif.id)
                    navigate(notif.actionLink)
                  }}
                >
                  {notif.actionLabel}
                  <ChevronRight className="h-3 w-3 ml-0.5" />
                </Button>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>
    )
  }

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-gray-800">Notifications</h2>
        {unread.length > 0 && (
          <Button
            variant="ghost"
            size="sm"
            className="text-xs text-blue-600"
            onClick={markAllRead}
          >
            <Check className="h-3 w-3 mr-1" />
            Mark all read
          </Button>
        )}
      </div>

      {/* Unread */}
      {unread.length > 0 && (
        <section className="space-y-3">
          <h3 className="text-sm font-semibold text-gray-500 uppercase tracking-wide">
            New ({unread.length})
          </h3>
          {unread.map(renderNotif)}
        </section>
      )}

      {/* Read */}
      {read.length > 0 && (
        <section className="space-y-3">
          <h3 className="text-sm font-semibold text-gray-500 uppercase tracking-wide">
            Earlier
          </h3>
          {read.map(renderNotif)}
        </section>
      )}

      {notifs.length === 0 && (
        <div className="text-center py-12">
          <Bell className="h-12 w-12 text-gray-300 mx-auto mb-3" />
          <p className="text-gray-500">No notifications</p>
        </div>
      )}
    </div>
  )
}

