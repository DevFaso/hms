import { Routes, Route, Navigate } from 'react-router-dom'
import ProtectedRoute from '@/components/ProtectedRoute'
import AppLayout from '@/components/layout/AppLayout'
import LoginPage from '@/pages/LoginPage'
import DashboardPage from '@/pages/DashboardPage'
import AppointmentsPage from '@/pages/AppointmentsPage'
import ScheduleAppointmentPage from '@/pages/ScheduleAppointmentPage'
import LabResultsPage from '@/pages/LabResultsPage'
import MedicationsPage from '@/pages/MedicationsPage'
import BillingPage from '@/pages/BillingPage'
import PaymentOptionsPage from '@/pages/PaymentOptionsPage'
import MessagesPage from '@/pages/MessagesPage'
import MessageThreadPage from '@/pages/MessageThreadPage'
import ComposeMessagePage from '@/pages/ComposeMessagePage'
import PatientProfilePage from '@/pages/PatientProfilePage'
import NotificationsPage from '@/pages/NotificationsPage'
import CareTeamPage from '@/pages/CareTeamPage'
import VisitHistoryPage from '@/pages/VisitHistoryPage'
import AfterVisitSummaryPage from '@/pages/AfterVisitSummaryPage'
import DocumentsPage from '@/pages/DocumentsPage'
import ConsentFormPage from '@/pages/ConsentFormPage'
import VitalsPage from '@/pages/VitalsPage'
import HealthRecordsPage from '@/pages/HealthRecordsPage'
import SharingPrivacyPage from '@/pages/SharingPrivacyPage'
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

          {/* Appointments & Scheduling */}
          <Route path="/appointments" element={<AppointmentsPage />} />
          <Route path="/appointments/schedule" element={<ScheduleAppointmentPage />} />

          {/* Messaging — /messages/new must come before /messages/:recipientId */}
          <Route path="/messages" element={<MessagesPage />} />
          <Route path="/messages/new" element={<ComposeMessagePage />} />
          <Route path="/messages/:recipientId" element={<MessageThreadPage />} />

          {/* Clinical */}
          <Route path="/lab-results" element={<LabResultsPage />} />
          <Route path="/medications" element={<MedicationsPage />} />
          <Route path="/care-team" element={<CareTeamPage />} />
          <Route path="/vitals" element={<VitalsPage />} />
          <Route path="/health-records" element={<HealthRecordsPage />} />

          {/* Visits */}
          <Route path="/visits" element={<VisitHistoryPage />} />
          <Route path="/visits/:encounterId/summary" element={<AfterVisitSummaryPage />} />

          {/* Billing */}
          <Route path="/billing" element={<BillingPage />} />
          <Route path="/billing/pay" element={<PaymentOptionsPage />} />

          {/* Documents & Forms */}
          <Route path="/documents" element={<DocumentsPage />} />
          <Route path="/documents/consents" element={<ConsentFormPage />} />
          <Route path="/sharing" element={<SharingPrivacyPage />} />

          {/* Profile & Notifications */}
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
