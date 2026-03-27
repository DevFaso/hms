import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import {
  Heart, Thermometer, Weight, Wind, Activity, Droplets
} from 'lucide-react'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

const vitalIconMap = {
  BLOOD_PRESSURE: Heart,
  HEART_RATE: Activity,
  TEMPERATURE: Thermometer,
  WEIGHT: Weight,
  RESPIRATORY_RATE: Wind,
  OXYGEN_SATURATION: Droplets,
  BMI: Activity,
  HEIGHT: Activity,
}

const vitalColorMap = {
  BLOOD_PRESSURE: 'bg-red-50 text-red-600',
  HEART_RATE: 'bg-pink-50 text-pink-600',
  TEMPERATURE: 'bg-orange-50 text-orange-600',
  WEIGHT: 'bg-blue-50 text-blue-600',
  RESPIRATORY_RATE: 'bg-teal-50 text-teal-600',
  OXYGEN_SATURATION: 'bg-cyan-50 text-cyan-600',
  BMI: 'bg-purple-50 text-purple-600',
  HEIGHT: 'bg-indigo-50 text-indigo-600',
}

function formatType(type) {
  if (!type) return 'Vital'
  return type
    .replaceAll('_', ' ')
    .replaceAll(/\b\w/g, (c) => c.toUpperCase())
}

export default function VitalsPage() {
  const { data: raw } = useApiData(
    () => portalService.getVitals(),
    [],
  )

  const vitals = Array.isArray(raw) ? raw : []

  return (
    <div className="space-y-5">
      <h2 className="text-xl font-bold text-gray-800">My Vitals</h2>

      {vitals.length === 0 ? (
        <div className="text-center py-12">
          <Heart className="h-12 w-12 text-gray-300 mx-auto mb-3" />
          <p className="text-gray-500 text-sm">No vital signs recorded</p>
          <p className="text-gray-400 text-xs mt-1">Your vital sign readings will appear here.</p>
        </div>
      ) : (
        <div className="grid grid-cols-2 gap-3">
          {vitals.map((v) => {
            const typeKey = (v.type || '').toUpperCase()
            const Icon = vitalIconMap[typeKey] || Heart
            const colorClass = vitalColorMap[typeKey] || 'bg-gray-50 text-gray-600'

            return (
              <Card key={v.id} className="hover:shadow-md transition-shadow">
                <CardContent className="p-4">
                  <div className="flex items-center space-x-2 mb-2">
                    <div className={`rounded-lg p-2 ${colorClass}`}>
                      <Icon className="h-4 w-4" />
                    </div>
                    <span className="text-[11px] font-semibold text-gray-500 uppercase tracking-wide">
                      {formatType(v.type)}
                    </span>
                  </div>
                  <p className="text-2xl font-bold text-gray-800">
                    {v.value} <span className="text-xs font-normal text-gray-400">{v.unit}</span>
                  </p>
                  <div className="flex items-center justify-between mt-2">
                    <span className="text-[10px] text-gray-400">
                      {v.recordedAt
                        ? new Date(v.recordedAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
                        : ''}
                    </span>
                    <Badge className="text-[10px] bg-gray-100 text-gray-500">
                      {v.source || 'Clinical'}
                    </Badge>
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
