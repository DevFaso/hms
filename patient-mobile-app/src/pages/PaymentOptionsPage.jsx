import { useState } from 'react'
import { useLocation } from 'react-router-dom'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Phone, CreditCard, Building2, Shield, CheckCircle2 } from 'lucide-react'
import portalService from '@/services/portalService'

const methods = [
  { name: 'Orange Money', description: 'Pay with your Orange Money account', color: 'bg-orange-500', icon: Phone },
  { name: 'MTN Mobile Money', description: 'Pay with MTN MoMo', color: 'bg-yellow-500', icon: Phone },
  { name: 'Credit / Debit Card', description: 'Visa, Mastercard, or American Express', color: 'bg-blue-500', icon: CreditCard },
  { name: 'Bank Transfer', description: 'Direct bank transfer or wire payment', color: 'bg-purple-500', icon: Building2 },
]

export default function PaymentOptionsPage() {
  const { state } = useLocation()
  const invoice = state?.invoice
  const [selected, setSelected] = useState(methods[0].name)
  const [paying, setPaying] = useState(false)
  const [paid, setPaid] = useState(false)

  const amount = Number((invoice?.balance ?? ((invoice?.totalAmount ?? 0) - (invoice?.amountPaid ?? 0))) || 54)

  const handlePay = async () => {
    if (!invoice?.id) {
      setPaid(true)
      return
    }
    setPaying(true)
    try {
      await portalService.payInvoice(invoice.id, { amount })
      setPaid(true)
    } finally {
      setPaying(false)
    }
  }

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-bold text-gray-800">Payment Options</h2>
      <Card className="border-l-4 border-l-red-500">
        <CardContent className="p-4">
          <h3 className="mb-1 font-semibold text-gray-800">Payment Due</h3>
          <p className="mb-1 text-sm text-gray-600">{invoice?.notes || invoice?.description || 'Patient billing invoice'}</p>
          <p className="text-2xl font-bold text-gray-800">${amount.toFixed(2)}</p>
        </CardContent>
      </Card>

      <section className="space-y-3">
        <h3 className="text-lg font-semibold text-gray-800">Choose a payment method</h3>
        {methods.map((method) => (
          <Card key={method.name} className={`cursor-pointer border-2 transition-all ${selected === method.name ? 'border-blue-600 shadow-md' : 'hover:border-slate-300'}`} onClick={() => setSelected(method.name)}>
            <CardContent className="p-4">
              <div className="flex items-center space-x-4">
                <div className={`${method.color} flex h-12 w-12 shrink-0 items-center justify-center rounded-lg`}><method.icon className="h-6 w-6 text-white" /></div>
                <div className="flex-1 min-w-0"><h4 className="font-semibold text-gray-800">{method.name}</h4><p className="text-sm text-gray-600">{method.description}</p></div>
                {selected === method.name && <CheckCircle2 className="h-5 w-5 text-blue-600" />}
              </div>
            </CardContent>
          </Card>
        ))}
      </section>

      <Button className="w-full bg-blue-700 hover:bg-blue-800" disabled={paying || paid} onClick={handlePay}>
        {paid ? 'Payment recorded' : paying ? 'Processing payment…' : `Pay $${amount.toFixed(2)} with ${selected}`}
      </Button>

      <Card className="border-blue-200 bg-blue-50">
        <CardContent className="p-4">
          <div className="flex items-start space-x-3"><Shield className="mt-0.5 h-5 w-5 shrink-0 text-blue-600" /><div><h4 className="mb-1 font-medium text-blue-800">Secure Payment</h4><p className="text-sm text-blue-700">Payments use the patient billing endpoint and are transmitted securely over the authenticated mobile session.</p></div></div>
        </CardContent>
      </Card>
    </div>
  )
}
