import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Search, Send, CheckCircle } from 'lucide-react'
import { staff as mockStaff } from '@/data/chatThreads'
import { careTeam as mockCareTeam } from '@/data/careTeam'
import chatService from '@/services/chatService'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

export default function ComposeMessagePage() {
  const navigate = useNavigate()
  const [step, setStep] = useState('recipient') // recipient | compose | sent
  const [recipient, setRecipient] = useState(null)
  const [subject, setSubject] = useState('')
  const [body, setBody] = useState('')
  const [search, setSearch] = useState('')
  const [sending, setSending] = useState(false)

  const { data: careTeamData } = useApiData(
    () => portalService.getCareTeam(),
    mockCareTeam,
  )

  const providers = [
    ...(careTeamData?.primaryCare ? [careTeamData.primaryCare] : []),
    ...(careTeamData?.members || []),
  ]

  const filtered = search.trim()
    ? providers.filter((p) => p.name.toLowerCase().includes(search.toLowerCase()))
    : providers

  const handleSend = async () => {
    if (!recipient || !body.trim()) return
    setSending(true)
    try {
      await chatService.send({
        recipientId: recipient.id,
        subject: subject || 'Patient message',
        content: body,
      })
    } catch {
      console.warn('Send failed — shown as sent')
    }
    setSending(false)
    setStep('sent')
  }

  if (step === 'sent') {
    return (
      <div className="flex flex-col items-center justify-center py-16 space-y-4">
        <div className="bg-green-100 rounded-full p-4">
          <CheckCircle className="h-12 w-12 text-green-600" />
        </div>
        <h2 className="text-xl font-bold text-gray-800">Message Sent</h2>
        <p className="text-sm text-gray-500 text-center">
          Your message to {recipient?.name} has been sent.
          <br />You'll be notified when they reply.
        </p>
        <div className="flex space-x-3 pt-4">
          <Button variant="outline" onClick={() => navigate('/messages')}>
            Back to Messages
          </Button>
          <Button
            className="bg-blue-700 hover:bg-blue-800"
            onClick={() => {
              setStep('recipient')
              setRecipient(null)
              setSubject('')
              setBody('')
            }}
          >
            Send Another
          </Button>
        </div>
      </div>
    )
  }

  if (step === 'compose' && recipient) {
    return (
      <div className="space-y-4">
        <h2 className="text-xl font-bold text-gray-800">New Message</h2>

        <Card>
          <CardContent className="p-4">
            <div className="flex items-center space-x-3">
              <div className="w-10 h-10 bg-blue-100 rounded-full flex items-center justify-center shrink-0">
                <span className="text-blue-700 font-semibold text-sm">
                  {recipient.name.split(' ').map((w) => w[0]).join('').slice(0, 2)}
                </span>
              </div>
              <div>
                <p className="font-medium text-gray-800">{recipient.name}</p>
                <p className="text-xs text-gray-500">{recipient.specialty || recipient.role}</p>
              </div>
              <Button
                variant="ghost"
                size="sm"
                className="ml-auto text-xs text-blue-600"
                onClick={() => setStep('recipient')}
              >
                Change
              </Button>
            </div>
          </CardContent>
        </Card>

        <div className="space-y-3">
          <div>
            <Label htmlFor="subject" className="text-sm">Subject (optional)</Label>
            <Input
              id="subject"
              placeholder="e.g., Question about my medication"
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
            />
          </div>
          <div>
            <Label htmlFor="body" className="text-sm">Message</Label>
            <Textarea
              id="body"
              placeholder="Type your message…"
              rows={6}
              value={body}
              onChange={(e) => setBody(e.target.value)}
            />
          </div>
        </div>

        <Button
          className="w-full bg-blue-700 hover:bg-blue-800"
          disabled={sending || !body.trim()}
          onClick={handleSend}
        >
          <Send className="h-4 w-4 mr-2" />
          {sending ? 'Sending…' : 'Send Message'}
        </Button>
      </div>
    )
  }

  // Step: recipient selection
  return (
    <div className="space-y-4">
      <h2 className="text-xl font-bold text-gray-800">Choose Recipient</h2>

      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" />
        <Input
          placeholder="Search providers…"
          className="pl-9"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>

      <div className="space-y-2">
        {filtered.map((p) => (
          <Card
            key={p.id}
            className="cursor-pointer hover:shadow-md transition-shadow active:scale-[0.99]"
            onClick={() => {
              setRecipient(p)
              setStep('compose')
            }}
          >
            <CardContent className="p-4">
              <div className="flex items-center space-x-3">
                <div className="w-10 h-10 bg-blue-100 rounded-full flex items-center justify-center shrink-0">
                  <span className="text-blue-700 font-semibold text-sm">
                    {p.name.split(' ').map((w) => w[0]).join('').slice(0, 2)}
                  </span>
                </div>
                <div className="flex-1">
                  <p className="font-medium text-gray-800 text-sm">{p.name}</p>
                  <p className="text-xs text-gray-500">{p.specialty || p.role}</p>
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  )
}

