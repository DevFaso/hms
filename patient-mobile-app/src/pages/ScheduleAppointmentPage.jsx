import { useEffect, useMemo, useReducer, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Calendar } from '@/components/ui/calendar'
import {
  Search, ChevronRight, ChevronLeft, CheckCircle, Calendar as CalendarIcon,
  Clock, User, MapPin, Building2
} from 'lucide-react'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

const initialState = {
  step: 1,
  department: null,
  provider: null,
  date: null,
  time: null,
  reason: '',
  notes: '',
}

function reducer(state, action) {
  switch (action.type) {
    case 'SET_DEPARTMENT': return { ...state, department: action.payload, provider: null, step: 2 }
    case 'SET_PROVIDER': return { ...state, provider: action.payload, step: 3 }
    case 'SET_DATE': return { ...state, date: action.payload }
    case 'SET_TIME': return { ...state, time: action.payload }
    case 'NEXT': return { ...state, step: state.step + 1 }
    case 'BACK': return { ...state, step: Math.max(1, state.step - 1) }
    case 'SET_REASON': return { ...state, reason: action.payload }
    case 'SET_NOTES': return { ...state, notes: action.payload }
    case 'RESET': return initialState
    default: return state
  }
}

const timeSlots = ['08:00', '08:30', '09:00', '09:30', '10:00', '10:30', '11:00', '13:00', '13:30', '14:00', '14:30', '15:00', '15:30']

export default function ScheduleAppointmentPage() {
  const navigate = useNavigate()
  const [state, dispatch] = useReducer(reducer, initialState)
  const [search, setSearch] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [done, setDone] = useState(false)

  const { data: departments } = useApiData(() => portalService.getDepartments(), [])
  const { data: providers, refetch: refetchProviders } = useApiData(
    () => state.department?.id ? portalService.getDepartmentProviders(state.department.id) : Promise.resolve([]),
    [],
    [state.department?.id],
  )

  useEffect(() => {
    if (state.department?.id) refetchProviders()
  }, [state.department?.id, refetchProviders])

  const filteredDepartments = useMemo(() => {
    const list = Array.isArray(departments) ? departments : []
    if (!search.trim()) return list
    return list.filter((dept) => `${dept.name || dept.departmentName || ''}`.toLowerCase().includes(search.toLowerCase()))
  }, [departments, search])

  const filteredProviders = useMemo(() => {
    const list = Array.isArray(providers) ? providers : []
    if (!search.trim()) return list
    return list.filter((provider) => `${provider.fullName || provider.name || ''} ${(provider.specialty || '')}`.toLowerCase().includes(search.toLowerCase()))
  }, [providers, search])

  const handleSubmit = async () => {
    setSubmitting(true)
    try {
      await portalService.bookAppointment({
        departmentId: state.department?.id,
        providerId: state.provider?.id,
        staffId: state.provider?.id,
        appointmentDate: state.date?.toISOString().split('T')[0],
        startTime: state.time,
        reason: state.reason,
        notes: state.notes,
      })
      setDone(true)
    } finally {
      setSubmitting(false)
    }
  }

  if (done) {
    return (
      <div className="flex flex-col items-center justify-center space-y-4 py-16">
        <div className="rounded-full bg-green-100 p-4"><CheckCircle className="h-12 w-12 text-green-600" /></div>
        <h2 className="text-xl font-bold text-gray-800">Appointment request sent</h2>
        <Card className="w-full"><CardContent className="space-y-1 p-4 text-center">
          <p className="font-semibold text-gray-800">{state.provider?.fullName || state.provider?.name}</p>
          <p className="text-sm text-gray-500">{state.department?.name || state.department?.departmentName}</p>
          <p className="text-sm font-medium text-blue-700">{state.date?.toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' })}</p>
          <p className="text-sm font-medium text-blue-700">{state.time}</p>
        </CardContent></Card>
        <div className="flex space-x-3 pt-2">
          <Button variant="outline" onClick={() => navigate('/appointments')}>View Appointments</Button>
          <Button className="bg-blue-700 hover:bg-blue-800" onClick={() => { dispatch({ type: 'RESET' }); setDone(false) }}>Book Another</Button>
        </div>
      </div>
    )
  }

  const StepIndicator = () => (
    <div className="mb-6 flex items-center justify-center space-x-2">
      {[1, 2, 3, 4].map((s) => (
        <div key={s} className="flex items-center">
          <div className={`flex h-8 w-8 items-center justify-center rounded-full text-sm font-medium ${s <= state.step ? 'bg-blue-700 text-white' : 'bg-gray-200 text-gray-500'}`}>{s}</div>
          {s < 4 && <div className={`h-0.5 w-8 ${s < state.step ? 'bg-blue-700' : 'bg-gray-200'}`} />}
        </div>
      ))}
    </div>
  )

  if (state.step === 1) {
    return (
      <div className="space-y-4">
        <h2 className="text-xl font-bold text-gray-800">Schedule Appointment</h2>
        <StepIndicator />
        <h3 className="font-semibold text-gray-700">Choose a department</h3>
        <div className="relative"><Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" /><Input placeholder="Search departments…" className="pl-9" value={search} onChange={(e) => setSearch(e.target.value)} /></div>
        <div className="space-y-2">
          {filteredDepartments.map((dept) => (
            <Card key={dept.id} className="cursor-pointer hover:shadow-md" onClick={() => { setSearch(''); dispatch({ type: 'SET_DEPARTMENT', payload: dept }) }}>
              <CardContent className="flex items-center space-x-3 p-4">
                <div className="flex h-10 w-10 items-center justify-center rounded-full bg-blue-100"><Building2 className="h-5 w-5 text-blue-700" /></div>
                <div className="flex-1"><p className="font-medium text-gray-800 text-sm">{dept.name || dept.departmentName}</p><p className="text-xs text-gray-500">Select providers in this department</p></div>
                <ChevronRight className="h-4 w-4 text-gray-300" />
              </CardContent>
            </Card>
          ))}
        </div>
      </div>
    )
  }

  if (state.step === 2) {
    return (
      <div className="space-y-4">
        <div className="flex items-center space-x-2"><Button variant="ghost" size="icon" className="h-8 w-8" onClick={() => dispatch({ type: 'BACK' })}><ChevronLeft className="h-4 w-4" /></Button><h2 className="text-xl font-bold text-gray-800">Choose a provider</h2></div>
        <StepIndicator />
        <Card><CardContent className="p-3"><p className="text-sm font-medium text-blue-700">{state.department?.name || state.department?.departmentName}</p></CardContent></Card>
        <div className="relative"><Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" /><Input placeholder="Search providers…" className="pl-9" value={search} onChange={(e) => setSearch(e.target.value)} /></div>
        <div className="space-y-2">
          {filteredProviders.map((provider) => (
            <Card key={provider.id} className="cursor-pointer hover:shadow-md" onClick={() => { setSearch(''); dispatch({ type: 'SET_PROVIDER', payload: provider }) }}>
              <CardContent className="flex items-center space-x-3 p-4">
                <div className="flex h-10 w-10 items-center justify-center rounded-full bg-blue-100"><span className="text-sm font-semibold text-blue-700">{(provider.fullName || provider.name || 'P').split(' ').map((w) => w[0]).join('').slice(0, 2)}</span></div>
                <div className="flex-1"><p className="text-sm font-medium text-gray-800">{provider.fullName || provider.name}</p><p className="text-xs text-gray-500">{provider.specialty || provider.role || 'Provider'}</p></div>
                <ChevronRight className="h-4 w-4 text-gray-300" />
              </CardContent>
            </Card>
          ))}
        </div>
      </div>
    )
  }

  if (state.step === 3) {
    return (
      <div className="space-y-4">
        <div className="flex items-center space-x-2"><Button variant="ghost" size="icon" className="h-8 w-8" onClick={() => dispatch({ type: 'BACK' })}><ChevronLeft className="h-4 w-4" /></Button><h2 className="text-xl font-bold text-gray-800">Pick date & time</h2></div>
        <StepIndicator />
        <Card><CardContent className="flex items-center space-x-3 p-3"><User className="h-4 w-4 text-blue-600" /><span className="text-sm font-medium">{state.provider?.fullName || state.provider?.name}</span><Badge variant="outline" className="ml-auto text-xs">{state.department?.name || state.department?.departmentName}</Badge></CardContent></Card>
        <div className="flex justify-center"><Calendar mode="single" selected={state.date} onSelect={(d) => dispatch({ type: 'SET_DATE', payload: d })} disabled={(d) => d < new Date() || d.getDay() === 0 || d.getDay() === 6} className="rounded-md border" /></div>
        {state.date && <div><h3 className="mb-2 flex items-center text-sm font-semibold text-gray-700"><Clock className="mr-1 h-4 w-4" />Available times</h3><div className="grid grid-cols-4 gap-2">{timeSlots.map((slot) => <Button key={slot} variant={state.time === slot ? 'default' : 'outline'} size="sm" className={`h-9 text-xs ${state.time === slot ? 'bg-blue-700' : ''}`} onClick={() => dispatch({ type: 'SET_TIME', payload: slot })}>{slot}</Button>)}</div></div>}
        <Button className="w-full bg-blue-700 hover:bg-blue-800" disabled={!state.date || !state.time} onClick={() => dispatch({ type: 'NEXT' })}>Continue<ChevronRight className="ml-1 h-4 w-4" /></Button>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center space-x-2"><Button variant="ghost" size="icon" className="h-8 w-8" onClick={() => dispatch({ type: 'BACK' })}><ChevronLeft className="h-4 w-4" /></Button><h2 className="text-xl font-bold text-gray-800">Confirm appointment</h2></div>
      <StepIndicator />
      <Card className="border-blue-200 bg-blue-50"><CardContent className="space-y-3 p-4"><div className="flex items-center space-x-2"><User className="h-4 w-4 text-blue-600" /><span className="font-semibold text-gray-800">{state.provider?.fullName || state.provider?.name}</span></div><div className="flex items-center space-x-2 text-sm text-blue-700"><CalendarIcon className="h-4 w-4" /><span>{state.date?.toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' })}</span></div><div className="flex items-center space-x-2 text-sm text-blue-700"><Clock className="h-4 w-4" /><span>{state.time}</span></div>{state.department && <div className="flex items-center space-x-2 text-sm text-gray-600"><MapPin className="h-4 w-4" /><span>{state.department?.name || state.department?.departmentName}</span></div>}</CardContent></Card>
      <div className="space-y-3"><div><Label className="text-sm">Reason for visit</Label><Input placeholder="e.g., Follow-up, Annual physical" value={state.reason} onChange={(e) => dispatch({ type: 'SET_REASON', payload: e.target.value })} /></div><div><Label className="text-sm">Additional notes (optional)</Label><Textarea placeholder="Any symptoms or questions for the provider…" rows={3} value={state.notes} onChange={(e) => dispatch({ type: 'SET_NOTES', payload: e.target.value })} /></div></div>
      <Button className="w-full bg-blue-700 hover:bg-blue-800" disabled={submitting || !state.reason.trim()} onClick={handleSubmit}><CheckCircle className="mr-2 h-4 w-4" />{submitting ? 'Submitting…' : 'Confirm & request appointment'}</Button>
    </div>
  )
}
