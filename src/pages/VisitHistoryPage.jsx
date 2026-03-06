import { useNavigate } from 'react-router-dom'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Building2, ChevronRight, FileText, Calendar } from 'lucide-react'
import { encounters as mockEncounters } from '@/data/encounters'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

export default function VisitHistoryPage() {
  const navigate = useNavigate()
  const { data: raw } = useApiData(
    () => portalService.getEncounters(),
    mockEncounters,
  )

  const encounters = (raw || []).map((e) => {
    if (e.type) return e
    return {
      id: e.id,
      type: e.encounterType || 'Visit',
      date: e.startDate || e.date || '',
      provider: e.providerName || '',
      facility: e.facilityName || '',
      department: e.department || '',
      reason: e.chiefComplaint || e.reason || '',
      status: e.status || 'COMPLETED',
      diagnoses: e.diagnoses || [],
      hasSummary: !!e.hasSummary,
    }
  })

  const formatDate = (d) => {
    if (!d) return ''
    return new Date(d).toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' })
  }

  return (
    <div className="space-y-5">
      <h2 className="text-xl font-bold text-gray-800">Visit History</h2>

      <div className="space-y-3">
        {encounters.map((enc) => (
          <Card key={enc.id} className="hover:shadow-md transition-shadow">
            <CardContent className="p-4">
              <div className="flex items-start justify-between mb-2">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center space-x-2 mb-1">
                    <h4 className="font-semibold text-gray-800 text-sm">{enc.type}</h4>
                    <Badge variant="secondary" className="text-xs bg-green-100 text-green-700">
                      {enc.status}
                    </Badge>
                  </div>
                  <div className="flex items-center text-xs text-gray-500 mb-1">
                    <Calendar className="h-3 w-3 mr-1" />
                    {formatDate(enc.date)}
                  </div>
                  <p className="text-sm text-gray-600">{enc.provider}</p>
                  <div className="flex items-center text-xs text-gray-400 mt-0.5">
                    <Building2 className="h-3 w-3 mr-1 shrink-0" />
                    <span className="truncate">{enc.facility}</span>
                  </div>
                  {enc.reason && (
                    <p className="text-xs text-gray-500 mt-1">Reason: {enc.reason}</p>
                  )}
                  {enc.diagnoses?.length > 0 && (
                    <div className="flex flex-wrap gap-1 mt-2">
                      {enc.diagnoses.map((d, i) => (
                        <Badge key={i} variant="outline" className="text-xs">
                          {d}
                        </Badge>
                      ))}
                    </div>
                  )}
                </div>
              </div>

              {enc.hasSummary && (
                <Button
                  variant="outline"
                  size="sm"
                  className="w-full text-xs mt-2"
                  onClick={() => navigate(`/visits/${enc.id}/summary`)}
                >
                  <FileText className="h-3 w-3 mr-1" />
                  View After Visit Summary®
                  <ChevronRight className="h-3 w-3 ml-auto" />
                </Button>
              )}
            </CardContent>
          </Card>
        ))}

        {encounters.length === 0 && (
          <div className="text-center py-12">
            <Building2 className="h-12 w-12 text-gray-300 mx-auto mb-3" />
            <p className="text-gray-500">No visits found</p>
          </div>
        )}
      </div>
    </div>
  )
}

