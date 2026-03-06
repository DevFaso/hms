import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Eye, Download } from 'lucide-react'
import { testResults as mockResults } from '@/data/testResults'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

export default function LabResultsPage() {
  const { data: raw } = useApiData(
    () => portalService.getLabResults(),
    mockResults,
  )

  // Normalize API shape → local shape
  const testResults = (raw || []).map((r) => {
    // If it came from the API (has testName), reshape
    if (r.testName) {
      return {
        id: r.id,
        test: r.testName,
        date: r.resultedAt ? new Date(r.resultedAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : '',
        status: r.status === 'FINAL' ? 'Reviewed' : 'New',
        provider: r.performedBy || r.orderedBy || 'Lab',
        details: r.value ? { [r.testCode || r.testName]: { value: r.value, unit: r.unit || '', range: r.referenceRange || '—', flag: r.notes || 'Normal' } } : null,
      }
    }
    // Already in mock shape
    return r
  })
  return (
    <div className="space-y-6">
      <h2 className="text-xl font-bold text-gray-800">Test Results</h2>

      <div className="space-y-3">
        {testResults.map((result) => (
          <Card key={result.id} className="hover:shadow-md transition-shadow">
            <CardContent className="p-4">
              <div className="flex justify-between items-start mb-3">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center space-x-2 mb-1">
                    <h4 className="font-semibold text-gray-800">{result.test}</h4>
                    <Badge
                      variant={result.status === 'New' ? 'default' : 'secondary'}
                      className={
                        result.status === 'New'
                          ? 'bg-blue-600 text-xs'
                          : 'bg-gray-100 text-gray-600 text-xs'
                      }
                    >
                      {result.status}
                    </Badge>
                  </div>
                  <p className="text-sm text-gray-600">{result.provider}</p>
                  <p className="text-sm text-gray-500">{result.date}</p>
                </div>
              </div>

              {/* Result details */}
              {result.details && (
                <div className="bg-gray-50 rounded-lg p-3 mb-3">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-gray-500 text-xs">
                        <th className="text-left pb-1">Test</th>
                        <th className="text-right pb-1">Value</th>
                        <th className="text-right pb-1">Range</th>
                        <th className="text-right pb-1">Flag</th>
                      </tr>
                    </thead>
                    <tbody>
                      {Object.entries(result.details).map(([key, detail]) => (
                        <tr key={key} className="border-t border-gray-200">
                          <td className="py-1.5 text-gray-700 capitalize">
                            {key.replace(/([A-Z])/g, ' $1').trim()}
                          </td>
                          <td className="py-1.5 text-right font-medium text-gray-800">
                            {detail.value} <span className="text-gray-400 text-xs">{detail.unit}</span>
                          </td>
                          <td className="py-1.5 text-right text-gray-500 text-xs">{detail.range}</td>
                          <td className="py-1.5 text-right">
                            <span
                              className={`text-xs font-medium ${
                                detail.flag === 'High' || detail.flag === 'Low'
                                  ? 'text-red-600'
                                  : 'text-green-600'
                              }`}
                            >
                              {detail.flag}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              <div className="flex space-x-2">
                <Button size="sm" variant="outline" className="text-xs h-8">
                  <Eye className="h-3 w-3 mr-1" />
                  Full Report
                </Button>
                <Button size="sm" variant="outline" className="text-xs h-8">
                  <Download className="h-3 w-3 mr-1" />
                  Download
                </Button>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  )
}

