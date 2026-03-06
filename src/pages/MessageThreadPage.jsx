import { useParams } from 'react-router-dom'
import { useState, useRef, useEffect, useMemo } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Send } from 'lucide-react'
import { chatThreads as mockThreads, conversations as mockConversations } from '@/data/chatThreads'
import { useAuth } from '@/contexts/AuthContext'
import chatService from '@/services/chatService'
import useApiData from '@/hooks/useApiData'

export default function MessageThreadPage() {
  const { recipientId } = useParams()
  const { user } = useAuth()
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const bottomRef = useRef(null)

  const convo = mockConversations.find((c) => c.recipientId === recipientId)
  const recipientName = convo?.recipientName || 'Provider'

  const { data: rawMessages, setData: setMessages } = useApiData(
    () => user?.id ? chatService.getHistory(user.id, recipientId) : Promise.resolve(null),
    mockThreads[recipientId] || [],
    [user?.id, recipientId],
  )

  const messages = useMemo(() => rawMessages || [], [rawMessages])

  // Mark read on mount
  useEffect(() => {
    if (user?.id && recipientId) {
      chatService.markRead(recipientId, user.id).catch(() => {})
    }
  }, [user?.id, recipientId])

  // Auto-scroll to bottom
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const handleSend = async () => {
    const text = input.trim()
    if (!text) return
    setInput('')
    setSending(true)

    const optimistic = {
      id: `tmp-${Date.now()}`,
      senderId: 'me',
      senderName: user?.firstName ? `${user.firstName} ${user.lastName}` : 'You',
      content: text,
      timestamp: new Date().toISOString(),
      read: true,
    }
    setMessages((prev) => [...(prev || []), optimistic])

    try {
      await chatService.send({
        recipientId,
        content: text,
      })
    } catch {
      console.warn('Send failed — message shown optimistically')
    } finally {
      setSending(false)
    }
  }

  const formatTime = (iso) => {
    if (!iso) return ''
    return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  }

  const formatDateSeparator = (iso) => {
    if (!iso) return ''
    const d = new Date(iso)
    const today = new Date()
    if (d.toDateString() === today.toDateString()) return 'Today'
    const yesterday = new Date(today)
    yesterday.setDate(yesterday.getDate() - 1)
    if (d.toDateString() === yesterday.toDateString()) return 'Yesterday'
    return d.toLocaleDateString([], { weekday: 'long', month: 'short', day: 'numeric' })
  }

  // Group messages by day
  let lastDate = ''

  return (
    <div className="flex flex-col h-[calc(100vh-12rem)]">
      {/* Header */}
      <div className="flex items-center space-x-3 pb-3 border-b mb-3">
        <div className="w-10 h-10 bg-blue-100 rounded-full flex items-center justify-center shrink-0">
          <span className="text-blue-700 font-semibold text-sm">
            {recipientName.split(' ').map((w) => w[0]).join('').slice(0, 2)}
          </span>
        </div>
        <div>
          <h3 className="font-semibold text-gray-800">{recipientName}</h3>
          {convo?.recipientRole && (
            <p className="text-xs text-gray-500">{convo.recipientRole}</p>
          )}
        </div>
      </div>

      {/* Messages */}
      <div className="flex-1 overflow-y-auto space-y-3 pr-1">
        {messages.map((msg) => {
          const isMe = msg.senderId === 'me' || msg.senderId === user?.id
          const msgDate = new Date(msg.timestamp).toDateString()
          let showDate = false
          if (msgDate !== lastDate) {
            showDate = true
            lastDate = msgDate
          }

          return (
            <div key={msg.id}>
              {showDate && (
                <div className="text-center my-3">
                  <span className="text-xs text-gray-400 bg-gray-100 px-3 py-1 rounded-full">
                    {formatDateSeparator(msg.timestamp)}
                  </span>
                </div>
              )}
              <div className={`flex ${isMe ? 'justify-end' : 'justify-start'}`}>
                <div
                  className={`max-w-[80%] rounded-2xl px-4 py-2.5 ${
                    isMe
                      ? 'bg-blue-700 text-white rounded-br-md'
                      : 'bg-gray-100 text-gray-800 rounded-bl-md'
                  }`}
                >
                  <p className="text-sm leading-relaxed">{msg.content}</p>
                  <p className={`text-xs mt-1 ${isMe ? 'text-blue-200' : 'text-gray-400'}`}>
                    {formatTime(msg.timestamp)}
                  </p>
                </div>
              </div>
            </div>
          )
        })}
        <div ref={bottomRef} />
      </div>

      {/* Input */}
      <div className="flex items-center space-x-2 pt-3 border-t mt-2">
        <Input
          placeholder="Type a message…"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && !e.shiftKey && handleSend()}
          className="flex-1"
        />
        <Button
          size="icon"
          className="bg-blue-700 hover:bg-blue-800 shrink-0"
          onClick={handleSend}
          disabled={sending || !input.trim()}
        >
          <Send className="h-4 w-4" />
        </Button>
      </div>
    </div>
  )
}

