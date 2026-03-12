import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import {
  FileText, Download, Search, Pill, TestTube,
  Shield, Camera
} from 'lucide-react'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

const categoryIcons = {
  'Visit Notes': FileText,
  Prescriptions: Pill,
  'Lab Reports': TestTube,
  Consents: Shield,
  Imaging: Camera,
}

const categoryColors = {
  'Visit Notes': 'bg-blue-500',
  Prescriptions: 'bg-teal-500',
  'Lab Reports': 'bg-purple-500',
  Consents: 'bg-green-500',
  Imaging: 'bg-orange-500',
}

const tabs = ['All', 'Visit Notes', 'Prescriptions', 'Lab Reports', 'Consents', 'Imaging']

export default function DocumentsPage() {
  const navigate = useNavigate()
  const [activeTab, setActiveTab] = useState('All')
  const [search, setSearch] = useState('')

  const { data: documents } = useApiData(
    () => portalService.getDocuments(),
    [],
  )

  const filtered = (documents || []).filter((d) => {
    if (activeTab !== 'All' && d.category !== activeTab) return false
    if (search.trim()) {
      const q = search.toLowerCase()
      return d.title.toLowerCase().includes(q) || d.provider.toLowerCase().includes(q)
    }
    return true
  })

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-gray-800">Documents</h2>
        <Button
          variant="outline"
          size="sm"
          onClick={() => navigate('/documents/consents')}
        >
          <Shield className="h-4 w-4 mr-1" />
          Consents
        </Button>
      </div>

      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" />
        <Input
          placeholder="Search documents…"
          className="pl-9"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>

      {/* Tabs */}
      <div className="flex overflow-x-auto gap-2 pb-1 -mx-1 px-1">
        {tabs.map((tab) => (
          <Button
            key={tab}
            variant={activeTab === tab ? 'default' : 'outline'}
            size="sm"
            className={`shrink-0 text-xs h-8 ${activeTab === tab ? 'bg-blue-700' : ''}`}
            onClick={() => setActiveTab(tab)}
          >
            {tab}
          </Button>
        ))}
      </div>

      {/* Document list */}
      <div className="space-y-2">
        {filtered.map((doc) => {
          const Icon = categoryIcons[doc.category] || FileText
          const color = categoryColors[doc.category] || 'bg-gray-500'

          return (
            <Card key={doc.id} className="hover:shadow-md transition-shadow">
              <CardContent className="p-4">
                <div className="flex items-start space-x-3">
                  <div className={`${color} w-10 h-10 rounded-lg flex items-center justify-center shrink-0`}>
                    <Icon className="h-5 w-5 text-white" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <h4 className="font-medium text-gray-800 text-sm mb-0.5">{doc.title}</h4>
                    <p className="text-xs text-gray-500">{doc.provider}</p>
                    <div className="flex items-center justify-between mt-1">
                      <span className="text-xs text-gray-400">
                        {new Date(doc.date).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}
                      </span>
                      <Badge variant="outline" className="text-xs">{doc.category}</Badge>
                    </div>
                  </div>
                  {doc.downloadable && (
                    <Button variant="ghost" size="icon" className="h-8 w-8 shrink-0">
                      <Download className="h-4 w-4 text-blue-600" />
                    </Button>
                  )}
                </div>
              </CardContent>
            </Card>
          )
        })}

        {filtered.length === 0 && (
          <div className="text-center py-12">
            <FileText className="h-12 w-12 text-gray-300 mx-auto mb-3" />
            <p className="text-gray-500">No documents found</p>
          </div>
        )}
      </div>
    </div>
  )
}

