import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { CreditCard, Shield, DollarSign } from 'lucide-react'
import { bills } from '@/data/bills'

export default function BillingPage() {
  const navigate = useNavigate()
  const totalDue = bills
    .filter((b) => b.status === 'Due')
    .reduce((sum, b) => sum + b.amount, 0)

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-bold text-gray-800">Billing</h2>

      {/* Summary */}
      {totalDue > 0 && (
        <Card className="bg-blue-700 text-white">
          <CardContent className="p-5 text-center">
            <p className="text-blue-200 text-sm mb-1">Total Amount Due</p>
            <p className="text-3xl font-bold">${totalDue.toFixed(2)}</p>
            <Button
              className="mt-3 bg-white text-blue-700 hover:bg-blue-50"
              onClick={() => navigate('/billing/pay')}
            >
              Pay Now
            </Button>
          </CardContent>
        </Card>
      )}

      {/* Bills */}
      <div className="space-y-3">
        {bills.map((bill) => (
          <Card
            key={bill.id}
            className={`hover:shadow-md transition-shadow border-l-4 ${
              bill.status === 'Due' ? 'border-l-red-500' : 'border-l-green-500'
            }`}
          >
            <CardContent className="p-4">
              <div className="flex justify-between items-start">
                <div className="flex-1 min-w-0">
                  <h4 className="font-semibold text-gray-800 mb-1">{bill.description}</h4>
                  <p className="text-sm text-gray-500">{bill.provider}</p>
                  <p className="text-sm text-gray-500">{bill.date}</p>
                  <Badge
                    className="mt-2 text-xs"
                    variant={bill.status === 'Due' ? 'destructive' : 'secondary'}
                  >
                    {bill.status}
                  </Badge>
                </div>
                <div className="text-right shrink-0 ml-3">
                  <p className="text-lg font-bold text-gray-800">${bill.amount.toFixed(2)}</p>
                  {bill.status === 'Due' && (
                    <Button
                      size="sm"
                      className="bg-blue-700 hover:bg-blue-800 mt-2 text-xs h-8"
                      onClick={() => navigate('/billing/pay')}
                    >
                      Pay
                    </Button>
                  )}
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      {/* Quick links */}
      <div className="grid grid-cols-2 gap-3">
        <Button variant="outline" className="w-full h-auto py-3 flex flex-col items-center">
          <Shield className="h-5 w-5 mb-1 text-blue-600" />
          <span className="text-xs">Insurance Summary</span>
        </Button>
        <Button variant="outline" className="w-full h-auto py-3 flex flex-col items-center">
          <DollarSign className="h-5 w-5 mb-1 text-blue-600" />
          <span className="text-xs">View Estimates</span>
        </Button>
      </div>
    </div>
  )
}

