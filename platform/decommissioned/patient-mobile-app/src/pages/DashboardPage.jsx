import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/contexts/AuthContext'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import {
  Calendar, Mail, Building2, TestTube, Pill, CreditCard,
  ChevronRight, Bell, Users, Heart, Activity, AlertTriangle,
  Stethoscope
} from 'lucide-react'
import notificationService from '@/services/notificationService'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

const quickLinks = [
  { icon: Calendar, title: 'Appointments', path: '/appointments', color: 'bg-green-500' },
  { icon: Mail, title: 'Messages', path: '/messages', color: 'bg-blue-500' },
  { icon: Building2, title: 'Visits', path: '/visits', color: 'bg-indigo-500' },
  { icon: TestTube, title: 'Test Results', path: '/lab-results', color: 'bg-purple-500' },
  { icon: Pill, title: 'Medications', path: '/medications', color: 'bg-teal-500' },
  { icon: CreditCard, title: 'Billing', path: '/billing', color: 'bg-orange-500' },
  { icon: Users, title: 'Care Team', path: '/care-team', color: 'bg-pink-500' },
  { icon: Activity, title: 'Vitals', path: '/vitals', color: 'bg-red-500' },
]

export default function DashboardPage() {
  const navigate = useNavigate()
  const { user } = useAuth()

  const { data: notifications } = useApiData(() => notificationService.getAll(), [])
  const { data: invoices } = useApiData(() => portalService.getInvoices(), [])
  const { data: appointments } = useApiData(() => portalService.getAppointments(), [])
  const { data: medications } = useApiData(() => portalService.getMedications(), [])
  const { data: labResults } = useApiData(() => portalService.getLabResults(), [])
  const { data: careTeamRaw } = useApiData(() => portalService.getCareTeam(), null)
  const { data: healthSummary } = useApiData(() => portalService.getHealthSummary(), null)

  const unreadNotifications = (Array.isArray(notifications) ? notifications : []).filter((n) => !n.read)

  const safeInvoices = Array.isArray(invoices) ? invoices : []
  const totalDue = safeInvoices
    .filter((inv) => inv.status !== 'PAID' && inv.status !== 'CANCELLED')
    .reduce((sum, inv) => sum + (Number.parseFloat(inv.balance ?? inv.amount) || 0), 0)

  const now = new Date()
  const safeAppointments = Array.isArray(appointments) ? appointments : []
  const upcoming = safeAppointments
    .filter((a) => a.status !== 'CANCELLED' && a.status !== 'COMPLETED' && a.status !== 'NO_SHOW')
    .slice(0, 3)

  const safeMeds = Array.isArray(medications) ? medications : []
  const safeLabs = Array.isArray(labResults) ? labResults : []

  let careTeamMembers = []
  if (Array.isArray(careTeamRaw?.members)) {
    careTeamMembers = careTeamRaw.members
  } else if (Array.isArray(careTeamRaw)) {
    careTeamMembers = careTeamRaw
  }

  const allergies = healthSummary?.allergies || []
  const conditions = healthSummary?.activeDiagnoses || []

  return (
    <div className="space-y-6">
      {/* Welcome */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-800">
            Welcome, {user?.firstName || 'Patient'}!
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            {now.toLocaleDateString('en-US', {
              weekday: 'long', month: 'long', day: 'numeric',
            })}
          </p>
        </div>
        {unreadNotifications.length > 0 && (
          <Button
            variant="outline"
            size="sm"
            className="relative"
            onClick={() => navigate('/notifications')}
          >
            <Bell className="h-4 w-4 mr-1" />
            {unreadNotifications.length}
          </Button>
        )}
      </div>

      {/* Quick Access Grid */}
      <div className="grid grid-cols-4 gap-3">
        {quickLinks.map((item) => (
          <Card
            key={item.path}
            className="cursor-pointer hover:shadow-lg transition-all duration-200 active:scale-95"
            onClick={() => navigate(item.path)}
          >
            <CardContent className="p-3 text-center">
              <div className={`${item.color} w-10 h-10 rounded-xl flex items-center justify-center mx-auto mb-1`}>
                <item.icon className="h-5 w-5 text-white" />
              </div>
              <h3 className="text-[11px] font-medium text-gray-700 leading-tight">{item.title}</h3>
            </CardContent>
          </Card>
        ))}
      </div>

      {/* ── Upcoming Appointments ─────────────────────── */}
      <Card>
        <CardContent className="p-4">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-gray-800 flex items-center">
              <Calendar className="h-4 w-4 mr-2 text-green-600" />
              Upcoming Appointments
            </h2>
            <Button variant="link" size="sm" className="text-xs p-0 h-auto text-blue-600" onClick={() => navigate('/appointments')}>
              View All
            </Button>
          </div>
          {upcoming.length === 0 ? (
            <div className="text-center py-4">
              <Calendar className="h-8 w-8 text-gray-300 mx-auto mb-2" />
              <p className="text-sm text-gray-400">No upcoming appointments</p>
              <Button size="sm" className="mt-2 bg-blue-700 hover:bg-blue-800 text-xs" onClick={() => navigate('/appointments/schedule')}>
                Schedule Now
              </Button>
            </div>
          ) : (
            <div className="space-y-2">
              {upcoming.map((appt) => (
                <div key={appt.id} className="flex items-center space-x-3 p-2 bg-gray-50 rounded-lg">
                  <div className="bg-blue-50 rounded-lg p-2 text-center min-w-[44px]">
                    <p className="text-[10px] text-blue-600 font-medium">{appt.appointmentDate ? new Date(appt.appointmentDate).toLocaleDateString('en-US', { month: 'short' }) : ''}</p>
                    <p className="text-lg font-bold text-blue-700 leading-tight">{appt.appointmentDate ? new Date(appt.appointmentDate).getDate() : ''}</p>
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-gray-800 truncate">{appt.staffName || appt.providerName || 'Provider'}</p>
                    <p className="text-xs text-gray-500 truncate">{appt.reason || appt.treatmentName || appt.department || 'General Visit'}</p>
                    <p className="text-xs text-gray-400">{appt.startTime || ''}</p>
                  </div>
                  <Badge className="text-[10px] bg-blue-100 text-blue-700 shrink-0">{appt.status}</Badge>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* ── Current Medications ───────────────────────── */}
      <Card>
        <CardContent className="p-4">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-gray-800 flex items-center">
              <Pill className="h-4 w-4 mr-2 text-teal-600" />
              Current Medications
            </h2>
            <Button variant="link" size="sm" className="text-xs p-0 h-auto text-blue-600" onClick={() => navigate('/medications')}>
              View All
            </Button>
          </div>
          {safeMeds.length === 0 ? (
            <div className="text-center py-4">
              <Pill className="h-8 w-8 text-gray-300 mx-auto mb-2" />
              <p className="text-sm text-gray-400">No active medications</p>
            </div>
          ) : (
            <div className="space-y-2">
              {safeMeds.slice(0, 4).map((med) => (
                <div key={med.id} className="flex items-center space-x-3 p-2 bg-gray-50 rounded-lg">
                  <div className="bg-teal-50 rounded-full p-2">
                    <Pill className="h-4 w-4 text-teal-600" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-gray-800 truncate">{med.medicationName || med.name}</p>
                    <p className="text-xs text-gray-500">{med.dosage} · {med.frequency}</p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* ── Recent Test Results ───────────────────────── */}
      <Card>
        <CardContent className="p-4">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-gray-800 flex items-center">
              <TestTube className="h-4 w-4 mr-2 text-purple-600" />
              Recent Test Results
            </h2>
            <Button variant="link" size="sm" className="text-xs p-0 h-auto text-blue-600" onClick={() => navigate('/lab-results')}>
              View All
            </Button>
          </div>
          {safeLabs.length === 0 ? (
            <div className="text-center py-4">
              <TestTube className="h-8 w-8 text-gray-300 mx-auto mb-2" />
              <p className="text-sm text-gray-400">No test results available</p>
            </div>
          ) : (
            <div className="space-y-2">
              {safeLabs.slice(0, 4).map((lab) => (
                <div key={lab.id} className="flex items-center space-x-3 p-2 bg-gray-50 rounded-lg">
                  <div className={`rounded-full p-2 ${lab.isAbnormal ? 'bg-red-50' : 'bg-green-50'}`}>
                    {lab.isAbnormal
                      ? <AlertTriangle className="h-4 w-4 text-red-500" />
                      : <TestTube className="h-4 w-4 text-green-600" />
                    }
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-gray-800 truncate">{lab.testName}</p>
                    <p className="text-xs text-gray-500">
                      {lab.result || lab.value}
                      {lab.referenceRange ? ` (${lab.referenceRange})` : ''}
                    </p>
                  </div>
                  <span className="text-[10px] text-gray-400 shrink-0">
                    {(() => {
                      const d = lab.collectedDate || lab.resultedAt
                      return d ? new Date(d).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) : ''
                    })()}
                  </span>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* ── Billing Summary ───────────────────────────── */}
      <Card>
        <CardContent className="p-4">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-gray-800 flex items-center">
              <CreditCard className="h-4 w-4 mr-2 text-orange-500" />
              Billing
            </h2>
            <Button variant="link" size="sm" className="text-xs p-0 h-auto text-blue-600" onClick={() => navigate('/billing')}>
              View All
            </Button>
          </div>
          {safeInvoices.length === 0 ? (
            <div className="text-center py-4">
              <CreditCard className="h-8 w-8 text-gray-300 mx-auto mb-2" />
              <p className="text-sm text-gray-400">No outstanding bills</p>
            </div>
          ) : (
            <div className="space-y-2">
              {safeInvoices.slice(0, 3).map((inv) => (
                <div key={inv.id} className="flex items-center justify-between p-2 bg-gray-50 rounded-lg">
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-gray-800 truncate">{inv.description || inv.facility || inv.notes || 'Medical Services'}</p>
                    <p className="text-xs text-gray-400">{inv.invoiceDate || inv.date ? new Date(inv.invoiceDate || inv.date).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : ''}</p>
                  </div>
                  <div className="text-right shrink-0 ml-2">
                    <p className={`text-sm font-bold ${inv.balance > 0 || (inv.totalAmount - (inv.amountPaid || 0)) > 0 ? 'text-red-600' : 'text-green-600'}`}>
                      ${(inv.balance ?? (inv.totalAmount - (inv.amountPaid || 0))).toFixed(2)}
                    </p>
                    <Badge className={`text-[10px] ${inv.status === 'PAID' ? 'bg-green-100 text-green-700' : 'bg-orange-100 text-orange-700'}`}>
                      {inv.status}
                    </Badge>
                  </div>
                </div>
              ))}
              {totalDue > 0 && (
                <div className="flex items-center justify-between pt-2 border-t">
                  <span className="text-sm font-semibold text-gray-700">Total Due</span>
                  <span className="text-lg font-bold text-red-600">${totalDue.toFixed(2)}</span>
                </div>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      {/* ── Care Team ─────────────────────────────────── */}
      {careTeamMembers.length > 0 && (
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-sm font-semibold text-gray-800 flex items-center">
                <Users className="h-4 w-4 mr-2 text-pink-500" />
                My Care Team
              </h2>
              <Button variant="link" size="sm" className="text-xs p-0 h-auto text-blue-600" onClick={() => navigate('/care-team')}>
                View All
              </Button>
            </div>
            <div className="grid grid-cols-2 gap-2">
              {careTeamMembers.slice(0, 4).map((m) => (
                <div key={m.name || m.id} className={`flex items-center space-x-2 p-2 rounded-lg ${m.isPrimary ? 'bg-blue-50 border border-blue-200' : 'bg-gray-50'}`}>
                  <div className={`w-8 h-8 rounded-full flex items-center justify-center shrink-0 ${m.isPrimary ? 'bg-blue-600' : 'bg-gray-200'}`}>
                    <Stethoscope className={`h-4 w-4 ${m.isPrimary ? 'text-white' : 'text-gray-500'}`} />
                  </div>
                  <div className="min-w-0">
                    <p className="text-xs font-medium text-gray-800 truncate">{m.name}</p>
                    <p className="text-[10px] text-gray-500 truncate">{m.specialty || m.role}</p>
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      {/* ── Health at a Glance ────────────────────────── */}
      {(allergies.length > 0 || conditions.length > 0) && (
        <Card>
          <CardContent className="p-4">
            <h2 className="text-sm font-semibold text-gray-800 flex items-center mb-3">
              <Heart className="h-4 w-4 mr-2 text-red-500" />
              Health at a Glance
            </h2>
            {allergies.length > 0 && (
              <div className="mb-3">
                <h4 className="text-xs font-medium text-gray-500 flex items-center mb-1">
                  <AlertTriangle className="h-3 w-3 mr-1 text-red-500" />
                  Allergies
                </h4>
                <div className="flex flex-wrap gap-1">
                  {allergies.map((a) => (
                    <Badge key={a} className="text-xs bg-red-50 text-red-700 border-red-200">{a}</Badge>
                  ))}
                </div>
              </div>
            )}
            {conditions.length > 0 && (
              <div>
                <h4 className="text-xs font-medium text-gray-500 flex items-center mb-1">
                  <Stethoscope className="h-3 w-3 mr-1 text-blue-600" />
                  Active Conditions
                </h4>
                <div className="flex flex-wrap gap-1">
                  {conditions.map((c) => (
                    <Badge key={c} className="text-xs bg-blue-50 text-blue-700 border-blue-200">{c}</Badge>
                  ))}
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {/* ── Recent Notifications ──────────────────────── */}
      {unreadNotifications.length > 0 && (
        <div className="space-y-3">
          <h2 className="text-lg font-semibold text-gray-800">Recent Notifications</h2>
          {unreadNotifications.slice(0, 3).map((notif) => {
            const iconMap = { 'lab-result': TestTube, billing: CreditCard, appointment: Calendar, medication: Pill, message: Mail }
            const colorMap = { 'lab-result': 'blue', billing: 'green', appointment: 'indigo', medication: 'teal', message: 'purple' }
            const Icon = iconMap[notif.type] || Bell
            const color = colorMap[notif.type] || 'gray'

            return (
              <Card key={notif.id} className={`border-l-4 border-l-${color}-500`}>
                <CardContent className="p-3">
                  <div className="flex items-start space-x-3">
                    <div className={`bg-${color}-500 rounded-full p-1.5 shrink-0`}>
                      <Icon className="h-3 w-3 text-white" />
                    </div>
                    <div className="flex-1 min-w-0">
                      <h3 className="font-semibold text-gray-800 text-xs">{notif.title}</h3>
                      <p className="text-xs text-gray-600">{notif.message}</p>
                      <div className="flex items-center justify-between mt-1">
                        <span className="text-[10px] text-gray-400">{notif.date}</span>
                        <Button size="sm" variant="outline" className="text-[10px] h-6 px-2" onClick={() => navigate(notif.actionLink)}>
                          {notif.actionLabel}
                          <ChevronRight className="h-3 w-3 ml-0.5" />
                        </Button>
                      </div>
                    </div>
                  </div>
                </CardContent>
              </Card>
            )
          })}
        </div>
      )}
    </div>
  )
}
