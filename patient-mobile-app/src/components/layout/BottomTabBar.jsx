import { NavLink } from 'react-router-dom'
import { Home, Calendar, TestTube, Pill, MoreHorizontal } from 'lucide-react'

const tabs = [
  { to: '/dashboard', icon: Home, label: 'Home' },
  { to: '/appointments', icon: Calendar, label: 'Visits' },
  { to: '/lab-results', icon: TestTube, label: 'Results' },
  { to: '/medications', icon: Pill, label: 'Meds' },
  { to: '/more', icon: MoreHorizontal, label: 'More' },
]

export default function BottomTabBar() {
  return (
    <nav className="fixed bottom-0 left-0 right-0 bg-white border-t border-gray-200 z-50 safe-area-bottom">
      <div className="flex justify-around items-center max-w-lg mx-auto h-16">
        {tabs.map((tab) => (
          <NavLink
            key={tab.to}
            to={tab.to}
            className={({ isActive }) =>
              `flex flex-col items-center justify-center flex-1 h-full transition-colors ${
                isActive
                  ? 'text-blue-700 font-semibold'
                  : 'text-gray-500 hover:text-gray-700'
              }`
            }
          >
            <tab.icon className="mb-1 h-5 w-5" />
            <span className="text-xs">{tab.label}</span>
          </NavLink>
        ))}
      </div>
    </nav>
  )
}

