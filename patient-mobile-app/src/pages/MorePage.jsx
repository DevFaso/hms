import { useNavigate } from 'react-router-dom'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import {
  ClipboardCheck, HeartPulse, Library, Users, FilePenLine, Shield,
  ChevronRight, BellRing, Activity
} from 'lucide-react'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

const modules = [
  {
    path: '/check-in',
    title: 'Mobile Check-In',
    description: 'Check in for today’s appointments from your phone.',
    icon: ClipboardCheck,
    color: 'bg-emerald-500',
  },
  {
    path: '/care-programs',
    title: 'Care Programs',
    description: 'Treatment plans, reminders, referrals, and outcomes.',
    icon: HeartPulse,
    color: 'bg-blue-600',
  },
  {
    path: '/resources',
    title: 'Records & Resources',
    description: 'Admissions, orders, downloads, trends, and education.',
    icon: Library,
    color: 'bg-violet-600',
  },
  {
    path: '/family-access',
    title: 'Family Access',
    description: 'Proxy access, sharing preferences, and notification channels.',
    icon: Users,
    color: 'bg-amber-500',
  },
  {
    path: '/questionnaires',
    title: 'Questionnaires',
    description: 'Complete pre-visit forms before you arrive.',
    icon: FilePenLine,
    color: 'bg-rose-500',
  },
  {
    path: '/sharing',
    title: 'Sharing & Privacy',
    description: 'Review record-sharing consents and audit access logs.',
    icon: Shield,
    color: 'bg-slate-700',
  },
]

export default function MorePage() {
  const navigate = useNavigate()
  const { data: reminders } = useApiData(() => portalService.getHealthReminders(), [])
  const { data: questionnaires } = useApiData(() => portalService.getPendingQuestionnaires(), [])
  const { data: preferences } = useApiData(() => portalService.getNotificationPreferences(), [])

  const pendingReminders = (Array.isArray(reminders) ? reminders : []).filter((r) => !['COMPLETED', 'DISMISSED'].includes((r.status || '').toUpperCase()))
  const pendingForms = Array.isArray(questionnaires) ? questionnaires.length : 0
  const enabledChannels = (Array.isArray(preferences) ? preferences : []).filter((item) => item.enabled !== false).length

  const stats = [
    { label: 'Reminders', value: pendingReminders.length, icon: Activity },
    { label: 'Forms', value: pendingForms, icon: FilePenLine },
    { label: 'Alerts', value: enabledChannels, icon: BellRing },
  ]

  return (
    <div className="space-y-5">
      <div>
        <p className="text-xs font-semibold uppercase tracking-[0.2em] text-blue-600">Mobile features</p>
        <h2 className="mt-1 text-2xl font-bold text-slate-900">More patient tools</h2>
        <p className="mt-1 text-sm text-slate-500">
          These sections are wired to the same patient-portal backend capabilities used by the main portal.
        </p>
      </div>

      <div className="grid grid-cols-3 gap-3">
        {stats.map((item) => (
          <Card key={item.label}>
            <CardContent className="space-y-2 p-4 text-center">
              <item.icon className="mx-auto h-5 w-5 text-blue-600" />
              <p className="text-2xl font-bold text-slate-900">{item.value}</p>
              <p className="text-xs text-slate-500">{item.label}</p>
            </CardContent>
          </Card>
        ))}
      </div>

      <div className="space-y-3">
        {modules.map((module) => (
          <Card key={module.path} className="cursor-pointer transition-shadow hover:shadow-md" onClick={() => navigate(module.path)}>
            <CardContent className="flex items-center gap-4 p-4">
              <div className={`${module.color} flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl`}>
                <module.icon className="h-6 w-6 text-white" />
              </div>
              <div className="min-w-0 flex-1">
                <h3 className="font-semibold text-slate-900">{module.title}</h3>
                <p className="text-sm text-slate-500">{module.description}</p>
              </div>
              <Button variant="ghost" size="icon" className="shrink-0">
                <ChevronRight className="h-5 w-5 text-slate-400" />
              </Button>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  )
}
