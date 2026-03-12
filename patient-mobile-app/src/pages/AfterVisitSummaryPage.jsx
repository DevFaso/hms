import { useParams, useNavigate } from 'react-router-dom'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import {
  FileText, Pill, TestTube, Calendar, Heart,
  ClipboardList, ArrowRight, Download, Printer
} from 'lucide-react'
import { afterVisitSummaries as mockSummaries } from '@/data/afterVisitSummaries'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

export default function AfterVisitSummaryPage() {
  const { encounterId } = useParams()
  const navigate = useNavigate()

  const { data: allSummaries } = useApiData(
    () => portalService.getAfterVisitSummaries(),
    mockSummaries,
  )

  const summary = (allSummaries || []).find(
    (s) => s.encounterId === encounterId || s.id === encounterId,
  )

  if (!summary) {
    return (
      <div className="text-center py-12">
        <FileText className="h-12 w-12 text-gray-300 mx-auto mb-3" />
        <p className="text-gray-500">Summary not found</p>
        <Button variant="link" onClick={() => navigate('/visits')}>
          Back to visits
        </Button>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-bold text-gray-800">After Visit Summary®</h2>
        <div className="flex space-x-1">
          <Button variant="ghost" size="icon" className="h-8 w-8">
            <Download className="h-4 w-4" />
          </Button>
          <Button variant="ghost" size="icon" className="h-8 w-8">
            <Printer className="h-4 w-4" />
          </Button>
        </div>
      </div>

      {/* Visit info */}
      <Card className="bg-blue-50 border-blue-200">
        <CardContent className="p-4">
          <h3 className="font-semibold text-blue-800 mb-1">{summary.visitType}</h3>
          <p className="text-sm text-blue-700">{summary.provider}</p>
          <p className="text-sm text-blue-600">{summary.facility}</p>
          <p className="text-sm text-blue-600">
            {new Date(summary.date).toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' })}
          </p>
        </CardContent>
      </Card>

      {/* Diagnoses */}
      {summary.diagnoses?.length > 0 && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm flex items-center">
              <ClipboardList className="h-4 w-4 mr-2 text-purple-600" />
              Diagnoses
            </CardTitle>
          </CardHeader>
          <CardContent className="pt-0">
            <div className="space-y-1">
              {summary.diagnoses.map((d, i) => (
                <Badge key={i} variant="outline" className="text-xs mr-1 mb-1">{d}</Badge>
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      {/* Vitals */}
      {summary.vitals && Object.keys(summary.vitals).length > 0 && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm flex items-center">
              <Heart className="h-4 w-4 mr-2 text-red-500" />
              Vitals Recorded
            </CardTitle>
          </CardHeader>
          <CardContent className="pt-0">
            <div className="grid grid-cols-2 gap-2">
              {Object.entries(summary.vitals).map(([key, value]) => (
                <div key={key} className="bg-gray-50 rounded p-2">
                  <p className="text-xs text-gray-500 capitalize">
                    {key.replace(/([A-Z])/g, ' $1').trim()}
                  </p>
                  <p className="text-sm font-medium text-gray-800">{value}</p>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      {/* Instructions */}
      {summary.instructions?.length > 0 && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm flex items-center">
              <FileText className="h-4 w-4 mr-2 text-blue-600" />
              Instructions
            </CardTitle>
          </CardHeader>
          <CardContent className="pt-0">
            <ul className="space-y-2">
              {summary.instructions.map((inst, i) => (
                <li key={i} className="flex items-start text-sm text-gray-700">
                  <span className="text-blue-600 mr-2 mt-0.5">•</span>
                  {inst}
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}

      {/* Medications */}
      {summary.medications?.length > 0 && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm flex items-center">
              <Pill className="h-4 w-4 mr-2 text-teal-600" />
              Medications
            </CardTitle>
          </CardHeader>
          <CardContent className="pt-0">
            <div className="space-y-2">
              {summary.medications.map((med, i) => (
                <div key={i} className="flex items-center justify-between bg-gray-50 rounded p-2">
                  <div>
                    <p className="text-sm font-medium text-gray-800">{med.name}</p>
                    <p className="text-xs text-gray-500">{med.instructions}</p>
                  </div>
                  <Badge
                    className={`text-xs ${
                      med.action === 'New' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'
                    }`}
                  >
                    {med.action}
                  </Badge>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      {/* Labs Ordered */}
      {summary.labsOrdered?.length > 0 && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm flex items-center">
              <TestTube className="h-4 w-4 mr-2 text-purple-600" />
              Labs Ordered
            </CardTitle>
          </CardHeader>
          <CardContent className="pt-0">
            <ul className="space-y-1">
              {summary.labsOrdered.map((lab, i) => (
                <li key={i} className="text-sm text-gray-700 flex items-center">
                  <ArrowRight className="h-3 w-3 mr-2 text-gray-400" />
                  {lab}
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}

      {/* Follow-up & Referrals */}
      <Card>
        <CardContent className="p-4 space-y-3">
          {summary.followUp && (
            <div className="flex items-start space-x-2">
              <Calendar className="h-4 w-4 text-blue-600 mt-0.5 shrink-0" />
              <div>
                <p className="text-xs text-gray-500 font-medium">Follow-up</p>
                <p className="text-sm text-gray-800">{summary.followUp}</p>
              </div>
            </div>
          )}
          {summary.referrals?.length > 0 && (
            <>
              <Separator />
              <div>
                <p className="text-xs text-gray-500 font-medium mb-1">Referrals</p>
                {summary.referrals.map((r, i) => (
                  <p key={i} className="text-sm text-gray-800">{r}</p>
                ))}
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

