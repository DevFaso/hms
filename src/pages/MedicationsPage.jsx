import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Pill, RefreshCw, MapPin } from 'lucide-react'
import { medications } from '@/data/medications'

export default function MedicationsPage() {
  return (
    <div className="space-y-6">
      <h2 className="text-xl font-bold text-gray-800">Medications</h2>

      <div className="space-y-3">
        {medications.map((med) => (
          <Card key={med.id} className="hover:shadow-md transition-shadow">
            <CardContent className="p-4">
              <div className="flex justify-between items-start mb-2">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center space-x-2 mb-1">
                    <Pill className="h-4 w-4 text-teal-600 shrink-0" />
                    <h4 className="font-semibold text-gray-800">{med.name}</h4>
                  </div>
                  <p className="text-sm text-gray-600 mb-1">{med.dosage}</p>
                  <p className="text-sm text-gray-500">Prescribed by {med.prescriber}</p>
                </div>
                <Badge className="bg-green-100 text-green-700 text-xs shrink-0">
                  {med.status}
                </Badge>
              </div>

              <div className="bg-gray-50 rounded-lg p-3 text-sm space-y-1 mb-3">
                <div className="flex items-center text-gray-600">
                  <MapPin className="h-3 w-3 mr-2 shrink-0" />
                  {med.pharmacy}
                </div>
                <div className="flex items-center text-gray-600">
                  <RefreshCw className="h-3 w-3 mr-2 shrink-0" />
                  {med.refills}
                </div>
                <p className="text-gray-500 text-xs">Last filled: {med.lastFilled}</p>
              </div>

              <Button size="sm" variant="outline" className="text-xs h-8 w-full">
                <RefreshCw className="h-3 w-3 mr-1" />
                Request Refill
              </Button>
            </CardContent>
          </Card>
        ))}
      </div>

      <Button className="w-full bg-blue-700 hover:bg-blue-800">
        <Pill className="h-4 w-4 mr-2" />
        Manage My Pharmacies
      </Button>
    </div>
  )
}

