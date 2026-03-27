import { useState } from 'react'
import { Button } from '@/components/ui/button.jsx'
import { Input } from '@/components/ui/input.jsx'
import { 
  Calendar, 
  Mail, 
  CalendarDays, 
  TestTube, 
  Pill, 
  CreditCard,
  Users,
  Video,
  MessageSquare,
  FileText,
  Glasses,
  Shield,
  Clock,
  History,
  Heart,
  ClipboardList,
  Stethoscope,
  FolderOpen,
  FileEdit,
  ShieldCheck,
  Pharmacy,
  DollarSign,
  HelpCircle,
  Receipt,
  Share2,
  Globe,
  Link,
  Code,
  Search,
  BookOpen,
  GraduationCap,
  Lightbulb,
  LifeBuoy,
  User,
  Settings,
  Palette,
  Star,
  Smartphone,
  Bell,
  MoreHorizontal,
  ArrowLeftRight,
  LogOut,
  X,
  ChevronRight
} from 'lucide-react'

const NavigationMenu = ({ isOpen, onClose, onLogout }) => {
  const [searchTerm, setSearchTerm] = useState('')

  const menuSections = [
    {
      title: "Find Care",
      color: "bg-green-100",
      items: [
        { icon: Calendar, title: "Schedule an Appointment", action: () => console.log('Schedule') },
        { icon: Users, title: "View Care Team", action: () => console.log('Care Team') },
        { icon: Video, title: "Virtual Urgent Care", action: () => console.log('Virtual Care') }
      ]
    },
    {
      title: "Communication",
      color: "bg-green-100",
      items: [
        { icon: Mail, title: "Messages", action: () => console.log('Messages') },
        { icon: MessageSquare, title: "Ask a Question", action: () => console.log('Ask Question') },
        { icon: FileText, title: "Letters", action: () => console.log('Letters') }
      ]
    },
    {
      title: "Eyecare Center",
      color: "bg-green-100",
      items: [
        { icon: Glasses, title: "Eyeglass Prescription", action: () => console.log('Eyeglass') }
      ]
    },
    {
      title: "My Record",
      color: "bg-green-100",
      items: [
        { icon: Shield, title: "COVID-19", action: () => console.log('COVID-19') },
        { icon: Clock, title: "To Do", action: () => console.log('To Do') },
        { icon: CalendarDays, title: "Visits", action: () => console.log('Visits') },
        { icon: TestTube, title: "Test Results", action: () => console.log('Test Results') },
        { icon: Pill, title: "Medications", action: () => console.log('Medications') },
        { icon: Heart, title: "Health Summary", action: () => console.log('Health Summary') },
        { icon: ShieldCheck, title: "Preventive Care", action: () => console.log('Preventive Care') },
        { icon: ClipboardList, title: "Questionnaires", action: () => console.log('Questionnaires') },
        { icon: Stethoscope, title: "Upcoming Tests and Procedures", action: () => console.log('Upcoming Tests') },
        { icon: History, title: "Medical and Family History", action: () => console.log('Medical History') },
        { icon: FolderOpen, title: "Document Center", action: () => console.log('Documents') },
        { icon: FileEdit, title: "End-of-Life Planning", action: () => console.log('End of Life') },
        { icon: ShieldCheck, title: "Safety Plan", action: () => console.log('Safety Plan') },
        { icon: Pharmacy, title: "Manage My Pharmacies", action: () => console.log('Pharmacies') }
      ]
    },
    {
      title: "Billing",
      color: "bg-green-100",
      items: [
        { icon: CreditCard, title: "Billing", action: () => console.log('Billing') },
        { icon: DollarSign, title: "Financial Assistance", action: () => console.log('Financial Assistance') },
        { icon: Receipt, title: "Estimates", action: () => console.log('Estimates') }
      ]
    },
    {
      title: "Insurance",
      color: "bg-green-100",
      items: [
        { icon: ShieldCheck, title: "Insurance Summary", action: () => console.log('Insurance Summary') },
        { icon: Search, title: "Coverage Details", action: () => console.log('Coverage Details') },
        { icon: CreditCard, title: "Insurance Premium Billing", action: () => console.log('Premium Billing') },
        { icon: FileText, title: "Referrals", action: () => console.log('Referrals') }
      ]
    },
    {
      title: "Sharing",
      color: "bg-green-100",
      items: [
        { icon: Share2, title: "Sharing Hub", action: () => console.log('Sharing Hub') },
        { icon: Globe, title: "Share Everywhere", action: () => console.log('Share Everywhere') },
        { icon: Link, title: "Link My Accounts", action: () => console.log('Link Accounts') },
        { icon: Code, title: "Computer-Readable Export", action: () => console.log('Export') }
      ]
    },
    {
      title: "Resources",
      color: "bg-green-100",
      items: [
        { icon: Search, title: "Search Medical Library", action: () => console.log('Medical Library') },
        { icon: BookOpen, title: "Research Studies", action: () => console.log('Research Studies') },
        { icon: GraduationCap, title: "Education", action: () => console.log('Education') },
        { icon: Lightbulb, title: "Learning Library", action: () => console.log('Learning Library') },
        { icon: LifeBuoy, title: "FindHelp (Community Resources)", action: () => console.log('FindHelp') }
      ]
    },
    {
      title: "Settings",
      color: "bg-green-100",
      items: [
        { icon: User, title: "Personal Information", action: () => console.log('Personal Info') },
        { icon: Settings, title: "Account Settings", action: () => console.log('Account Settings') },
        { icon: Palette, title: "Personalize", action: () => console.log('Personalize') },
        { icon: Star, title: "Change Your Shortcuts", action: () => console.log('Shortcuts') },
        { icon: Smartphone, title: "Linked Apps and Devices", action: () => console.log('Linked Apps') },
        { icon: Bell, title: "Communication Preferences", action: () => console.log('Communication Prefs') },
        { icon: MoreHorizontal, title: "Other Preferences", action: () => console.log('Other Prefs') },
        { icon: ArrowLeftRight, title: "Switch Organizations", action: () => console.log('Switch Orgs') },
        { icon: LogOut, title: "Log Out", action: onLogout }
      ]
    }
  ]

  const filteredSections = menuSections.map(section => ({
    ...section,
    items: section.items.filter(item => 
      item.title.toLowerCase().includes(searchTerm.toLowerCase())
    )
  })).filter(section => section.items.length > 0)

  if (!isOpen) return null

  return (
    <div className="fixed inset-0 z-50 bg-black bg-opacity-50">
      <div className="bg-white h-full w-full max-w-md shadow-xl overflow-y-auto">
        {/* Header */}
        <div className="bg-white p-4 border-b border-gray-200 sticky top-0 z-10">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-xl font-semibold text-green-700">Menu</h2>
            <Button 
              variant="ghost" 
              size="sm" 
              onClick={onClose}
              className="text-blue-600 hover:text-blue-800"
            >
              Cancel
            </Button>
          </div>
          
          {/* Search */}
          <div className="relative">
            <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-gray-400" />
            <Input
              type="text"
              placeholder="Search the menu"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="pl-10 bg-gray-50 border-gray-200"
            />
          </div>
        </div>

        {/* Menu Content */}
        <div className="p-4 space-y-6">
          {filteredSections.map((section, sectionIndex) => (
            <div key={sectionIndex}>
              <div className={`${section.color} px-3 py-2 rounded-lg mb-2`}>
                <h3 className="font-semibold text-gray-800">{section.title}</h3>
              </div>
              <div className="space-y-1">
                {section.items.map((item, itemIndex) => (
                  <Button
                    key={itemIndex}
                    variant="ghost"
                    className="w-full justify-between h-auto p-3 text-left hover:bg-gray-50"
                    onClick={() => {
                      item.action()
                      if (item.title !== "Log Out") {
                        onClose()
                      }
                    }}
                  >
                    <div className="flex items-center space-x-3">
                      <item.icon className="h-5 w-5 text-green-600" />
                      <span className="text-gray-800">{item.title}</span>
                    </div>
                    <ChevronRight className="h-4 w-4 text-gray-400" />
                  </Button>
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

export default NavigationMenu
