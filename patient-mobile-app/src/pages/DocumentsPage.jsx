import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { FileText, Download, Search, Pill, TestTube, Shield, Camera } from 'lucide-react'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

const categoryIcons = {
  'Visit Notes': FileText,
  Prescriptions: Pill,
  'Lab Reports': TestTube,
  Consents: Shield,
  Imaging: Camera,
}

const tabs = ['All', 'Visit Notes', 'Prescriptions', 'Lab Reports', 'Consents', 'Imaging']

export default function DocumentsPage() {
  const navigate = useNavigate()
  const [activeTab, setActiveTab] = useState('All')
  const [search, setSearch] = useState('')

  const { data: summaries } = useApiData(() => portalService.getAfterVisitSummaries(), [])
  const { data: prescriptions } = useApiData(() => portalService.getPrescriptions(), [])
  const { data: labOrders } = useApiData(() => portalService.getLabOrders(), [])
  const { data: consents } = useApiData(() => portalService.getConsents(), [])
  const { data: imaging } = useApiData(() => portalService.getImagingOrders(), [])

  const docs = useMemo(() => ([
    ...(Array.isArray(summaries) ? summaries : []).map((item) => ({
      id: `visit-${item.id}`,
      title: item.chiefComplaint || item.department || 'After visit summary',
      provider: item.providerName || 'Care team',
      date: item.encounterDate,
      category: 'Visit Notes',
      downloadable: false,
    })),
    ...(Array.isArray(prescriptions) ? prescriptions : []).map((item) => ({
      id: `rx-${item.id}`,
      title: item.medicationName || item.name || 'Prescription',
      provider: item.prescribedBy || item.providerName || 'Prescriber',
      date: item.prescribedDate || item.createdAt,
      category: 'Prescriptions',
      downloadable: false,
    })),
    ...(Array.isArray(labOrders) ? labOrders : []).map((item) => ({
      id: `lab-${item.id}`,
      title: item.testName || item.name || 'Lab order',
      provider: item.orderingProviderName || item.department || 'Laboratory',
      date: item.orderDate || item.createdAt,
      category: 'Lab Reports',
      downloadable: false,
    })),
    ...(Array.isArray(consents) ? consents : []).map((item) => ({
      id: `consent-${item.id}`,
      title: `${item.fromHospitalName || 'Facility'} → ${item.toHospitalName || 'Facility'}`,
      provider: item.purpose || item.consentType || 'Data sharing consent',
      date: item.grantedAt,
      category: 'Consents',
      downloadable: false,
    })),
    ...(Array.isArray(imaging) ? imaging : []).map((item) => ({
      id: `img-${item.id}`,
      title: item.examName || item.name || 'Imaging order',
      provider: item.orderingProviderName || item.department || 'Radiology',
      date: item.orderDate || item.createdAt,
      category: 'Imaging',
      downloadable: false,
    })),
  ]), [summaries, prescriptions, labOrders, consents, imaging])

  const filtered = docs.filter((d) => {
    if (activeTab !== 'All' && d.category !== activeTab) return false
    if (search.trim()) {
      const q = search.toLowerCase()
      return `${d.title} ${d.provider}`.toLowerCase().includes(q)
    }
    return true
  })

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-gray-800">Documents</h2>
        <Button variant="outline" size="sm" onClick={() => navigate('/documents/consents')}>
          <Shield className="mr-1 h-4 w-4" /> Consents
        </Button>
      </div>

      <div className="grid grid-cols-2 gap-2">
        <Button variant="outline" onClick={() => portalService.downloadRecord('pdf')}><Download className="mr-2 h-4 w-4" /> Record PDF</Button>
        <Button variant="outline" onClick={() => portalService.downloadImmunizationCertificate()}><Download className="mr-2 h-4 w-4" /> Vaccine PDF</Button>
      </div>

      <div className="relative">
        <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
        <Input placeholder="Search documents…" className="pl-9" value={search} onChange={(e) => setSearch(e.target.value)} />
      </div>

      <div className="-mx-1 flex gap-2 overflow-x-auto px-1 pb-1">
        {tabs.map((tab) => (
          <Button key={tab} variant={activeTab === tab ? 'default' : 'outline'} size="sm" className={`h-8 shrink-0 text-xs ${activeTab === tab ? 'bg-blue-700' : ''}`} onClick={() => setActiveTab(tab)}>{tab}</Button>
        ))}
      </div>

      <div className="space-y-2">
        {filtered.map((doc) => {
          const Icon = categoryIcons[doc.category] || FileText
          return (
            <Card key={doc.id} className="transition-shadow hover:shadow-md">
              <CardContent className="flex items-start space-x-3 p-4">
                <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-blue-600"><Icon className="h-5 w-5 text-white" /></div>
                <div className="min-w-0 flex-1">
                  <h4 className="mb-0.5 text-sm font-medium text-gray-800">{doc.title}</h4>
                  <p className="text-xs text-gray-500">{doc.provider}</p>
                  <div className="mt-1 flex items-center justify-between">
                    <span className="text-xs text-gray-400">{formatDate(doc.date)}</span>
                    <Badge variant="outline" className="text-xs">{doc.category}</Badge>
                  </div>
                </div>
              </CardContent>
            </Card>
          )
        })}

        {filtered.length === 0 && (
          <div className="py-12 text-center"><FileText className="mx-auto mb-3 h-12 w-12 text-gray-300" /><p className="text-gray-500">No documents found</p></div>
        )}
      </div>
    </div>
  )
}

function formatDate(raw) {
  if (!raw) return '—'
  const d = new Date(raw)
  if (Number.isNaN(d.getTime())) return raw
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}
