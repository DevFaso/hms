import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Calendar, AlertCircle, FileText } from 'lucide-react'
import { appointments as mockAppointments } from '@/data/appointments'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

export default function AppointmentsPage() {
  const navigate = useNavigate()
  const { data: raw } = useApiData(
    () => portalService.getAppointments(),
    mockAppointments,
  )

  // Normalize API shape → local shape
  const appointments = (raw || []).map((a) => ({
    id: a.id,
    type: a.treatmentName || a.type || 'Appointment',
    doctor: a.staffName || a.doctor,
    location: a.hospitalName || a.location || '',
    date: a.appointmentDate
      ? new Date(a.appointmentDate).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }).toUpperCase()
      : a.date || '',
    time: a.startTime || a.time || '',
    status: mapStatus(a.status),
  }))

  const upcoming = appointments.filter((a) => a.status === 'upcoming')
  const past = appointments.filter((a) => a.status === 'past')

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-gray-800">Appointments</h2>
        <Button
          className="bg-blue-700 hover:bg-blue-800 text-xs h-9"
          onClick={() => navigate('/appointments/schedule')}
        >
          <Calendar className="h-4 w-4 mr-1" />
          Schedule
        </Button>
      </div>

      {/* Upcoming */}
      <section className="space-y-3">
        <h3 className="text-lg font-semibold text-gray-800 flex items-center">
          Upcoming
          {upcoming.length > 0 && (
            <AlertCircle className="h-4 w-4 ml-2 text-orange-500" />
          )}
        </h3>
        {upcoming.length === 0 && (
          <p className="text-sm text-gray-500">No upcoming appointments.</p>
        )}
        {upcoming.map((apt) => (
          <Card key={apt.id} className="hover:shadow-md transition-shadow">
            <CardContent className="p-4">
              <div className="flex justify-between items-start">
                <div className="flex-1 min-w-0">
                  <h4 className="font-semibold text-gray-800">{apt.type}</h4>
                  {apt.doctor && (
                    <p className="text-sm text-gray-600">{apt.doctor}</p>
                  )}
                  <p className="text-sm text-gray-500">{apt.location}</p>
                  <p className="text-sm text-gray-500">Starts at {apt.time}</p>
                </div>
                <div className="text-right shrink-0 ml-3">
                  <div className="bg-blue-50 rounded-lg p-2">
                    <p className="text-blue-700 text-xs font-medium">
                      {apt.date.split(' ')[0]}
                    </p>
                    <p className="text-blue-700 font-bold text-xl leading-tight">
                      {apt.date.split(' ')[1]?.replace(',', '')}
                    </p>
                  </div>
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </section>

      {/* Past */}
      <section className="space-y-3">
        <h3 className="text-lg font-semibold text-gray-800">Past</h3>
        {past.map((apt) => (
          <Card key={apt.id} className="opacity-80">
            <CardContent className="p-4">
              <div className="flex justify-between items-start">
                <div className="flex-1 min-w-0">
                  <h4 className="font-semibold text-gray-800">{apt.type}</h4>
                  {apt.doctor && (
                    <p className="text-sm text-gray-600">{apt.doctor}</p>
                  )}
                  <p className="text-sm text-gray-500">{apt.location}</p>
                  <Button variant="link" className="text-blue-600 p-0 h-auto text-sm mt-1">
                    <FileText className="h-4 w-4 mr-1" />
                    View After Visit Summary®
                  </Button>
                </div>
                <div className="text-right shrink-0 ml-3">
                  <div className="bg-gray-100 rounded-lg p-2">
                    <p className="text-gray-500 text-xs font-medium">
                      {apt.date.split(' ')[0]}
                    </p>
                    <p className="text-gray-500 font-bold text-xl leading-tight">
                      {apt.date.split(' ')[1]?.replace(',', '')}
                    </p>
                  </div>
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </section>
    </div>
  )
}

function mapStatus(s) {
  if (!s) return 'upcoming'
  const upper = s.toUpperCase()
  if (['COMPLETED', 'NO_SHOW', 'CANCELLED'].includes(upper)) return 'past'
  return 'upcoming'
}
