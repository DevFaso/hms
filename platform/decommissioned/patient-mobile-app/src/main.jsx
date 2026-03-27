import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { AuthProvider } from '@/contexts/AuthContext'
import ErrorBoundary from '@/components/ErrorBoundary'
import pushNotifications from '@/services/pushNotifications'
import './index.css'
import App from './App.jsx'

// Register for push notifications (no-op on web)
pushNotifications.register().catch((err) =>
  console.warn('Push registration skipped:', err)
)

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <ErrorBoundary>
      <BrowserRouter>
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </ErrorBoundary>
  </StrictMode>,
)
