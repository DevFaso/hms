import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Shield, DollarSign } from 'lucide-react'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

export default function BillingPage() {
  const navigate = useNavigate()
  const { data: raw } = useApiData(() => portalService.getInvoices(), [])

  const invoices = Array.isArray(raw) ? raw : raw?.content || raw || []
  const bills = invoices.map((b) => ({
    id: b.id,
    description: b.notes || `Invoice #${b.invoiceNumber || ''}`,
    amount: Number((b.balance ?? ((b.totalAmount ?? 0) - (b.amountPaid ?? 0))) || 0),
    date: b.invoiceDate || b.date || '',
    status: b.status === 'PAID' ? 'Paid' : b.status === 'CANCELLED' ? 'Cancelled' : 'Due',
    provider: b.hospitalName || b.facility || '',
    raw: b,
  }))

  const totalDue = bills.filter((b) => b.status === 'Due').reduce((sum, b) => sum + b.amount, 0)
  const firstDue = bills.find((b) => b.status === 'Due')

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-bold text-gray-800">Billing</h2>

      {totalDue > 0 && (
        <Card className="bg-blue-700 text-white">
          <CardContent className="p-5 text-center">
            <p className="mb-1 text-sm text-blue-200">Total Amount Due</p>
            <p className="text-3xl font-bold">${totalDue.toFixed(2)}</p>
            <Button className="mt-3 bg-white text-blue-700 hover:bg-blue-50" onClick={() => navigate('/billing/pay', { state: { invoice: firstDue?.raw } })}>
              Pay Now
            </Button>
          </CardContent>
        </Card>
      )}

      <div className="space-y-3">
        {bills.map((bill) => (
          <Card key={bill.id} className={`border-l-4 transition-shadow hover:shadow-md ${bill.status === 'Due' ? 'border-l-red-500' : 'border-l-green-500'}`}>
            <CardContent className="p-4">
              <div className="flex justify-between items-start">
                <div className="min-w-0 flex-1">
                  <h4 className="mb-1 font-semibold text-gray-800">{bill.description}</h4>
                  <p className="text-sm text-gray-500">{bill.provider}</p>
                  <p className="text-sm text-gray-500">{bill.date}</p>
                  <Badge className="mt-2 text-xs" variant={bill.status === 'Due' ? 'destructive' : 'secondary'}>{bill.status}</Badge>
                </div>
                <div className="ml-3 shrink-0 text-right">
                  <p className="text-lg font-bold text-gray-800">${bill.amount.toFixed(2)}</p>
                  {bill.status === 'Due' && <Button size="sm" className="mt-2 h-8 bg-blue-700 text-xs hover:bg-blue-800" onClick={() => navigate('/billing/pay', { state: { invoice: bill.raw } })}>Pay</Button>}
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      <div className="grid grid-cols-2 gap-3">
        <Button variant="outline" className="h-auto w-full flex-col py-3"><Shield className="mb-1 h-5 w-5 text-blue-600" /><span className="text-xs">Insurance Summary</span></Button>
        <Button variant="outline" className="h-auto w-full flex-col py-3"><DollarSign className="mb-1 h-5 w-5 text-blue-600" /><span className="text-xs">View Estimates</span></Button>
      </div>
    </div>
  )
}
