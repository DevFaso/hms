import { useState } from 'react'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  FileText, TestTube, Pill, Syringe,
  User, AlertTriangle, Activity
} from 'lucide-react'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'
import { useAuth } from '@/contexts/AuthContext'

const TABS = ['Overview', 'Encounters', 'Labs', 'Medications', 'Immunizations']

export default function HealthRecordsPage() {
  const [activeTab, setActiveTab] = useState('Overview')
  const { profile } = useAuth()

  const { data: healthSummary } = useApiData(
    () => portalService.getHealthSummary(),
    null,
  )
  const { data: rawEncounters } = useApiData(
    () => portalService.getEncounters(),
    [],
  )
  const { data: rawLabs } = useApiData(
    () => portalService.getLabResults(),
    [],
  )
  const { data: rawMeds } = useApiData(
    () => portalService.getMedications(),
    [],
  )
  const { data: rawImmunizations } = useApiData(
    () => portalService.getImmunizations(),
    [],
  )

  const encounters = Array.isArray(rawEncounters) ? rawEncounters : []
  const labs = Array.isArray(rawLabs) ? rawLabs : []
  const meds = Array.isArray(rawMeds) ? rawMeds : []
  const immunizations = Array.isArray(rawImmunizations) ? rawImmunizations : []

  const allergies = healthSummary?.allergies ?? []
  const conditions = healthSummary?.activeDiagnoses ?? []

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-bold text-gray-800">Health Records</h2>

      {/* Tab bar */}
      <div className="flex overflow-x-auto gap-1 pb-1 -mx-1 px-1">
        {TABS.map((tab) => (
          <Button
            key={tab}
            size="sm"
            variant={activeTab === tab ? 'default' : 'outline'}
            className={`shrink-0 text-xs h-8 ${
              activeTab === tab ? 'bg-green-700 hover:bg-green-800' : ''
            }`}
            onClick={() => setActiveTab(tab)}
          >
            {tab}
          </Button>
        ))}
      </div>

      {activeTab === 'Overview' && (
        <OverviewTab profile={profile} allergies={allergies} conditions={conditions} />
      )}
      {activeTab === 'Encounters' && <EncountersTab encounters={encounters} />}
      {activeTab === 'Labs' && <LabsTab labs={labs} />}
      {activeTab === 'Medications' && <MedsTab meds={meds} />}
      {activeTab === 'Immunizations' && <ImmunizationsTab immunizations={immunizations} />}
    </div>
  )
}

/* ── Overview Tab ───────────────────────────────────────────────── */
function OverviewTab({ profile, allergies, conditions }) {
  return (
    <div className="space-y-4">
      {/* Personal Info */}
      <Card>
        <CardContent className="p-4">
          <h3 className="font-semibold text-gray-800 mb-3 flex items-center">
            <User className="h-4 w-4 mr-2 text-green-600" />
            Personal Information
          </h3>
          <div className="grid grid-cols-2 gap-3 text-sm">
            <InfoRow label="Name" value={`${profile?.firstName ?? ''} ${profile?.lastName ?? ''}`} />
            <InfoRow label="DOB" value={formatDate(profile?.dateOfBirth)} />
            <InfoRow label="Gender" value={profile?.gender} />
            <InfoRow label="Blood Type" value={profile?.bloodType} />
            <InfoRow label="Phone" value={profile?.phoneNumberPrimary ?? profile?.phone} />
            <InfoRow label="Email" value={profile?.email} />
          </div>
        </CardContent>
      </Card>

      {/* Allergies */}
      <Card>
        <CardContent className="p-4">
          <h3 className="font-semibold text-gray-800 mb-2 flex items-center">
            <AlertTriangle className="h-4 w-4 mr-2 text-orange-500" />
            Allergies
          </h3>
          {allergies.length === 0
            ? <p className="text-sm text-gray-500">No known allergies on record.</p>
            : (
              <div className="flex flex-wrap gap-1.5">
                {allergies.map((a, i) => {
                  const name = typeof a === 'string' ? a : a.allergen ?? a.name ?? 'Unknown'
                  return (
                    <Badge key={i} variant="outline" className="text-xs border-orange-300 text-orange-700">
                      {name}
                    </Badge>
                  )
                })}
              </div>
            )}
        </CardContent>
      </Card>

      {/* Active Conditions */}
      <Card>
        <CardContent className="p-4">
          <h3 className="font-semibold text-gray-800 mb-2 flex items-center">
            <Activity className="h-4 w-4 mr-2 text-purple-600" />
            Active Conditions
          </h3>
          {conditions.length === 0
            ? <p className="text-sm text-gray-500">No active conditions on record.</p>
            : (
              <div className="flex flex-wrap gap-1.5">
                {conditions.map((c, i) => {
                  const name = typeof c === 'string' ? c : c.name ?? c.diagnosis ?? 'Condition'
                  return (
                    <Badge key={i} variant="outline" className="text-xs border-purple-300 text-purple-700">
                      {name}
                    </Badge>
                  )
                })}
              </div>
            )}
        </CardContent>
      </Card>
    </div>
  )
}

/* ── Encounters Tab ─────────────────────────────────────────────── */
function EncountersTab({ encounters }) {
  if (encounters.length === 0) {
    return <EmptyState icon={FileText} message="No encounters found" />
  }
  return (
    <div className="space-y-3">
      {encounters.map((e) => (
        <Card key={e.id} className="hover:shadow-md transition-shadow">
          <CardContent className="p-4">
            <div className="flex justify-between items-start">
              <div className="flex-1 min-w-0">
                <h4 className="font-semibold text-gray-800 text-sm">
                  {e.encounterType ?? e.type ?? 'Visit'}
                </h4>
                <p className="text-xs text-gray-600 mt-0.5">
                  {e.providerName ?? e.staffName ?? 'Provider'}
                </p>
                <p className="text-xs text-gray-500">
                  {e.department ?? e.hospitalName ?? ''}
                </p>
                {e.chiefComplaint && (
                  <p className="text-xs text-gray-500 mt-1 italic">
                    &ldquo;{e.chiefComplaint}&rdquo;
                  </p>
                )}
              </div>
              <div className="text-right shrink-0 ml-3">
                <p className="text-xs text-gray-400">
                  {formatDate(e.encounterDate ?? e.date ?? e.createdAt)}
                </p>
                {e.status && (
                  <Badge className={`text-[10px] mt-1 ${statusColor(e.status)}`}>
                    {e.status}
                  </Badge>
                )}
              </div>
            </div>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

/* ── Labs Tab ───────────────────────────────────────────────────── */
function LabsTab({ labs }) {
  if (labs.length === 0) {
    return <EmptyState icon={TestTube} message="No lab results found" />
  }
  return (
    <div className="space-y-3">
      {labs.map((l) => {
        const abnormal = l.isAbnormal === true || (l.notes && /high|low|abnormal/i.test(l.notes))
        return (
          <Card key={l.id} className="hover:shadow-md transition-shadow">
            <CardContent className="p-4">
              <div className="flex justify-between items-start">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <h4 className="font-semibold text-gray-800 text-sm">
                      {l.testName ?? l.testCode ?? 'Lab Test'}
                    </h4>
                    {abnormal && (
                      <Badge className="text-[10px] bg-red-100 text-red-700">Abnormal</Badge>
                    )}
                  </div>
                  <p className="text-xs text-gray-600 mt-1">
                    Result: <span className="font-medium">{l.result ?? l.value ?? '—'}</span>
                    {l.unit ? ` ${l.unit}` : ''}
                  </p>
                  {l.referenceRange && (
                    <p className="text-xs text-gray-500">Ref: {l.referenceRange}</p>
                  )}
                </div>
                <div className="text-right shrink-0 ml-3">
                  <p className="text-xs text-gray-400">
                    {formatDate(l.collectedDate ?? l.resultedAt)}
                  </p>
                  {l.status && (
                    <Badge className={`text-[10px] mt-1 ${l.status === 'FINAL' ? 'bg-green-100 text-green-700' : 'bg-blue-100 text-blue-700'}`}>
                      {l.status}
                    </Badge>
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

/* ── Medications Tab ────────────────────────────────────────────── */
function MedsTab({ meds }) {
  if (meds.length === 0) {
    return <EmptyState icon={Pill} message="No medications on record" />
  }
  return (
    <div className="space-y-3">
      {meds.map((m) => (
        <Card key={m.id} className="hover:shadow-md transition-shadow">
          <CardContent className="p-4">
            <div className="flex justify-between items-start">
              <div className="flex-1 min-w-0">
                <h4 className="font-semibold text-gray-800 text-sm">
                  {m.medicationName ?? m.name ?? 'Medication'}
                </h4>
                <p className="text-xs text-gray-600 mt-0.5">
                  {m.dosage ?? ''} {m.frequency ?? ''}
                </p>
                {m.prescribedBy && (
                  <p className="text-xs text-gray-500">Prescribed by {m.prescribedBy}</p>
                )}
              </div>
              <div className="text-right shrink-0 ml-3">
                {m.status && (
                  <Badge className={`text-[10px] ${m.status === 'ACTIVE' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>
                    {m.status}
                  </Badge>
                )}
              </div>
            </div>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

/* ── Immunizations Tab ──────────────────────────────────────────── */
function ImmunizationsTab({ immunizations }) {
  if (immunizations.length === 0) {
    return <EmptyState icon={Syringe} message="No immunization records found" />
  }
  return (
    <div className="space-y-3">
      {immunizations.map((im, idx) => (
        <Card key={im.id ?? idx} className="hover:shadow-md transition-shadow">
          <CardContent className="p-4">
            <div className="flex justify-between items-start">
              <div className="flex-1 min-w-0">
                <h4 className="font-semibold text-gray-800 text-sm">
                  {im.vaccineName ?? im.name ?? 'Immunization'}
                </h4>
                {im.site && <p className="text-xs text-gray-500 mt-0.5">Site: {im.site}</p>}
                {im.administeredBy && (
                  <p className="text-xs text-gray-500">By {im.administeredBy}</p>
                )}
              </div>
              <div className="text-right shrink-0 ml-3">
                <p className="text-xs text-gray-400">
                  {formatDate(im.administeredDate ?? im.date)}
                </p>
                {im.doseNumber && (
                  <Badge className="text-[10px] bg-blue-100 text-blue-700 mt-1">
                    Dose {im.doseNumber}
                  </Badge>
                )}
              </div>
            </div>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

/* ── Helpers ─────────────────────────────────────────────────────── */
function InfoRow({ label, value }) {
  return (
    <div>
      <p className="text-xs text-gray-500">{label}</p>
      <p className="text-gray-800 font-medium">{value || '—'}</p>
    </div>
  )
}

function EmptyState({ icon: Icon, message }) {
  return (
    <div className="text-center py-12">
      <Icon className="h-12 w-12 text-gray-300 mx-auto mb-3" />
      <p className="text-gray-500 text-sm">{message}</p>
    </div>
  )
}

function formatDate(raw) {
  if (!raw) return '—'
  const d = new Date(raw)
  if (Number.isNaN(d.getTime())) return raw
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

function statusColor(s) {
  const up = (s || '').toUpperCase()
  if (['COMPLETED', 'DISCHARGED'].includes(up)) return 'bg-green-100 text-green-700'
  if (['IN_PROGRESS', 'ACTIVE'].includes(up)) return 'bg-blue-100 text-blue-700'
  if (['CANCELLED', 'NO_SHOW'].includes(up)) return 'bg-red-100 text-red-700'
  return 'bg-gray-100 text-gray-600'
}
