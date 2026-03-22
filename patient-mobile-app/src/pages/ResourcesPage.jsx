import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { BookOpenText, Download, FileBarChart2, FlaskConical, Hospital, ScanLine, Search } from 'lucide-react'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

export default function ResourcesPage() {
  const [query, setQuery] = useState('')
  const { data: admission } = useApiData(() => portalService.getCurrentAdmission(), null)
  const { data: admissions } = useApiData(() => portalService.getAdmissions(), [])
  const { data: labOrders } = useApiData(() => portalService.getLabOrders(), [])
  const { data: imagingOrders } = useApiData(() => portalService.getImagingOrders(), [])
  const { data: procedures } = useApiData(() => portalService.getProcedureOrders(), [])
  const { data: vaccines } = useApiData(() => portalService.getUpcomingVaccinations(), [])
  const { data: education } = useApiData(
    () => query.trim() ? portalService.searchEducationResources(query.trim()) : portalService.getEducationResources(),
    [],
    [query],
  )

  return (
    <div className="space-y-5">
      <div>
        <h2 className="text-2xl font-bold text-slate-900">Records & resources</h2>
        <p className="mt-1 text-sm text-slate-500">Mobile access to admissions, diagnostic orders, downloads, and education resources.</p>
      </div>

      <section className="grid grid-cols-2 gap-3">
        <Button variant="outline" className="h-auto justify-start gap-2 p-4" onClick={() => portalService.downloadRecord('pdf')}>
          <Download className="h-4 w-4 text-blue-600" /> Download PDF record
        </Button>
        <Button variant="outline" className="h-auto justify-start gap-2 p-4" onClick={() => portalService.downloadImmunizationCertificate()}>
          <FileBarChart2 className="h-4 w-4 text-emerald-600" /> Vaccine certificate
        </Button>
      </section>

      {admission && (
        <Card className="border-l-4 border-l-blue-600">
          <CardContent className="space-y-2 p-4">
            <div className="flex items-center justify-between">
              <h3 className="font-semibold text-slate-900">Current admission</h3>
              <Badge className="bg-blue-100 text-blue-700">{admission.status || 'ACTIVE'}</Badge>
            </div>
            <p className="text-sm text-slate-600">{admission.departmentName || admission.facility || admission.roomNumber || 'Inpatient stay in progress'}</p>
          </CardContent>
        </Card>
      )}

      <Section title="Recent admissions" icon={Hospital} items={admissions} render={(item) => (
        <ItemCard title={item.reason || item.departmentName || 'Admission'} subtitle={item.facility || item.roomNumber || item.admittingDoctorName || 'Hospital stay'} badge={item.status || 'RECORDED'} />
      )} />
      <Section title="Lab orders" icon={FlaskConical} items={labOrders} render={(item) => (
        <ItemCard title={item.testName || item.name || 'Lab order'} subtitle={item.orderDate || item.collectedDate || item.department || 'Pending lab work'} badge={item.status || 'ORDERED'} />
      )} />
      <Section title="Imaging & procedures" icon={ScanLine} items={[...(imagingOrders || []), ...(procedures || [])]} render={(item) => (
        <ItemCard title={item.examName || item.procedureName || item.name || 'Diagnostic order'} subtitle={item.scheduledDate || item.orderDate || item.department || 'Care team order'} badge={item.status || 'ACTIVE'} />
      )} />
      <Section title="Upcoming vaccines" icon={FileBarChart2} items={vaccines} render={(item) => (
        <ItemCard title={item.vaccineName || item.name || 'Immunization'} subtitle={item.dateAdministered || item.scheduledDate || 'Due soon'} badge={item.status || 'DUE'} />
      )} />

      <section className="space-y-3">
        <div className="flex items-center gap-2">
          <BookOpenText className="h-4 w-4 text-blue-600" />
          <h3 className="font-semibold text-slate-900">Education library</h3>
        </div>
        <div className="relative">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <Input className="pl-9" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search education resources…" />
        </div>
        <div className="space-y-2">
          {(Array.isArray(education) ? education : []).slice(0, 8).map((resource) => (
            <Card key={resource.id}>
              <CardContent className="space-y-1 p-4">
                <h4 className="font-semibold text-slate-900">{resource.title || resource.name || 'Education resource'}</h4>
                <p className="text-sm text-slate-500">{resource.summary || resource.description || resource.category || 'Patient learning material'}</p>
              </CardContent>
            </Card>
          ))}
        </div>
      </section>
    </div>
  )
}

function Section({ title, icon, items, render }) {
  const Glyph = icon
  const safeItems = Array.isArray(items) ? items : []
  return (
    <section className="space-y-3">
      <div className="flex items-center gap-2">
        <Glyph className="h-4 w-4 text-blue-600" />
        <h3 className="font-semibold text-slate-900">{title}</h3>
      </div>
      {safeItems.length > 0 ? safeItems.slice(0, 5).map((item, index) => <div key={item.id || index}>{render(item)}</div>) : (
        <Card><CardContent className="p-4 text-sm text-slate-500">No records available.</CardContent></Card>
      )}
    </section>
  )
}

function ItemCard({ title, subtitle, badge }) {
  return (
    <Card>
      <CardContent className="flex items-start justify-between gap-3 p-4">
        <div>
          <h4 className="font-semibold text-slate-900">{title}</h4>
          <p className="text-sm text-slate-500">{subtitle}</p>
        </div>
        <Badge className="bg-slate-100 text-slate-700">{badge}</Badge>
      </CardContent>
    </Card>
  )
}
