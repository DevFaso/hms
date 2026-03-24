import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Mail, ChevronRight, Search, Plus } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import chatService from '@/services/chatService'
import useApiData from '@/hooks/useApiData'
import { useState, useMemo } from 'react'

export default function MessagesPage() {
  const navigate = useNavigate()
  const { user } = useAuth()
  const [search, setSearch] = useState('')

  const { data: raw } = useApiData(
    () => user?.id ? chatService.getConversations(user.id) : Promise.resolve(null),
    [],
    [user?.id],
  )

  const conversations = useMemo(() => {
    const list = (raw || []).map((c) => {
      if (c.recipientId) return c
      return {
        recipientId: c.id || c.recipientId,
        recipientName: c.recipientName || c.name || 'Unknown',
        recipientRole: c.recipientRole || '',
        lastMessage: c.lastMessage || c.content || '',
        lastMessageDate: c.lastMessageDate || c.updatedAt || '',
        unreadCount: c.unreadCount ?? 0,
      }
    })
    if (!search.trim()) return list
    const q = search.toLowerCase()
    return list.filter(
      (c) => c.recipientName.toLowerCase().includes(q) || c.lastMessage.toLowerCase().includes(q),
    )
  }, [raw, search])

  const formatDate = (iso) => {
    if (!iso) return ''
    const d = new Date(iso)
    const now = new Date()
    const diff = now - d
    if (diff < 86400000) return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    if (diff < 604800000) return d.toLocaleDateString([], { weekday: 'short' })
    return d.toLocaleDateString([], { month: 'short', day: 'numeric' })
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-gray-800">Messages</h2>
        <Button
          size="sm"
          className="bg-blue-700 hover:bg-blue-800"
          onClick={() => navigate('/messages/new')}
        >
          <Plus className="h-4 w-4 mr-1" />
          New
        </Button>
      </div>

      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" />
        <Input
          placeholder="Search messages…"
          className="pl-9"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>

      <div className="space-y-2">
        {conversations.map((c) => (
          <Card
            key={c.recipientId}
            className={`cursor-pointer hover:shadow-md transition-shadow active:scale-[0.99] ${
              c.unreadCount > 0 ? 'border-l-4 border-l-blue-500' : ''
            }`}
            onClick={() => navigate(`/messages/${c.recipientId}`)}
          >
            <CardContent className="p-4">
              <div className="flex items-start space-x-3">
                <div className="w-10 h-10 bg-blue-100 rounded-full flex items-center justify-center shrink-0">
                  <span className="text-blue-700 font-semibold text-sm">
                    {c.recipientName?.split(' ').map((w) => w[0]).join('').slice(0, 2)}
                  </span>
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center justify-between mb-0.5">
                    <h4 className={`text-sm truncate ${c.unreadCount > 0 ? 'font-bold' : 'font-medium'} text-gray-800`}>
                      {c.recipientName}
                    </h4>
                    <span className="text-xs text-gray-400 shrink-0 ml-2">
                      {formatDate(c.lastMessageDate)}
                    </span>
                  </div>
                  {c.recipientRole && (
                    <p className="text-xs text-gray-500 mb-0.5">{c.recipientRole}</p>
                  )}
                  <div className="flex items-center justify-between">
                    <p className="text-sm text-gray-500 truncate">{c.lastMessage}</p>
                    {c.unreadCount > 0 && (
                      <Badge className="bg-blue-600 text-white text-xs ml-2 shrink-0">
                        {c.unreadCount}
                      </Badge>
                    )}
                  </div>
                </div>
                <ChevronRight className="h-4 w-4 text-gray-300 shrink-0 mt-3" />
              </div>
            </CardContent>
          </Card>
        ))}

        {conversations.length === 0 && (
          <div className="text-center py-12">
            <Mail className="h-12 w-12 text-gray-300 mx-auto mb-3" />
            <p className="text-gray-500">No messages yet</p>
          </div>
        )}
      </div>
    </div>
  )
}
