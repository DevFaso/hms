import { useState } from 'react'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Shield, Clock, CheckCircle, XCircle, Eye } from 'lucide-react'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

const TABS = ['Sharing Consents', 'Access Log']

export default function SharingPrivacyPage() {
  const [activeTab, setActiveTab] = useState('Sharing Consents')

  const { data: consents, refetch: refetchConsents } = useApiData(
    () => portalService.getConsents(),
    [],
  )
  const { data: accessLog } = useApiData(
    () => portalService.getAccessLog(),
    [],
  )

  const consentList = Array.isArray(consents) ? consents : []
  const logEntries = Array.isArray(accessLog) ? accessLog : []

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-bold text-gray-800">Sharing & Privacy</h2>

      {/* Tab bar */}
      <div className="flex gap-2">
        {TABS.map((tab) => (
          <Button
            key={tab}
            size="sm"
            variant={activeTab === tab ? 'default' : 'outline'}
            className={`text-xs h-8 ${
              activeTab === tab ? 'bg-green-700 hover:bg-green-800' : ''
            }`}
            onClick={() => setActiveTab(tab)}
          >
            {tab}
          </Button>
        ))}
      </div>

      {activeTab === 'Sharing Consents' && (
        <ConsentsTab consents={consentList} onRevoke={refetchConsents} />
      )}
      {activeTab === 'Access Log' && <AccessLogTab entries={logEntries} />}
    </div>
  )
}

/* ── Consents Tab ───────────────────────────────────────────────── */
function ConsentsTab({ consents, onRevoke }) {
  const handleRevoke = async (consent) => {
    try {
      await portalService.revokeConsent(consent.fromHospitalId, consent.toHospitalId)
      onRevoke()
    } catch {
      // error handled silently — user sees no change
    }
  }

  if (consents.length === 0) {
    return (
      <div className="text-center py-12">
        <Shield className="h-12 w-12 text-gray-300 mx-auto mb-3" />
        <p className="text-gray-500 text-sm">No sharing consents on record</p>
        <p className="text-gray-400 text-xs mt-1">
          When you authorize facilities to share your records, they will appear here.
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-3">
      {consents.map((c) => {
        const isActive = (c.status || '').toUpperCase() === 'ACTIVE'
        return (
          <Card key={c.id} className="hover:shadow-md transition-shadow">
            <CardContent className="p-4">
              <div className="flex justify-between items-start">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    {isActive
                      ? <CheckCircle className="h-4 w-4 text-green-600 shrink-0" />
                      : <XCircle className="h-4 w-4 text-gray-400 shrink-0" />}
                    <h4 className="font-semibold text-gray-800 text-sm">
                      {c.fromHospitalName ?? c.fromOrganization ?? 'Source Facility'}
                    </h4>
                  </div>
                  <p className="text-xs text-gray-600 ml-6">
                    Sharing with: {c.toHospitalName ?? c.toOrganization ?? 'Recipient'}
                  </p>
                  {c.consentType && (
                    <p className="text-xs text-gray-500 ml-6 mt-0.5">
                      Type: {c.consentType}
                    </p>
                  )}
                  <p className="text-xs text-gray-400 ml-6 mt-0.5">
                    Granted {formatDate(c.grantedAt ?? c.createdAt)}
                    {c.expiresAt ? ` · Expires ${formatDate(c.expiresAt)}` : ''}
                  </p>
                </div>
                <div className="shrink-0 ml-3 flex flex-col items-end gap-1">
                  <Badge className={`text-[10px] ${isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>
                    {c.status ?? 'Unknown'}
                  </Badge>
                  {isActive && (
                    <Button
                      size="sm"
                      variant="outline"
                      className="text-xs h-7 text-red-600 border-red-200 hover:bg-red-50"
                      onClick={() => handleRevoke(c)}
                    >
                      Revoke
                    </Button>
                  )}
                </div>
              </div>
            </CardContent>
          </Card>
        )
      })}
    </div>
  )
}

/* ── Access Log Tab ─────────────────────────────────────────────── */
function AccessLogTab({ entries }) {
  if (entries.length === 0) {
    return (
      <div className="text-center py-12">
        <Clock className="h-12 w-12 text-gray-300 mx-auto mb-3" />
        <p className="text-gray-500 text-sm">No access log entries</p>
        <p className="text-gray-400 text-xs mt-1">
          Records of who accessed your health information will appear here.
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-3">
      {entries.map((entry) => (
        <Card key={entry.id} className="hover:shadow-md transition-shadow">
          <CardContent className="p-4">
            <div className="flex items-start gap-3">
              <div className="bg-blue-50 rounded-lg p-2 shrink-0">
                <Eye className="h-4 w-4 text-blue-600" />
              </div>
              <div className="flex-1 min-w-0">
                <h4 className="font-semibold text-gray-800 text-sm">
                  {entry.accessedBy ?? entry.userName ?? 'Unknown User'}
                </h4>
                <p className="text-xs text-gray-600 mt-0.5">
                  {entry.action ?? entry.accessType ?? 'Viewed records'}
                </p>
                {entry.resourceType && (
                  <p className="text-xs text-gray-500">
                    Resource: {entry.resourceType}
                  </p>
                )}
                <p className="text-xs text-gray-400 mt-1">
                  {formatDateTime(entry.accessedAt ?? entry.timestamp)}
                </p>
              </div>
            </div>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

/* ── Helpers ─────────────────────────────────────────────────────── */
function formatDate(raw) {
  if (!raw) return '—'
  const d = new Date(raw)
  if (Number.isNaN(d.getTime())) return raw
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

function formatDateTime(raw) {
  if (!raw) return '—'
  const d = new Date(raw)
  if (Number.isNaN(d.getTime())) return raw
  return d.toLocaleString('en-US', {
    month: 'short', day: 'numeric', year: 'numeric',
    hour: 'numeric', minute: '2-digit',
  })
}
