import { useNavigate } from 'react-router-dom'
import { Card, CardContent } from '@/components/ui/card'
import {
  Phone, CreditCard, Building2, Shield, ChevronRight
} from 'lucide-react'

const mobileMoney = [
  {
    name: 'Orange Money',
    description: 'Pay with your Orange Money account',
    color: 'bg-orange-500',
    borderColor: 'hover:border-orange-500',
  },
  {
    name: 'MTN Mobile Money',
    description: 'Pay with MTN MoMo',
    color: 'bg-yellow-500',
    borderColor: 'hover:border-yellow-500',
  },
  {
    name: 'Airtel Money',
    description: 'Pay with Airtel Money',
    color: 'bg-red-500',
    borderColor: 'hover:border-red-500',
  },
  {
    name: 'M-Pesa',
    description: 'Pay with Vodacom M-Pesa',
    color: 'bg-green-600',
    borderColor: 'hover:border-green-600',
  },
]

const otherMethods = [
  {
    name: 'Credit/Debit Card',
    description: 'Visa, Mastercard, or American Express',
    icon: CreditCard,
    color: 'bg-blue-500',
    borderColor: 'hover:border-blue-500',
  },
  {
    name: 'Bank Transfer',
    description: 'Direct bank transfer or wire payment',
    icon: Building2,
    color: 'bg-purple-500',
    borderColor: 'hover:border-purple-500',
  },
]

export default function PaymentOptionsPage() {
  const navigate = useNavigate()

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-bold text-gray-800">Payment Options</h2>

      {/* Bill summary */}
      <Card className="border-l-4 border-l-red-500">
        <CardContent className="p-4">
          <h3 className="font-semibold text-gray-800 mb-1">Payment Due</h3>
          <p className="text-sm text-gray-600 mb-1">Physician Services</p>
          <p className="text-2xl font-bold text-gray-800">$54.00</p>
        </CardContent>
      </Card>

      {/* Mobile Money */}
      <section className="space-y-3">
        <h3 className="text-lg font-semibold text-gray-800">Mobile Money</h3>
        {mobileMoney.map((method) => (
          <Card
            key={method.name}
            className={`cursor-pointer hover:shadow-lg transition-all border-2 ${method.borderColor} active:scale-[0.98]`}
          >
            <CardContent className="p-4">
              <div className="flex items-center space-x-4">
                <div className={`${method.color} w-12 h-12 rounded-lg flex items-center justify-center shrink-0`}>
                  <Phone className="h-6 w-6 text-white" />
                </div>
                <div className="flex-1 min-w-0">
                  <h4 className="font-semibold text-gray-800">{method.name}</h4>
                  <p className="text-sm text-gray-600">{method.description}</p>
                </div>
                <ChevronRight className="h-5 w-5 text-gray-400 shrink-0" />
              </div>
            </CardContent>
          </Card>
        ))}
      </section>

      {/* Other */}
      <section className="space-y-3">
        <h3 className="text-lg font-semibold text-gray-800">Other Methods</h3>
        {otherMethods.map((method) => (
          <Card
            key={method.name}
            className={`cursor-pointer hover:shadow-lg transition-all border-2 ${method.borderColor} active:scale-[0.98]`}
          >
            <CardContent className="p-4">
              <div className="flex items-center space-x-4">
                <div className={`${method.color} w-12 h-12 rounded-lg flex items-center justify-center shrink-0`}>
                  <method.icon className="h-6 w-6 text-white" />
                </div>
                <div className="flex-1 min-w-0">
                  <h4 className="font-semibold text-gray-800">{method.name}</h4>
                  <p className="text-sm text-gray-600">{method.description}</p>
                </div>
                <ChevronRight className="h-5 w-5 text-gray-400 shrink-0" />
              </div>
            </CardContent>
          </Card>
        ))}
      </section>

      {/* Security notice */}
      <Card className="bg-blue-50 border-blue-200">
        <CardContent className="p-4">
          <div className="flex items-start space-x-3">
            <Shield className="h-5 w-5 text-blue-600 mt-0.5 shrink-0" />
            <div>
              <h4 className="font-medium text-blue-800 mb-1">Secure Payment</h4>
              <p className="text-sm text-blue-700">
                All payments are encrypted and processed securely. Your financial
                information is protected.
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}

