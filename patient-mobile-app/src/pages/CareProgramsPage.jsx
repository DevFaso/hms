import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Activity, HeartPulse, NotebookPen, RefreshCw, Stethoscope } from 'lucide-react'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

export default function CareProgramsPage() {
  const [score, setScore] = useState('')
  const [notes, setNotes] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const { data: plans } = useApiData(() => portalService.getTreatmentPlans(), [])
  const { data: consultations } = useApiData(() => portalService.getConsultations(), [])
  const { data: referrals } = useApiData(() => portalService.getReferrals(), [])
  const { data: reminders, refetch: refetchReminders } = useApiData(() => portalService.getHealthReminders(), [])
  const { data: outcomes, refetch: refetchOutcomes } = useApiData(() => portalService.getOutcomes(), [])

  const handleReminderComplete = async (id) => {
    await portalService.completeHealthReminder(id)
    await refetchReminders()
  }

  const handleOutcomeSubmit = async () => {
    if (!score.trim()) return
    setSubmitting(true)
    try {
      await portalService.reportOutcome({ metricName: 'Daily check-in', score: Number(score), notes })
      setScore('')
      setNotes('')
      await refetchOutcomes()
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="space-y-5">
      <div>
        <h2 className="text-2xl font-bold text-slate-900">Care programs</h2>
        <p className="mt-1 text-sm text-slate-500">Treatment plans, referrals, consultations, reminders, and outcomes in one mobile workflow.</p>
      </div>

      <Section title="Treatment plans" icon={HeartPulse}>
        {(Array.isArray(plans) ? plans : []).slice(0, 4).map((plan) => (
          <RecordCard
            key={plan.id}
            title={plan.title || plan.name || 'Treatment plan'}
            subtitle={plan.goal || plan.description || plan.status || 'Active plan'}
            badge={plan.status || 'ACTIVE'}
          />
        ))}
      </Section>

      <Section title="Health reminders" icon={Activity}>
        {(Array.isArray(reminders) ? reminders : []).slice(0, 5).map((reminder) => {
          const completed = ['COMPLETED', 'DISMISSED'].includes((reminder.status || '').toUpperCase())
          return (
            <Card key={reminder.id}>
              <CardContent className="space-y-3 p-4">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <h3 className="font-semibold text-slate-900">{reminder.title || reminder.name || 'Health reminder'}</h3>
                    <p className="text-sm text-slate-500">{reminder.description || reminder.recommendation || reminder.type || 'Preventive care task'}</p>
                  </div>
                  <Badge className={completed ? 'bg-slate-100 text-slate-700' : 'bg-amber-100 text-amber-700'}>{reminder.status || 'OPEN'}</Badge>
                </div>
                {!completed && (
                  <Button size="sm" className="bg-emerald-600 hover:bg-emerald-700" onClick={() => handleReminderComplete(reminder.id)}>
                    Mark complete
                  </Button>
                )}
              </CardContent>
            </Card>
          )
        })}
      </Section>

      <Section title="Consultations & referrals" icon={Stethoscope}>
        {[...(Array.isArray(consultations) ? consultations : []).slice(0, 2), ...(Array.isArray(referrals) ? referrals : []).slice(0, 2)].map((item, index) => (
          <RecordCard
            key={item.id || index}
            title={item.providerName || item.consultantName || item.specialistName || item.referralType || 'Care coordination'}
            subtitle={item.reason || item.department || item.specialty || item.status || 'Clinical follow-up'}
            badge={item.status || 'OPEN'}
          />
        ))}
      </Section>

      <Section title="Patient-reported outcomes" icon={NotebookPen}>
        <Card>
          <CardContent className="space-y-3 p-4">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="text-xs font-medium text-slate-500">Score (1-10)</label>
                <Input value={score} onChange={(e) => setScore(e.target.value)} inputMode="numeric" placeholder="7" />
              </div>
              <div>
                <label className="text-xs font-medium text-slate-500">Metric</label>
                <Input value="Daily check-in" disabled />
              </div>
            </div>
            <div>
              <label className="text-xs font-medium text-slate-500">Notes</label>
              <Textarea rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Pain, energy, sleep, or recovery notes…" />
            </div>
            <Button className="w-full bg-blue-700 hover:bg-blue-800" disabled={submitting || !score.trim()} onClick={handleOutcomeSubmit}>
              {submitting ? <RefreshCw className="mr-2 h-4 w-4 animate-spin" /> : null}
              Log outcome
            </Button>
          </CardContent>
        </Card>
        {(Array.isArray(outcomes) ? outcomes : []).slice(0, 3).map((entry) => (
          <RecordCard
            key={entry.id}
            title={entry.metricName || entry.name || 'Outcome entry'}
            subtitle={entry.notes || entry.observation || 'Patient-reported update'}
            badge={entry.score !== undefined && entry.score !== null ? `Score ${entry.score}` : (entry.status || 'Saved')}
          />
        ))}
      </Section>
    </div>
  )
}

function Section({ title, icon, children }) {
  const Glyph = icon
  const items = Array.isArray(children) ? children.filter(Boolean) : [children]
  return (
    <section className="space-y-3">
      <div className="flex items-center gap-2">
        <Glyph className="h-4 w-4 text-blue-600" />
        <h3 className="font-semibold text-slate-900">{title}</h3>
      </div>
      {items.length > 0 ? items : <Card><CardContent className="p-4 text-sm text-slate-500">No data available yet.</CardContent></Card>}
    </section>
  )
}

function RecordCard({ title, subtitle, badge }) {
  return (
    <Card>
      <CardContent className="flex items-start justify-between gap-3 p-4">
        <div>
          <h4 className="font-semibold text-slate-900">{title}</h4>
          <p className="text-sm text-slate-500">{subtitle}</p>
        </div>
        <Badge className="bg-blue-100 text-blue-700">{badge}</Badge>
      </CardContent>
    </Card>
  )
}
