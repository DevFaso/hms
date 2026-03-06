import { Routes, Route, Navigate } from 'react-router-dom'
import ProtectedRoute from '@/components/ProtectedRoute'
import AppLayout from '@/components/layout/AppLayout'
import LoginPage from '@/pages/LoginPage'
import DashboardPage from '@/pages/DashboardPage'
import AppointmentsPage from '@/pages/AppointmentsPage'
import LabResultsPage from '@/pages/LabResultsPage'
import MedicationsPage from '@/pages/MedicationsPage'
import BillingPage from '@/pages/BillingPage'
import PaymentOptionsPage from '@/pages/PaymentOptionsPage'
import MessagesPage from '@/pages/MessagesPage'
import PatientProfilePage from '@/pages/PatientProfilePage'
import NotificationsPage from '@/pages/NotificationsPage'
import './App.css'

function App() {
  return (
    <Routes>
      {/* Public */}
      <Route path="/login" element={<LoginPage />} />

      {/* Protected — everything inside AppLayout */}
      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/appointments" element={<AppointmentsPage />} />
          <Route path="/lab-results" element={<LabResultsPage />} />
          <Route path="/medications" element={<MedicationsPage />} />
          <Route path="/billing" element={<BillingPage />} />
          <Route path="/billing/pay" element={<PaymentOptionsPage />} />
          <Route path="/messages" element={<MessagesPage />} />
          <Route path="/profile" element={<PatientProfilePage />} />
          <Route path="/notifications" element={<NotificationsPage />} />
        </Route>
      </Route>

      {/* Catch-all redirect */}
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  )
}

export default App
