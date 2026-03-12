import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Pill, RefreshCw, ClipboardList } from 'lucide-react'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

const statusColor = (s) => {
  const key = (s || '').toUpperCase()
  if (key === 'ACTIVE') return 'bg-green-100 text-green-700'
  if (key === 'COMPLETED' || key === 'FILLED') return 'bg-blue-100 text-blue-700'
  if (key === 'DISCONTINUED' || key === 'CANCELLED') return 'bg-red-100 text-red-700'
  return 'bg-gray-100 text-gray-700'
}

export default function MedicationsPage() {
  const { data: rawMeds } = useApiData(
    () => portalService.getMedications(),
    [],
  )
  const { data: rawRx } = useApiData(
    () => portalService.getPrescriptions(),
    [],
  )

  const medications = (Array.isArray(rawMeds) ? rawMeds : []).map((m) => ({
    id: m.id,
    name: m.medicationName || m.name || '',
    dosage: m.dosage || '',
    frequency: m.frequency || m.instructions || '',
    prescriber: m.prescribedBy || '',
    startDate: m.startDate || '',
    status: m.status || 'Active',
  }))

  const prescriptions = (Array.isArray(rawRx) ? rawRx : []).map((p) => ({
    id: p.id,
    name: p.medicationName || p.name || '',
    dosage: p.dosage || '',
    frequency: p.frequency || '',
    prescriber: p.prescribedBy || '',
    prescribedDate: p.prescribedDate || '',
    refillsRemaining: p.refillsRemaining ?? '',
    status: p.status || '',
  }))

  return (
    <div className="space-y-6">
      {/* Active Medications */}
      <section className="space-y-3">
        <h2 className="text-xl font-bold text-gray-800">Active Medications</h2>

        {medications.length === 0 && (
          <p className="text-sm text-gray-400 text-center py-6">No active medications</p>
        )}

        {medications.map((med) => (
          <Card key={med.id} className="hover:shadow-md transition-shadow">
            <CardContent className="p-4">
              <div className="flex justify-between items-start mb-2">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center space-x-2 mb-1">
                    <Pill className="h-4 w-4 text-teal-600 shrink-0" />
                    <h4 className="font-semibold text-gray-800">{med.name}</h4>
                  </div>
                  {med.dosage && <p className="text-sm text-gray-600">{med.dosage}</p>}
                  {med.frequency && <p className="text-sm text-gray-500">{med.frequency}</p>}
                  {med.prescriber && (
                    <p className="text-xs text-gray-400 mt-1">Prescribed by {med.prescriber}</p>
                  )}
                  {med.startDate && (
                    <p className="text-xs text-gray-400">Since {med.startDate}</p>
                  )}
                </div>
                <Badge className={`text-xs shrink-0 ${statusColor(med.status)}`}>
                  {med.status}
                </Badge>
              </div>
            </CardContent>
          </Card>
        ))}
      </section>

      {/* Prescriptions */}
      <section className="space-y-3">
        <h2 className="text-xl font-bold text-gray-800">Prescriptions</h2>

        {prescriptions.length === 0 && (
          <p className="text-sm text-gray-400 text-center py-6">No prescriptions on file</p>
        )}

        {prescriptions.map((rx) => (
          <Card key={rx.id} className="hover:shadow-md transition-shadow">
            <CardContent className="p-4">
              <div className="flex justify-between items-start mb-2">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center space-x-2 mb-1">
                    <ClipboardList className="h-4 w-4 text-indigo-600 shrink-0" />
                    <h4 className="font-semibold text-gray-800">{rx.name}</h4>
                  </div>
                  {rx.dosage && <p className="text-sm text-gray-600">{rx.dosage}</p>}
                  {rx.frequency && <p className="text-sm text-gray-500">{rx.frequency}</p>}
                  {rx.prescriber && (
                    <p className="text-xs text-gray-400 mt-1">Prescribed by {rx.prescriber}</p>
                  )}
                  {rx.prescribedDate && (
                    <p className="text-xs text-gray-400">Date: {rx.prescribedDate}</p>
                  )}
                </div>
                <Badge className={`text-xs shrink-0 ${statusColor(rx.status)}`}>
                  {rx.status}
                </Badge>
              </div>
              {rx.refillsRemaining !== '' && (
                <div className="bg-gray-50 rounded-lg p-2 text-sm mt-2">
                  <div className="flex items-center text-gray-600">
                    <RefreshCw className="h-3 w-3 mr-2 shrink-0" />
                    Refills remaining: {rx.refillsRemaining}
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        ))}
      </section>
    </div>
  )
}

