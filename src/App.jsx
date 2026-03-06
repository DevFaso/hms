import { useState } from 'react'
import { Button } from '@/components/ui/button.jsx'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card.jsx'
import { Input } from '@/components/ui/input.jsx'
import { Label } from '@/components/ui/label.jsx'
import { Badge } from '@/components/ui/badge.jsx'
import { Separator } from '@/components/ui/separator.jsx'
import { 
  Fingerprint, 
  HelpCircle, 
  UserPlus, 
  AlertCircle, 
  Menu, 
  X, 
  Calendar, 
  Mail, 
  Building2, 
  TestTube, 
  Pill, 
  CreditCard,
  ChevronRight,
  ArrowLeft,
  Phone,
  MapPin,
  Clock,
  DollarSign,
  FileText,
  Settings,
  LogOut,
  User,
  Shield,
  BookOpen,
  Search,
  Eye,
  Download
} from 'lucide-react'
import './App.css'

function App() {
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  const [showUsernameLogin, setShowUsernameLogin] = useState(false)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [currentView, setCurrentView] = useState('dashboard')
  const [showMenu, setShowMenu] = useState(false)

  const handleFaceIdLogin = () => {
    setTimeout(() => {
      setIsLoggedIn(true)
    }, 1000)
  }

  const handleUsernameLogin = (e) => {
    e.preventDefault()
    if (username && password) {
      setIsLoggedIn(true)
    }
  }

  const navigateTo = (view) => {
    setCurrentView(view)
    setShowMenu(false)
  }

  const menuItems = [
    { id: 'find-care', title: 'Find Care', icon: Search, items: [
      { id: 'schedule', title: 'Schedule an Appointment', icon: Calendar },
      { id: 'care-team', title: 'View Care Team', icon: User },
      { id: 'urgent-care', title: 'Virtual Urgent Care', icon: Phone }
    ]},
    { id: 'communication', title: 'Communication', icon: Mail, items: [
      { id: 'messages', title: 'Messages', icon: Mail },
      { id: 'ask-question', title: 'Ask a Question', icon: HelpCircle },
      { id: 'letters', title: 'Letters', icon: FileText }
    ]},
    { id: 'my-record', title: 'My Record', icon: FileText, items: [
      { id: 'visits', title: 'Visits', icon: Building2 },
      { id: 'test-results', title: 'Test Results', icon: TestTube },
      { id: 'medications', title: 'Medications', icon: Pill },
      { id: 'health-summary', title: 'Health Summary', icon: FileText },
      { id: 'preventive-care', title: 'Preventive Care', icon: Shield }
    ]},
    { id: 'billing', title: 'Billing', icon: CreditCard, items: [
      { id: 'billing-overview', title: 'Billing', icon: CreditCard },
      { id: 'insurance', title: 'Insurance Summary', icon: Shield },
      { id: 'estimates', title: 'Estimates', icon: DollarSign }
    ]}
  ]

  // Sample data
  const appointments = [
    {
      id: 1,
      type: 'Lab Work',
      location: 'Kings County Outpatient Lab',
      date: 'DEC 5, 2025',
      time: '9:00 AM',
      status: 'upcoming'
    },
    {
      id: 2,
      type: 'Revisit',
      doctor: 'Dr. Joshua Shapiro',
      location: 'Kings County Endocrinology E9',
      date: 'DEC 12, 2025',
      time: '11:20 AM',
      status: 'upcoming'
    },
    {
      id: 3,
      type: 'Hospital Outpatient Visit',
      location: 'Kings County Ultrasound',
      date: 'SEP 12, 2025',
      time: 'Completed',
      status: 'past'
    }
  ]

  const messages = [
    {
      id: 1,
      from: 'NYC Health and Hospitals',
      subject: 'New Test Result Available',
      date: 'Sep 12',
      unread: true,
      preview: 'You have a new test result.'
    },
    {
      id: 2,
      from: 'Dr. Joshua Shapiro',
      subject: 'Follow-up Instructions',
      date: 'Sep 10',
      unread: false,
      preview: 'Please continue taking your medication as prescribed.'
    }
  ]

  const testResults = [
    {
      id: 1,
      test: 'Complete Blood Count',
      date: 'Sep 12, 2025',
      status: 'New',
      provider: 'NYC Health + Hospitals'
    },
    {
      id: 2,
      test: 'Hemoglobin A1C',
      date: 'Sep 8, 2025',
      status: 'Reviewed',
      provider: 'Kings County Lab'
    }
  ]

  const medications = [
    {
      id: 1,
      name: 'Metformin 500mg',
      dosage: 'Take twice daily with meals',
      prescriber: 'Dr. Joshua Shapiro',
      refills: '2 refills remaining'
    },
    {
      id: 2,
      name: 'Lisinopril 10mg',
      dosage: 'Take once daily',
      prescriber: 'Dr. Sarah Johnson',
      refills: '5 refills remaining'
    }
  ]

  const bills = [
    {
      id: 1,
      description: 'Physician Services',
      amount: 54.00,
      date: 'Sep 15, 2025',
      status: 'Due'
    },
    {
      id: 2,
      description: 'Lab Work',
      amount: 125.00,
      date: 'Sep 10, 2025',
      status: 'Paid'
    }
  ]

  if (!isLoggedIn) {
    return (
      <div className="min-h-screen bg-gradient-to-b from-blue-600 to-blue-800 relative overflow-hidden">
        {/* City skyline background */}
        <div className="absolute bottom-0 left-0 right-0 h-64 opacity-20">
          <svg viewBox="0 0 800 200" className="w-full h-full">
            <rect x="50" y="120" width="40" height="80" fill="white" />
            <rect x="100" y="80" width="60" height="120" fill="white" />
            <rect x="170" y="100" width="45" height="100" fill="white" />
            <rect x="220" y="60" width="50" height="140" fill="white" />
            <rect x="280" y="90" width="35" height="110" fill="white" />
            <rect x="320" y="70" width="55" height="130" fill="white" />
            <rect x="380" y="110" width="40" height="90" fill="white" />
            <rect x="430" y="50" width="65" height="150" fill="white" />
            <rect x="500" y="85" width="45" height="115" fill="white" />
            <rect x="550" y="95" width="50" height="105" fill="white" />
            <rect x="610" y="75" width="40" height="125" fill="white" />
            <rect x="660" y="105" width="35" height="95" fill="white" />
            <rect x="700" y="65" width="50" height="135" fill="white" />
          </svg>
        </div>

        <div className="relative z-10 flex flex-col items-center justify-center min-h-screen p-4">
          {/* Header */}
          <div className="flex items-center justify-between w-full max-w-md mb-8">
            <div className="flex items-center space-x-2">
              <span className="text-white font-bold text-lg">MyChart</span>
              <span className="text-red-500 font-bold text-lg italic">Epic</span>
            </div>
            <Button variant="ghost" className="text-blue-300 hover:text-white">
              Edit organizations
            </Button>
          </div>

          {/* Main login card */}
          <Card className="w-full max-w-md bg-white/95 backdrop-blur-sm">
            <CardContent className="p-8">
              {/* Hospital branding */}
              <div className="text-center mb-8">
                <h1 className="text-4xl font-bold text-orange-500 mb-2">NYC</h1>
                <h2 className="text-2xl font-bold text-blue-700 leading-tight">
                  HEALTH<span className="text-blue-500">+</span><br />
                  HOSPITALS
                </h2>
              </div>

              {!showUsernameLogin ? (
                <div className="space-y-6">
                  {/* Face ID Login */}
                  <Button 
                    onClick={handleFaceIdLogin}
                    className="w-full bg-blue-700 hover:bg-blue-800 text-white py-4 text-lg"
                  >
                    <Fingerprint className="mr-3 h-6 w-6" />
                    Log in with Face ID
                  </Button>

                  {/* Alternative login */}
                  <div className="text-center">
                    <Button 
                      variant="link" 
                      className="text-blue-600 hover:text-blue-800"
                      onClick={() => setShowUsernameLogin(true)}
                    >
                      Or log in with username and password
                    </Button>
                  </div>

                  {/* Help and Sign up */}
                  <div className="flex justify-between items-center pt-4">
                    <Button variant="ghost" className="flex flex-col items-center space-y-1 text-blue-600">
                      <HelpCircle className="h-6 w-6" />
                      <span className="text-sm">Need help?</span>
                    </Button>
                    <Button variant="ghost" className="flex flex-col items-center space-y-1 text-blue-600">
                      <UserPlus className="h-6 w-6" />
                      <span className="text-sm">Sign up</span>
                    </Button>
                  </div>
                </div>
              ) : (
                <form onSubmit={handleUsernameLogin} className="space-y-4">
                  <div className="space-y-2">
                    <Label htmlFor="username">Username</Label>
                    <Input
                      id="username"
                      type="text"
                      value={username}
                      onChange={(e) => setUsername(e.target.value)}
                      placeholder="Enter your username"
                      required
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="password">Password</Label>
                    <Input
                      id="password"
                      type="password"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      placeholder="Enter your password"
                      required
                    />
                  </div>
                  <Button type="submit" className="w-full bg-blue-700 hover:bg-blue-800">
                    Log In
                  </Button>
                  <Button 
                    type="button" 
                    variant="outline" 
                    className="w-full"
                    onClick={() => setShowUsernameLogin(false)}
                  >
                    Back to Face ID
                  </Button>
                </form>
              )}

              {/* Get Help section */}
              <div className="mt-8 pt-6 border-t border-gray-200">
                <Button variant="ghost" className="w-full flex items-center justify-between text-gray-700 hover:text-gray-900">
                  <div className="flex items-center">
                    <div className="bg-blue-700 rounded-full p-2 mr-3">
                      <AlertCircle className="h-4 w-4 text-white" />
                    </div>
                    <span>Get Help</span>
                  </div>
                  <ChevronRight className="h-4 w-4 text-gray-400" />
                </Button>
              </div>
            </CardContent>
          </Card>

          {/* Footer */}
          <div className="mt-8 text-center text-white/80 text-sm">
            MyChart®, Epic Systems Corporation, © 1999 - 2025
          </div>
        </div>
      </div>
    )
  }

  const renderDashboard = () => (
    <div className="space-y-6">
      {/* Welcome Section */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-800">Welcome, Tiego!</h1>
        <Button variant="ghost" size="sm" className="text-blue-600">
          ✏️
        </Button>
      </div>

      {/* Quick Access Grid */}
      <div className="grid grid-cols-3 gap-4">
        {[
          { icon: Calendar, title: 'Schedule an Appointment', view: 'schedule', color: 'bg-green-500' },
          { icon: Mail, title: 'Messages', view: 'messages', color: 'bg-green-500' },
          { icon: Building2, title: 'Visits', view: 'visits', color: 'bg-green-500' },
          { icon: TestTube, title: 'Test Results', view: 'test-results', color: 'bg-green-500' },
          { icon: Pill, title: 'Medications', view: 'medications', color: 'bg-green-500' },
          { icon: CreditCard, title: 'Billing', view: 'billing-overview', color: 'bg-green-500' }
        ].map((item, index) => (
          <Card key={index} className="cursor-pointer hover:shadow-lg transition-shadow duration-200" onClick={() => navigateTo(item.view)}>
            <CardContent className="p-4 text-center">
              <div className={`${item.color} w-12 h-12 rounded-lg flex items-center justify-center mx-auto mb-3`}>
                <item.icon className="h-6 w-6 text-white" />
              </div>
              <h3 className="text-sm font-medium text-gray-800">{item.title}</h3>
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
              <div className="bg-blue-500 rounded-full p-2">
                <TestTube className="h-4 w-4 text-white" />
              </div>
              <div className="flex-1">
                <h3 className="font-semibold text-gray-800 mb-1">New Test Result</h3>
                <p className="text-sm text-gray-600 mb-1">NYC Health and Hospitals</p>
                <p className="text-sm text-gray-600 mb-3">Sep 12</p>
                <p className="text-sm text-gray-700 mb-3">You have a new test result.</p>
                <Button size="sm" className="bg-blue-700 hover:bg-blue-800" onClick={() => navigateTo('test-results')}>
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
              <div className="bg-green-500 rounded-full p-2">
                <CreditCard className="h-4 w-4 text-white" />
              </div>
              <div className="flex-1">
                <h3 className="font-semibold text-gray-800 mb-1">Amount Due</h3>
                <div className="flex items-center space-x-2 mb-2">
                  <div className="bg-blue-700 rounded p-1">
                    <Building2 className="h-4 w-4 text-white" />
                  </div>
                  <div>
                    <p className="text-sm font-medium text-gray-800">NYC Health + Hospitals</p>
                    <p className="text-xs text-gray-600">Physician Services</p>
                    <p className="text-xs text-gray-600">Guarantor #100714973</p>
                  </div>
                </div>
                <p className="text-lg font-bold text-gray-800 mb-3">You owe $54.00</p>
                <div className="flex space-x-2">
                  <Button size="sm" className="bg-blue-700 hover:bg-blue-800" onClick={() => navigateTo('billing-overview')}>
                    Pay now
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => navigateTo('billing-overview')}>
                    View details
                  </Button>
                </div>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  )

  const renderAppointments = () => (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-gray-800">Appointments</h2>
        <Button className="bg-blue-700 hover:bg-blue-800">
          <Calendar className="h-4 w-4 mr-2" />
          Schedule an appointment
        </Button>
      </div>

      <div className="space-y-4">
        <div>
          <h3 className="text-lg font-semibold text-gray-800 mb-3 flex items-center">
            Future
            <AlertCircle className="h-5 w-5 ml-2 text-orange-500" />
          </h3>
          {appointments.filter(apt => apt.status === 'upcoming').map(appointment => (
            <Card key={appointment.id} className="mb-3">
              <CardContent className="p-4">
                <div className="flex justify-between items-start">
                  <div className="flex-1">
                    <h4 className="font-semibold text-gray-800">{appointment.type}</h4>
                    {appointment.doctor && (
                      <p className="text-sm text-gray-600">{appointment.doctor}</p>
                    )}
                    <p className="text-sm text-gray-600">{appointment.location}</p>
                    <p className="text-sm text-gray-600">Starts at {appointment.time}</p>
                  </div>
                  <div className="text-right">
                    <div className="text-blue-600 font-medium text-sm">
                      {appointment.date.split(',')[0]}
                    </div>
                    <div className="text-blue-600 font-bold text-lg">
                      {appointment.date.split(' ')[1]}
                    </div>
                    <div className="text-blue-600 font-medium text-sm">
                      {appointment.date.split(' ')[2]}
                    </div>
                  </div>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>

        <div>
          <h3 className="text-lg font-semibold text-gray-800 mb-3 flex items-center">
            Past
            <AlertCircle className="h-5 w-5 ml-2 text-orange-500" />
          </h3>
          {appointments.filter(apt => apt.status === 'past').map(appointment => (
            <Card key={appointment.id} className="mb-3">
              <CardContent className="p-4">
                <div className="flex justify-between items-start">
                  <div className="flex-1">
                    <h4 className="font-semibold text-gray-800">{appointment.type}</h4>
                    <p className="text-sm text-gray-600">{appointment.location}</p>
                    <div className="mt-2 space-y-1">
                      <Button variant="link" className="text-blue-600 p-0 h-auto text-sm">
                        <FileText className="h-4 w-4 mr-1" />
                        View After Visit Summary®
                      </Button>
                    </div>
                  </div>
                  <div className="text-right">
                    <div className="text-blue-600 font-medium text-sm">
                      {appointment.date.split(' ')[0]}
                    </div>
                    <div className="text-blue-600 font-bold text-lg">
                      {appointment.date.split(' ')[1]}
                    </div>
                    <div className="text-blue-600 font-medium text-sm">
                      {appointment.date.split(' ')[2]}
                    </div>
                  </div>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      </div>
    </div>
  )

  const renderMessages = () => (
    <div className="space-y-6">
      <h2 className="text-xl font-bold text-gray-800">Messages</h2>
      
      <div className="space-y-3">
        {messages.map(message => (
          <Card key={message.id} className={`cursor-pointer hover:shadow-md transition-shadow ${message.unread ? 'border-l-4 border-l-blue-500' : ''}`}>
            <CardContent className="p-4">
              <div className="flex justify-between items-start">
                <div className="flex-1">
                  <div className="flex items-center space-x-2 mb-1">
                    <h4 className={`font-medium ${message.unread ? 'font-semibold' : ''} text-gray-800`}>
                      {message.from}
                    </h4>
                    {message.unread && (
                      <Badge variant="secondary" className="bg-blue-100 text-blue-800">New</Badge>
                    )}
                  </div>
                  <p className="text-sm text-gray-600 mb-1">{message.subject}</p>
                  <p className="text-sm text-gray-500">{message.preview}</p>
                </div>
                <div className="text-right">
                  <p className="text-sm text-gray-500">{message.date}</p>
                  <ChevronRight className="h-4 w-4 text-gray-400 mt-1" />
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      <Button className="w-full bg-blue-700 hover:bg-blue-800">
        <Mail className="h-4 w-4 mr-2" />
        Compose New Message
      </Button>
    </div>
  )

  const renderTestResults = () => (
    <div className="space-y-6">
      <h2 className="text-xl font-bold text-gray-800">Test Results</h2>
      
      <div className="space-y-3">
        {testResults.map(result => (
          <Card key={result.id} className="cursor-pointer hover:shadow-md transition-shadow">
            <CardContent className="p-4">
              <div className="flex justify-between items-start">
                <div className="flex-1">
                  <div className="flex items-center space-x-2 mb-1">
                    <h4 className="font-medium text-gray-800">{result.test}</h4>
                    <Badge variant={result.status === 'New' ? 'default' : 'secondary'} 
                           className={result.status === 'New' ? 'bg-blue-600' : 'bg-gray-100 text-gray-600'}>
                      {result.status}
                    </Badge>
                  </div>
                  <p className="text-sm text-gray-600">{result.provider}</p>
                  <p className="text-sm text-gray-500">{result.date}</p>
                </div>
                <div className="flex space-x-2">
                  <Button size="sm" variant="outline">
                    <Eye className="h-4 w-4 mr-1" />
                    View
                  </Button>
                  <Button size="sm" variant="outline">
                    <Download className="h-4 w-4 mr-1" />
                    Download
                  </Button>
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  )

  const renderMedications = () => (
    <div className="space-y-6">
      <h2 className="text-xl font-bold text-gray-800">Medications</h2>
      
      <div className="space-y-3">
        {medications.map(medication => (
          <Card key={medication.id} className="cursor-pointer hover:shadow-md transition-shadow">
            <CardContent className="p-4">
              <div className="flex justify-between items-start">
                <div className="flex-1">
                  <h4 className="font-medium text-gray-800 mb-1">{medication.name}</h4>
                  <p className="text-sm text-gray-600 mb-1">{medication.dosage}</p>
                  <p className="text-sm text-gray-500">Prescribed by {medication.prescriber}</p>
                  <p className="text-sm text-green-600 font-medium">{medication.refills}</p>
                </div>
                <div className="flex space-x-2">
                  <Button size="sm" variant="outline">
                    Request Refill
                  </Button>
                </div>
              </div>
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

  const renderBilling = () => (
    <div className="space-y-6">
      <h2 className="text-xl font-bold text-gray-800">Billing</h2>
      
      <div className="space-y-3">
        {bills.map(bill => (
          <Card key={bill.id} className={`cursor-pointer hover:shadow-md transition-shadow ${bill.status === 'Due' ? 'border-l-4 border-l-red-500' : 'border-l-4 border-l-green-500'}`}>
            <CardContent className="p-4">
              <div className="flex justify-between items-start">
                <div className="flex-1">
                  <h4 className="font-medium text-gray-800 mb-1">{bill.description}</h4>
                  <p className="text-sm text-gray-600">{bill.date}</p>
                  <div className="flex items-center space-x-2 mt-2">
                    <Badge variant={bill.status === 'Due' ? 'destructive' : 'secondary'}>
                      {bill.status}
                    </Badge>
                  </div>
                </div>
                <div className="text-right">
                  <p className="text-lg font-bold text-gray-800">${bill.amount.toFixed(2)}</p>
                  {bill.status === 'Due' && (
                    <div className="mt-2 space-y-2">
                      <Button size="sm" className="bg-blue-700 hover:bg-blue-800 w-full" onClick={() => setCurrentView('payment-options')}>
                        Pay Now
                      </Button>
                    </div>
                  )}
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      <div className="grid grid-cols-2 gap-4">
        <Button variant="outline" className="w-full">
          <Shield className="h-4 w-4 mr-2" />
          Insurance Summary
        </Button>
        <Button variant="outline" className="w-full">
          <DollarSign className="h-4 w-4 mr-2" />
          View Estimates
        </Button>
      </div>
    </div>
  )

  const renderPaymentOptions = () => (
    <div className="space-y-6">
      <h2 className="text-xl font-bold text-gray-800">Payment Options</h2>
      
      {/* Bill Summary */}
      <Card className="border-l-4 border-l-red-500">
        <CardContent className="p-4">
          <h3 className="font-semibold text-gray-800 mb-2">Payment Due</h3>
          <p className="text-sm text-gray-600 mb-1">Physician Services</p>
          <p className="text-2xl font-bold text-gray-800">$54.00</p>
        </CardContent>
      </Card>

      {/* Mobile Money Options */}
      <div className="space-y-4">
        <h3 className="text-lg font-semibold text-gray-800">Mobile Money</h3>
        
        {/* Orange Money */}
        <Card className="cursor-pointer hover:shadow-lg transition-shadow border-2 hover:border-orange-500">
          <CardContent className="p-4">
            <div className="flex items-center space-x-4">
              <div className="bg-orange-500 w-12 h-12 rounded-lg flex items-center justify-center">
                <Phone className="h-6 w-6 text-white" />
              </div>
              <div className="flex-1">
                <h4 className="font-semibold text-gray-800">Orange Money</h4>
                <p className="text-sm text-gray-600">Pay with your Orange Money account</p>
              </div>
              <ChevronRight className="h-5 w-5 text-gray-400" />
            </div>
          </CardContent>
        </Card>

        {/* MTN Mobile Money */}
        <Card className="cursor-pointer hover:shadow-lg transition-shadow border-2 hover:border-yellow-500">
          <CardContent className="p-4">
            <div className="flex items-center space-x-4">
              <div className="bg-yellow-500 w-12 h-12 rounded-lg flex items-center justify-center">
                <Phone className="h-6 w-6 text-white" />
              </div>
              <div className="flex-1">
                <h4 className="font-semibold text-gray-800">MTN Mobile Money</h4>
                <p className="text-sm text-gray-600">Pay with MTN MoMo</p>
              </div>
              <ChevronRight className="h-5 w-5 text-gray-400" />
            </div>
          </CardContent>
        </Card>

        {/* Airtel Money */}
        <Card className="cursor-pointer hover:shadow-lg transition-shadow border-2 hover:border-red-500">
          <CardContent className="p-4">
            <div className="flex items-center space-x-4">
              <div className="bg-red-500 w-12 h-12 rounded-lg flex items-center justify-center">
                <Phone className="h-6 w-6 text-white" />
              </div>
              <div className="flex-1">
                <h4 className="font-semibold text-gray-800">Airtel Money</h4>
                <p className="text-sm text-gray-600">Pay with Airtel Money</p>
              </div>
              <ChevronRight className="h-5 w-5 text-gray-400" />
            </div>
          </CardContent>
        </Card>

        {/* Vodacom M-Pesa */}
        <Card className="cursor-pointer hover:shadow-lg transition-shadow border-2 hover:border-green-600">
          <CardContent className="p-4">
            <div className="flex items-center space-x-4">
              <div className="bg-green-600 w-12 h-12 rounded-lg flex items-center justify-center">
                <Phone className="h-6 w-6 text-white" />
              </div>
              <div className="flex-1">
                <h4 className="font-semibold text-gray-800">M-Pesa</h4>
                <p className="text-sm text-gray-600">Pay with Vodacom M-Pesa</p>
              </div>
              <ChevronRight className="h-5 w-5 text-gray-400" />
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Traditional Payment Methods */}
      <div className="space-y-4">
        <h3 className="text-lg font-semibold text-gray-800">Other Payment Methods</h3>
        
        {/* Credit/Debit Card */}
        <Card className="cursor-pointer hover:shadow-lg transition-shadow border-2 hover:border-blue-500">
          <CardContent className="p-4">
            <div className="flex items-center space-x-4">
              <div className="bg-blue-500 w-12 h-12 rounded-lg flex items-center justify-center">
                <CreditCard className="h-6 w-6 text-white" />
              </div>
              <div className="flex-1">
                <h4 className="font-semibold text-gray-800">Credit/Debit Card</h4>
                <p className="text-sm text-gray-600">Pay with Visa, Mastercard, or American Express</p>
              </div>
              <ChevronRight className="h-5 w-5 text-gray-400" />
            </div>
          </CardContent>
        </Card>

        {/* Bank Transfer */}
        <Card className="cursor-pointer hover:shadow-lg transition-shadow border-2 hover:border-purple-500">
          <CardContent className="p-4">
            <div className="flex items-center space-x-4">
              <div className="bg-purple-500 w-12 h-12 rounded-lg flex items-center justify-center">
                <Building2 className="h-6 w-6 text-white" />
              </div>
              <div className="flex-1">
                <h4 className="font-semibold text-gray-800">Bank Transfer</h4>
                <p className="text-sm text-gray-600">Direct bank transfer or wire payment</p>
              </div>
              <ChevronRight className="h-5 w-5 text-gray-400" />
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Payment Security Notice */}
      <Card className="bg-blue-50 border-blue-200">
        <CardContent className="p-4">
          <div className="flex items-start space-x-3">
            <Shield className="h-5 w-5 text-blue-600 mt-0.5" />
            <div>
              <h4 className="font-medium text-blue-800 mb-1">Secure Payment</h4>
              <p className="text-sm text-blue-700">All payments are encrypted and processed securely. Your financial information is protected.</p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  )

  const renderCurrentView = () => {
    switch (currentView) {
      case 'schedule':
      case 'visits':
        return renderAppointments()
      case 'messages':
        return renderMessages()
      case 'test-results':
        return renderTestResults()
      case 'medications':
        return renderMedications()
      case 'billing-overview':
        return renderBilling()
      case 'payment-options':
        return renderPaymentOptions()
      default:
        return renderDashboard()
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-blue-100 to-blue-200">
      {/* Header */}
      <div className="bg-blue-700 text-white p-4 sticky top-0 z-50">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-3">
            {currentView !== 'dashboard' ? (
              <Button 
                variant="ghost" 
                size="sm" 
                className="text-white hover:bg-blue-600 p-1"
                onClick={() => setCurrentView('dashboard')}
              >
                <ArrowLeft className="h-5 w-5" />
              </Button>
            ) : (
              <Button 
                variant="ghost" 
                size="sm" 
                className="text-white hover:bg-blue-600 p-1"
                onClick={() => setShowMenu(true)}
              >
                <Menu className="h-5 w-5" />
              </Button>
            )}
            <div className="flex items-center space-x-2">
              <span className="text-sm font-medium">NYC HEALTH + HOSPITALS</span>
              <span className="text-xs">MYCHART</span>
            </div>
          </div>
          <div className="flex items-center space-x-2">
            <span className="text-white font-bold">MyChart</span>
            <span className="text-red-400 font-bold italic">Epic</span>
            <div className="w-8 h-8 bg-green-500 rounded-full flex items-center justify-center">
              <User className="h-4 w-4 text-white" />
            </div>
          </div>
        </div>
      </div>

      {/* Navigation Menu Overlay */}
      {showMenu && (
        <div className="fixed inset-0 bg-black bg-opacity-50 z-50" onClick={() => setShowMenu(false)}>
          <div className="bg-white w-80 h-full overflow-y-auto" onClick={(e) => e.stopPropagation()}>
            <div className="p-4 border-b">
              <div className="flex items-center justify-between">
                <h2 className="text-lg font-semibold text-green-600">Menu</h2>
                <Button variant="ghost" size="sm" onClick={() => setShowMenu(false)}>
                  <X className="h-5 w-5" />
                </Button>
              </div>
              <div className="mt-4">
                <Input placeholder="Search the menu" className="w-full" />
              </div>
            </div>
            
            <div className="p-4 space-y-6">
              {menuItems.map(section => (
                <div key={section.id}>
                  <h3 className="font-semibold text-gray-800 bg-green-100 px-3 py-2 rounded mb-2">
                    {section.title}
                  </h3>
                  <div className="space-y-1">
                    {section.items.map(item => (
                      <Button
                        key={item.id}
                        variant="ghost"
                        className="w-full justify-between text-left"
                        onClick={() => navigateTo(item.id)}
                      >
                        <div className="flex items-center space-x-3">
                          <item.icon className="h-5 w-5 text-green-600" />
                          <span>{item.title}</span>
                        </div>
                        <ChevronRight className="h-4 w-4 text-gray-400" />
                      </Button>
                    ))}
                  </div>
                </div>
              ))}
              
              <Separator />
              
              <div className="space-y-1">
                <Button
                  variant="ghost"
                  className="w-full justify-between text-left"
                  onClick={() => navigateTo('settings')}
                >
                  <div className="flex items-center space-x-3">
                    <Settings className="h-5 w-5 text-green-600" />
                    <span>Settings</span>
                  </div>
                  <ChevronRight className="h-4 w-4 text-gray-400" />
                </Button>
                <Button
                  variant="ghost"
                  className="w-full justify-between text-left"
                  onClick={() => setIsLoggedIn(false)}
                >
                  <div className="flex items-center space-x-3">
                    <LogOut className="h-5 w-5 text-green-600" />
                    <span>Log Out</span>
                  </div>
                  <ChevronRight className="h-4 w-4 text-gray-400" />
                </Button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Main Content */}
      <div className="p-6">
        {renderCurrentView()}
      </div>
    </div>
  )
}

export default App
