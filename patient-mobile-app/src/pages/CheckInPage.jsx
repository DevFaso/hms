import { useMemo, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { CalendarCheck2, Clock3, MapPin, RefreshCw } from 'lucide-react'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

const ELIGIBLE = new Set(['SCHEDULED', 'CONFIRMED', 'PENDING'])

export default function CheckInPage() {
  const [busyId, setBusyId] = useState(null)
  const { data, refetch } = useApiData(() => portalService.getAppointments(), [])

  const eligible = useMemo(() => {
    const appointments = Array.isArray(data) ? data : []

    return appointments.filter((appt) => {
    const status = (appt.status || '').toUpperCase()
    if (!ELIGIBLE.has(status)) return false
    const date = new Date(appt.appointmentDate || appt.date || '')
    if (Number.isNaN(date.getTime())) return true
    const today = new Date()
    today.setHours(0, 0, 0, 0)
    date.setHours(0, 0, 0, 0)
    const diffDays = (today.getTime() - date.getTime()) / (1000 * 60 * 60 * 24)
    return diffDays >= 0 && diffDays <= 1
    })
  }, [data])

  const handleCheckIn = async (id) => {
    setBusyId(id)
    try {
      await portalService.checkInAppointment(id)
      await refetch()
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="space-y-5">
      <div>
        <h2 className="text-2xl font-bold text-slate-900">Mobile check-in</h2>
        <p className="mt-1 text-sm text-slate-500">
          Check in from your phone for scheduled, confirmed, or pending appointments.
        </p>
      </div>

      {eligible.length === 0 ? (
        <Card>
          <CardContent className="py-12 text-center">
            <CalendarCheck2 className="mx-auto mb-3 h-12 w-12 text-slate-300" />
            <p className="text-sm text-slate-500">No appointments are currently eligible for mobile check-in.</p>
          </CardContent>
        </Card>
      ) : (
        eligible.map((appt) => (
          <Card key={appt.id} className="border-l-4 border-l-emerald-500">
            <CardContent className="space-y-4 p-4">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <h3 className="font-semibold text-slate-900">{appt.providerName || appt.staffName || 'Care team visit'}</h3>
                  <p className="text-sm text-slate-500">{appt.reason || appt.department || appt.treatmentName || 'Appointment'}</p>
                </div>
                <Badge className="bg-emerald-100 text-emerald-700">{appt.status}</Badge>
              </div>
              <div className="grid gap-2 text-sm text-slate-600">
                <div className="flex items-center gap-2"><Clock3 className="h-4 w-4 text-slate-400" />{appt.startTime || 'Time pending'}</div>
                <div className="flex items-center gap-2"><MapPin className="h-4 w-4 text-slate-400" />{appt.location || appt.departmentName || appt.hospitalName || 'Location to be assigned'}</div>
              </div>
              <Button className="w-full bg-emerald-600 hover:bg-emerald-700" disabled={busyId === appt.id} onClick={() => handleCheckIn(appt.id)}>
                {busyId === appt.id ? <RefreshCw className="mr-2 h-4 w-4 animate-spin" /> : <CalendarCheck2 className="mr-2 h-4 w-4" />}
                {busyId === appt.id ? 'Checking in…' : 'Check in now'}
              </Button>
            </CardContent>
          </Card>
        ))
      )}
    </div>
  )
}
