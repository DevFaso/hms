import { useState } from 'react'
import { Button } from '@/components/ui/button.jsx'
import { Card, CardContent } from '@/components/ui/card.jsx'
import { Badge } from '@/components/ui/badge.jsx'
import NavigationMenu from './NavigationMenu.jsx'
import { 
  Calendar, 
  Mail, 
  CalendarDays, 
  TestTube, 
  Pill, 
  CreditCard,
  Menu,
  Edit,
  Eye,
  DollarSign,
  User
} from 'lucide-react'

const Dashboard = ({ onLogout }) => {
  const [showMenu, setShowMenu] = useState(false)

  const quickAccessItems = [
    { 
      icon: Calendar, 
      title: 'Schedule an Appointment', 
      color: 'bg-green-500',
      action: () => console.log('Schedule appointment')
    },
    { 
      icon: Mail, 
      title: 'Messages', 
      color: 'bg-green-500',
      action: () => console.log('Messages')
    },
    { 
      icon: CalendarDays, 
      title: 'Visits', 
      color: 'bg-green-500',
      action: () => console.log('Visits')
    },
    { 
      icon: TestTube, 
      title: 'Test Results', 
      color: 'bg-green-500',
      action: () => console.log('Test Results')
    },
    { 
      icon: Pill, 
      title: 'Medications', 
      color: 'bg-green-500',
      action: () => console.log('Medications')
    },
    { 
      icon: CreditCard, 
      title: 'Billing', 
      color: 'bg-green-500',
      action: () => console.log('Billing')
    }
  ]

  return (
    <>
      <NavigationMenu 
        isOpen={showMenu} 
        onClose={() => setShowMenu(false)} 
        onLogout={onLogout}
      />
      <div className="min-h-screen bg-gradient-to-b from-blue-100 to-blue-200">
      {/* Header */}
      <div className="bg-blue-700 text-white p-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <Button 
              variant="ghost" 
              size="sm" 
              className="text-white hover:bg-blue-600 p-2"
              onClick={() => setShowMenu(!showMenu)}
            >
              <Menu className="h-6 w-6" />
            </Button>
            <div className="flex items-center space-x-2">
              <span className="text-sm font-medium">NYC HEALTH + HOSPITALS</span>
              <span className="text-xs">MYCHART</span>
            </div>
          </div>
          <div className="flex items-center space-x-2">
            <div className="flex items-center space-x-1">
              <span className="text-white font-bold">MyChart</span>
              <span className="text-red-400 font-bold italic">Epic</span>
            </div>
            <div className="w-10 h-10 bg-green-500 rounded-full flex items-center justify-center">
              <User className="h-6 w-6 text-white" />
            </div>
          </div>
        </div>
      </div>

      {/* Welcome Section */}
      <div className="p-6">
        <div className="flex items-center justify-between mb-6">
          <h1 className="text-2xl font-bold text-gray-800">Welcome, Tiego!</h1>
          <Button variant="ghost" size="sm" className="text-green-600 hover:text-green-700">
            <Edit className="h-4 w-4" />
          </Button>
        </div>

        {/* Quick Access Grid */}
        <div className="grid grid-cols-3 gap-4 mb-6">
          {quickAccessItems.map((item, index) => (
            <Card 
              key={index} 
              className="cursor-pointer hover:shadow-lg transition-shadow duration-200"
              onClick={item.action}
            >
              <CardContent className="p-4 text-center">
                <div className={`${item.color} w-12 h-12 rounded-lg flex items-center justify-center mx-auto mb-3`}>
                  <item.icon className="h-6 w-6 text-white" />
                </div>
                <h3 className="text-sm font-medium text-gray-800 leading-tight">
                  {item.title}
                </h3>
              </CardContent>
            </Card>
          ))}
        </div>

        {/* Notifications */}
        <div className="space-y-4">
          {/* New Test Result */}
          <Card className="border-l-4 border-l-blue-500">
            <CardContent className="p-4">
              <div className="flex items-start space-x-3">
                <div className="bg-blue-500 p-2 rounded-lg">
                  <Mail className="h-5 w-5 text-white" />
                </div>
                <div className="flex-1">
                  <h3 className="font-semibold text-gray-800 mb-1">New Test Result</h3>
                  <div className="flex items-center space-x-2 mb-2">
                    <div className="w-8 h-8 bg-gray-300 rounded-full flex items-center justify-center">
                      <span className="text-sm font-medium text-gray-600">N</span>
                    </div>
                    <div>
                      <p className="text-sm font-medium text-gray-700">NYC Health and Hospitals</p>
                      <p className="text-xs text-gray-500">Sep 12</p>
                    </div>
                  </div>
                  <p className="text-sm text-gray-600 mb-3">You have a new test result.</p>
                  <Button size="sm" className="bg-blue-700 hover:bg-blue-800">
                    View message
                  </Button>
                </div>
              </div>
            </CardContent>
          </Card>

          {/* Amount Due */}
          <Card className="border-l-4 border-l-green-500">
            <CardContent className="p-4">
              <div className="flex items-start space-x-3">
                <div className="bg-green-500 p-2 rounded-lg">
                  <CreditCard className="h-5 w-5 text-white" />
                </div>
                <div className="flex-1">
                  <h3 className="font-semibold text-gray-800 mb-1">Amount Due</h3>
                  <div className="flex items-center space-x-2 mb-2">
                    <div className="bg-blue-700 p-2 rounded-lg">
                      <DollarSign className="h-4 w-4 text-white" />
                    </div>
                    <div>
                      <p className="text-sm font-medium text-gray-700">NYC Health + Hospitals</p>
                      <p className="text-xs text-gray-500">Physician Services</p>
                      <p className="text-xs text-gray-500">Guarantor #100714973</p>
                    </div>
                  </div>
                  <p className="text-lg font-bold text-gray-800 mb-3">You owe $54.00</p>
                  <div className="flex space-x-2">
                    <Button size="sm" className="bg-blue-700 hover:bg-blue-800">
                      Pay now
                    </Button>
                    <Button size="sm" variant="outline">
                      View details
                    </Button>
                  </div>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>

        {/* Logout Button for testing */}
        <div className="mt-8 pt-6 border-t border-gray-200">
          <Button 
            onClick={onLogout} 
            variant="outline" 
            className="w-full"
          >
            Logout
          </Button>
        </div>
      </div>
    </div>
    </>
  )
}

export default Dashboard
