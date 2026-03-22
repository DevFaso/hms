import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Calendar, AlertCircle, FileText, CalendarCheck2, XCircle } from 'lucide-react'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

export default function AppointmentsPage() {
  const navigate = useNavigate()
  const { data: raw, refetch } = useApiData(() => portalService.getAppointments(), [])
  const { data: summaries } = useApiData(() => portalService.getAfterVisitSummaries(), [])

  const appointments = (raw || []).map((a) => ({
    id: a.id,
    type: a.treatmentName || a.type || 'Appointment',
    doctor: a.providerName || a.staffName || a.doctor || '',
    location: a.departmentName || a.hospitalName || a.location || '',
    date: a.appointmentDate ? new Date(a.appointmentDate).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }).toUpperCase() : a.date || '',
    rawDate: a.appointmentDate || a.date || '',
    time: a.startTime || a.time || '',
    status: a.status || 'SCHEDULED',
  }))

  const latestSummaryByDate = new Map((Array.isArray(summaries) ? summaries : []).map((item) => [item.encounterDate, item]))
  const upcoming = appointments.filter((a) => mapStatus(a.status, a.rawDate) === 'upcoming')
  const past = appointments.filter((a) => mapStatus(a.status, a.rawDate) === 'past')

  const handleCheckIn = async (id) => {
    await portalService.checkInAppointment(id)
    await refetch()
  }

  const handleCancel = async (id) => {
    await portalService.cancelAppointment({ appointmentId: id, reason: 'Cancelled from mobile app' })
    await refetch()
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-gray-800">Appointments</h2>
        <Button className="bg-blue-700 hover:bg-blue-800 text-xs h-9" onClick={() => navigate('/appointments/schedule')}>
          <Calendar className="mr-1 h-4 w-4" /> Schedule
        </Button>
      </div>

      <section className="space-y-3">
        <h3 className="flex items-center text-lg font-semibold text-gray-800">Upcoming{upcoming.length > 0 && <AlertCircle className="ml-2 h-4 w-4 text-orange-500" />}</h3>
        {upcoming.length === 0 && <p className="text-sm text-gray-500">No upcoming appointments.</p>}
        {upcoming.map((apt) => {
          const canCheckIn = ['SCHEDULED', 'CONFIRMED', 'PENDING'].includes((apt.status || '').toUpperCase())
          const canCancel = ['SCHEDULED', 'CONFIRMED', 'PENDING'].includes((apt.status || '').toUpperCase())
          return (
            <Card key={apt.id} className="transition-shadow hover:shadow-md">
              <CardContent className="space-y-3 p-4">
                <div className="flex justify-between items-start">
                  <div className="min-w-0 flex-1">
                    <h4 className="font-semibold text-gray-800">{apt.type}</h4>
                    {apt.doctor && <p className="text-sm text-gray-600">{apt.doctor}</p>}
                    <p className="text-sm text-gray-500">{apt.location}</p>
                    <p className="text-sm text-gray-500">Starts at {apt.time || 'TBD'}</p>
                  </div>
                  <div className="ml-3 shrink-0 text-right">
                    <div className="rounded-lg bg-blue-50 p-2">
                      <p className="text-xs font-medium text-blue-700">{apt.date.split(' ')[0]}</p>
                      <p className="text-xl font-bold leading-tight text-blue-700">{apt.date.split(' ')[1]?.replace(',', '')}</p>
                    </div>
                    <Badge className="mt-2 bg-blue-100 text-blue-700">{apt.status}</Badge>
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  {canCheckIn && <Button size="sm" className="bg-emerald-600 hover:bg-emerald-700" onClick={() => handleCheckIn(apt.id)}><CalendarCheck2 className="mr-1 h-4 w-4" /> Check in</Button>}
                  {canCancel && <Button size="sm" variant="outline" className="text-red-600" onClick={() => handleCancel(apt.id)}><XCircle className="mr-1 h-4 w-4" /> Cancel</Button>}
                </div>
              </CardContent>
            </Card>
          )
        })}
      </section>

      <section className="space-y-3">
        <h3 className="text-lg font-semibold text-gray-800">Past</h3>
        {past.map((apt) => {
          const summary = latestSummaryByDate.get(apt.rawDate)
          return (
            <Card key={apt.id} className="opacity-85">
              <CardContent className="p-4">
                <div className="flex justify-between items-start">
                  <div className="min-w-0 flex-1">
                    <h4 className="font-semibold text-gray-800">{apt.type}</h4>
                    {apt.doctor && <p className="text-sm text-gray-600">{apt.doctor}</p>}
                    <p className="text-sm text-gray-500">{apt.location}</p>
                    {summary && <Button variant="link" className="mt-1 h-auto p-0 text-sm text-blue-600" onClick={() => navigate(`/visits/${summary.encounterId || summary.id}/summary`)}><FileText className="mr-1 h-4 w-4" /> View After Visit Summary</Button>}
                  </div>
                  <div className="ml-3 shrink-0 text-right"><div className="rounded-lg bg-gray-100 p-2"><p className="text-xs font-medium text-gray-500">{apt.date.split(' ')[0]}</p><p className="text-xl font-bold leading-tight text-gray-500">{apt.date.split(' ')[1]?.replace(',', '')}</p></div></div>
                </div>
              </CardContent>
            </Card>
          )
        })}
      </section>
    </div>
  )
}

function mapStatus(status, dateStr) {
  const upper = (status || '').toUpperCase()
  if (['COMPLETED', 'NO_SHOW', 'CANCELLED'].includes(upper)) return 'past'
  if (dateStr) {
    const aptDate = new Date(dateStr)
    if (!Number.isNaN(aptDate.getTime()) && aptDate < new Date()) return 'past'
  }
  return 'upcoming'
}
