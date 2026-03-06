import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Mail, ChevronRight } from 'lucide-react'
import { messages } from '@/data/messages'

export default function MessagesPage() {
  return (
    <div className="space-y-6">
      <h2 className="text-xl font-bold text-gray-800">Messages</h2>

      <div className="space-y-3">
        {messages.map((msg) => (
          <Card
            key={msg.id}
            className={`cursor-pointer hover:shadow-md transition-shadow ${
              msg.unread ? 'border-l-4 border-l-blue-500' : ''
            }`}
          >
            <CardContent className="p-4">
              <div className="flex justify-between items-start">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center space-x-2 mb-1">
                    <h4 className={`text-sm ${msg.unread ? 'font-semibold' : 'font-medium'} text-gray-800`}>
                      {msg.from}
                    </h4>
                    {msg.unread && (
                      <Badge variant="secondary" className="bg-blue-100 text-blue-800 text-xs">
                        New
                      </Badge>
                    )}
                  </div>
                  <p className="text-sm text-gray-600 mb-1">{msg.subject}</p>
                  <p className="text-sm text-gray-500 truncate">{msg.preview}</p>
                </div>
                <div className="text-right shrink-0 ml-3">
                  <p className="text-xs text-gray-400">{msg.date}</p>
                  <ChevronRight className="h-4 w-4 text-gray-400 mt-1 ml-auto" />
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      <Button className="w-full bg-blue-700 hover:bg-blue-800">
        <Mail className="h-4 w-4 mr-2" />
        Compose New Message
      </Button>
    </div>
  )
}

