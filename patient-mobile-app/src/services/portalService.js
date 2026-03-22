/**
 * Patient Portal service — wraps /me/patient/* endpoints.
 *
 * The mobile app mirrors the richer patient-portal surface area exposed by the
 * backend controller so navigation maps to real mobile workflows instead of
 * placeholder website screens.
 */
import api from './api'

const unwrapPage = (res) => (Array.isArray(res) ? res : res?.content ?? [])

async function downloadBlob(path, filename, params) {
  const res = await api.raw(path, { method: 'GET', params })
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`)
  }

  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
  return true
}

const portalService = {
  getProfile: () => api.get('/me/patient/profile'),
  updateProfile: (dto) => api.put('/me/patient/profile', dto),
  getHealthSummary: () => api.get('/me/patient/health-summary'),

  getAppointments: () => api.get('/me/patient/appointments'),
  bookAppointment: (dto) => api.post('/me/patient/appointments', dto),
  cancelAppointment: (dto) => api.put('/me/patient/appointments/cancel', dto),
  rescheduleAppointment: (dto) => api.put('/me/patient/appointments/reschedule', dto),
  checkInAppointment: (appointmentId) => api.post(`/me/patient/appointments/${appointmentId}/check-in`, {}),
  getDepartments: () => api.get('/me/patient/departments'),
  getDepartmentProviders: (departmentId) => api.get(`/me/patient/departments/${departmentId}/providers`),

  getLabResults: (limit = 20) => api.get('/me/patient/lab-results', { limit }),
  getLabResultTrends: () => api.get('/me/patient/lab-results/trends'),
  getLabOrders: () => api.get('/me/patient/lab-orders'),

  getMedications: (limit = 20) => api.get('/me/patient/medications', { limit }),
  getPrescriptions: () => api.get('/me/patient/prescriptions'),
  getPharmacyFills: () => api.get('/me/patient/medications/fills'),
  getRefills: async (page = 0, size = 20) => unwrapPage(await api.get('/me/patient/refills', { page, size })),
  requestRefill: (dto) => api.post('/me/patient/refills', dto),
  cancelRefill: (refillId) => api.put(`/me/patient/refills/${refillId}/cancel`, {}),

  getVitals: (limit = 10) => api.get('/me/patient/vitals', { limit }),
  recordHomeVital: (dto) => api.post('/me/patient/vitals', dto),
  getVitalTrends: (months = 3) => api.get('/me/patient/vitals/trends', { months }),

  getEncounters: () => api.get('/me/patient/encounters'),
  getEncounterNote: (encounterId) => api.get(`/me/patient/encounters/${encounterId}/note`),
  getEncounterInstructions: (encounterId) => api.get(`/me/patient/encounters/${encounterId}/instructions`),
  getAfterVisitSummaries: () => api.get('/me/patient/after-visit-summaries'),

  getInvoices: async (page = 0, size = 20) => unwrapPage(await api.get('/me/patient/billing/invoices', { page, size })),
  payInvoice: (invoiceId, dto) => api.post(`/me/patient/billing/invoices/${invoiceId}/pay`, dto),

  getConsents: async (page = 0, size = 20) => unwrapPage(await api.get('/me/patient/consents', { page, size })),
  grantConsent: (dto) => api.post('/me/patient/consents', dto),
  revokeConsent: (fromHospitalId, toHospitalId) => api.delete('/me/patient/consents', undefined, {
    params: { fromHospitalId, toHospitalId },
  }),
  getAccessLog: async (page = 0, size = 20) => unwrapPage(await api.get('/me/patient/access-log', { page, size })),

  getCareTeam: () => api.get('/me/patient/care-team'),
  getMessageableCareTeam: () => api.get('/me/patient/care-team/messageable'),
  getConsultations: () => api.get('/me/patient/consultations'),
  getTreatmentPlans: async (page = 0, size = 20) => unwrapPage(await api.get('/me/patient/treatment-plans', { page, size })),
  getTreatmentPlanProgress: (planId) => api.get(`/me/patient/treatment-plans/${planId}/progress`),
  logTreatmentPlanProgress: (planId, dto) => api.post(`/me/patient/treatment-plans/${planId}/progress`, dto),
  getOutcomes: () => api.get('/me/patient/outcomes'),
  reportOutcome: (dto) => api.post('/me/patient/outcomes', dto),
  getReferrals: () => api.get('/me/patient/referrals'),

  getImmunizations: () => api.get('/me/patient/immunizations'),
  getUpcomingVaccinations: (months = 6) => api.get('/me/patient/immunizations/upcoming', { months }),
  downloadImmunizationCertificate: () => downloadBlob('/me/patient/immunizations/certificate', 'immunization-certificate.pdf'),

  getAdmissions: () => api.get('/me/patient/admissions'),
  getCurrentAdmission: () => api.get('/me/patient/admissions/current'),
  getImagingOrders: () => api.get('/me/patient/imaging/orders'),
  getProcedureOrders: () => api.get('/me/patient/procedure-orders'),

  getEducationProgress: () => api.get('/me/patient/education/progress'),
  getInProgressEducation: () => api.get('/me/patient/education/in-progress'),
  getCompletedEducation: () => api.get('/me/patient/education/completed'),
  getEducationResources: () => api.get('/me/patient/education/resources'),
  searchEducationResources: (query) => api.get('/me/patient/education/resources/search', { query }),
  getEducationResourcesByCategory: (category) => api.get(`/me/patient/education/resources/by-category/${category}`),
  downloadRecord: (format = 'pdf') => downloadBlob('/me/patient/records/download', format === 'csv' ? 'my-health-record.csv' : 'my-health-record.pdf', { format }),

  getPendingQuestionnaires: () => api.get('/me/patient/questionnaires'),
  getSubmittedQuestionnaires: () => api.get('/me/patient/questionnaires/submitted'),
  submitQuestionnaire: (dto) => api.post('/me/patient/questionnaires/response', dto),

  getHealthReminders: () => api.get('/me/patient/health-reminders'),
  completeHealthReminder: (reminderId) => api.put(`/me/patient/health-reminders/${reminderId}/complete`, {}),

  getProxies: () => api.get('/me/patient/proxies'),
  grantProxy: (dto) => api.post('/me/patient/proxies', dto),
  revokeProxy: (proxyId) => api.delete(`/me/patient/proxies/${proxyId}`),
  getProxyAccess: () => api.get('/me/patient/proxy-access'),

  getNotificationPreferences: () => api.get('/me/patient/notifications/preferences'),
  setNotificationPreference: (dto) => api.put('/me/patient/notifications/preferences', dto),
  resetNotificationPreferences: () => api.delete('/me/patient/notifications/preferences'),
}

export default portalService
