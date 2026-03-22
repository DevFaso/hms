import { useMemo, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Badge } from '@/components/ui/badge'
import { ClipboardList, Send } from 'lucide-react'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

export default function QuestionnairesPage() {
  const [activeId, setActiveId] = useState(null)
  const [answers, setAnswers] = useState({})
  const { data: pending, refetch: refetchPending } = useApiData(() => portalService.getPendingQuestionnaires(), [])
  const { data: submitted, refetch: refetchSubmitted } = useApiData(() => portalService.getSubmittedQuestionnaires(), [])

  const active = useMemo(() => (Array.isArray(pending) ? pending : []).find((item) => item.id === activeId) || null, [pending, activeId])

  const updateAnswer = (questionId, value) => setAnswers((current) => ({ ...current, [questionId]: value }))

  const submitActive = async () => {
    if (!active) return
    await portalService.submitQuestionnaire({ questionnaireId: active.id, answers })
    setAnswers({})
    setActiveId(null)
    await Promise.all([refetchPending(), refetchSubmitted()])
  }

  return (
    <div className="space-y-5">
      <div>
        <h2 className="text-2xl font-bold text-slate-900">Pre-visit questionnaires</h2>
        <p className="mt-1 text-sm text-slate-500">Fill forms ahead of time so clinic staff already has your answers when you arrive.</p>
      </div>

      <section className="space-y-3">
        <h3 className="font-semibold text-slate-900">Pending</h3>
        {(Array.isArray(pending) ? pending : []).map((form) => (
          <Card key={form.id}>
            <CardContent className="space-y-3 p-4">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <h4 className="font-semibold text-slate-900">{form.title || form.name || 'Questionnaire'}</h4>
                  <p className="text-sm text-slate-500">{form.description || `${form.questions?.length || 0} questions`}</p>
                </div>
                <Badge className="bg-amber-100 text-amber-700">Pending</Badge>
              </div>
              <Button size="sm" className="bg-blue-700 hover:bg-blue-800" onClick={() => setActiveId(form.id)}>
                Open form
              </Button>
            </CardContent>
          </Card>
        ))}
      </section>

      {active && (
        <section className="space-y-3">
          <div className="flex items-center gap-2">
            <ClipboardList className="h-4 w-4 text-blue-600" />
            <h3 className="font-semibold text-slate-900">{active.title || active.name}</h3>
          </div>
          {(active.questions || []).map((question) => (
            <Card key={question.id}>
              <CardContent className="space-y-2 p-4">
                <p className="text-sm font-medium text-slate-900">{question.prompt || question.label || question.questionText}</p>
                {(question.type || '').toUpperCase() === 'TEXT' || !(question.options?.length)
                  ? <Textarea rows={3} value={answers[question.id] || ''} onChange={(e) => updateAnswer(question.id, e.target.value)} placeholder="Type your answer…" />
                  : <Input value={answers[question.id] || ''} onChange={(e) => updateAnswer(question.id, e.target.value)} placeholder={`Options: ${question.options.join(', ')}`} />}
              </CardContent>
            </Card>
          ))}
          <Button className="w-full bg-emerald-600 hover:bg-emerald-700" onClick={submitActive}>
            <Send className="mr-2 h-4 w-4" /> Submit questionnaire
          </Button>
        </section>
      )}

      <section className="space-y-3">
        <h3 className="font-semibold text-slate-900">Submitted</h3>
        {(Array.isArray(submitted) ? submitted : []).map((item) => (
          <Card key={item.id}>
            <CardContent className="flex items-start justify-between gap-3 p-4">
              <div>
                <h4 className="font-semibold text-slate-900">{item.questionnaireTitle || item.title || 'Submitted questionnaire'}</h4>
                <p className="text-sm text-slate-500">{item.submittedAt || item.createdAt || 'Previously submitted'}</p>
              </div>
              <Badge className="bg-emerald-100 text-emerald-700">Submitted</Badge>
            </CardContent>
          </Card>
        ))}
      </section>
    </div>
  )
}
