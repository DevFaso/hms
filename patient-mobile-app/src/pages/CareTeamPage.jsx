import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Phone, Mail, MapPin, Star, MessageSquare } from 'lucide-react'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

function ProviderCard({ provider, isPrimary = false, onMessage }) {
  return (
    <Card className={`hover:shadow-md transition-shadow ${isPrimary ? 'border-l-4 border-l-blue-600' : ''}`}>
      <CardContent className="p-4">
        <div className="flex items-start space-x-3">
          <div className={`w-12 h-12 rounded-full flex items-center justify-center shrink-0 ${isPrimary ? 'bg-blue-600' : 'bg-blue-100'}`}>
            <span className={`font-bold text-sm ${isPrimary ? 'text-white' : 'text-blue-700'}`}>
              {provider.name?.split(' ').map((w) => w[0]).join('').slice(0, 2)}
            </span>
          </div>
          <div className="flex-1 min-w-0">
            <div className="flex items-center space-x-2 mb-1">
              <h4 className="font-semibold text-gray-800 text-sm">{provider.name}</h4>
              {isPrimary && (
                <Badge className="bg-blue-100 text-blue-700 text-xs">
                  <Star className="h-3 w-3 mr-0.5" />
                  PCP
                </Badge>
              )}
            </div>
            <p className="text-xs text-gray-500">{provider.specialty}</p>
            <p className="text-xs text-gray-500">{provider.role}</p>
            {provider.facility && (
              <div className="flex items-center text-xs text-gray-400 mt-1">
                <MapPin className="h-3 w-3 mr-1 shrink-0" />
                <span className="truncate">{provider.facility}</span>
              </div>
            )}

            <div className="flex items-center space-x-2 mt-3">
              <Button
                size="sm"
                variant="outline"
                className="text-xs h-8"
                onClick={() => onMessage(provider.id)}
              >
                <MessageSquare className="h-3 w-3 mr-1" />
                Message
              </Button>
              {provider.phone && (
                <Button size="sm" variant="outline" className="text-xs h-8" asChild>
                  <a href={`tel:${provider.phone}`}>
                    <Phone className="h-3 w-3 mr-1" />
                    Call
                  </a>
                </Button>
              )}
              {provider.email && (
                <Button size="sm" variant="outline" className="text-xs h-8" asChild>
                  <a href={`mailto:${provider.email}`}>
                    <Mail className="h-3 w-3 mr-1" />
                    Email
                  </a>
                </Button>
              )}
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

export default function CareTeamPage() {
  const navigate = useNavigate()
  const { data: raw } = useApiData(
    () => portalService.getCareTeam(),
    null,
  )

  let allMembers = []
  if (Array.isArray(raw?.members)) {
    allMembers = raw.members
  } else if (Array.isArray(raw)) {
    allMembers = raw
  }
  const pcp = allMembers.find((m) => m.isPrimary) || null
  const members = allMembers.filter((m) => !m.isPrimary)
  const handleMessage = (id) => navigate(`/messages/${id}`)

  return (
    <div className="space-y-5">
      <h2 className="text-xl font-bold text-gray-800">My Care Team</h2>

      {pcp && (
        <section className="space-y-2">
          <h3 className="text-sm font-semibold text-gray-500 uppercase tracking-wide">
            Primary Care Provider
          </h3>
          <ProviderCard provider={pcp} isPrimary onMessage={handleMessage} />
        </section>
      )}

      <section className="space-y-2">
        <h3 className="text-sm font-semibold text-gray-500 uppercase tracking-wide">
          Care Team Members
        </h3>
        {members.map((m) => (
          <ProviderCard key={m.id} provider={m} onMessage={handleMessage} />
        ))}
        {members.length === 0 && (
          <p className="text-sm text-gray-400 text-center py-6">No additional care team members</p>
        )}
      </section>
    </div>
  )
}
