/**
 * Patient Portal service — wraps /me/patient/* endpoints
 *
 * All endpoints require a valid Bearer JWT token (handled by api.js).
 * Responses follow ApiResponseWrapper<T> — unwrapped automatically.
 */
import api from './api'

const portalService = {
  // ── Profile ──────────────────────────────────────────────────
  /** GET /me/patient/profile → PatientProfileDTO */
  getProfile: () => api.get('/me/patient/profile'),

  /** PUT /me/patient/profile → PatientProfileDTO */
  updateProfile: (dto) => api.put('/me/patient/profile', dto),

  // ── Health Summary ───────────────────────────────────────────
  /** GET /me/patient/health-summary → HealthSummaryDTO (aggregated) */
  getHealthSummary: () => api.get('/me/patient/health-summary'),

  // ── Appointments ─────────────────────────────────────────────
  /** GET /me/patient/appointments → AppointmentResponseDTO[] */
  getAppointments: () => api.get('/me/patient/appointments'),

  /** PUT /me/patient/appointments/cancel → AppointmentResponseDTO */
  cancelAppointment: (dto) => api.put('/me/patient/appointments/cancel', dto),

  /** PUT /me/patient/appointments/reschedule → AppointmentResponseDTO */
  rescheduleAppointment: (dto) => api.put('/me/patient/appointments/reschedule', dto),

  // ── Lab Results ──────────────────────────────────────────────
  /** GET /me/patient/lab-results?limit=20 → PatientLabResultResponseDTO[] */
  getLabResults: (limit = 20) => api.get('/me/patient/lab-results', { limit }),

  // ── Medications ──────────────────────────────────────────────
  /** GET /me/patient/medications?limit=20 → PatientMedicationResponseDTO[] */
  getMedications: (limit = 20) => api.get('/me/patient/medications', { limit }),

  // ── Prescriptions ────────────────────────────────────────────
  /** GET /me/patient/prescriptions → PrescriptionResponseDTO[] */
  getPrescriptions: () => api.get('/me/patient/prescriptions'),

  // ── Refills ──────────────────────────────────────────────────
  /** GET /me/patient/refills → Page<MedicationRefillResponseDTO> */
  getRefills: (page = 0, size = 20) =>
    api.get('/me/patient/refills', { page, size }),

  /** POST /me/patient/refills → MedicationRefillResponseDTO */
  requestRefill: (dto) => api.post('/me/patient/refills', dto),

  /** PUT /me/patient/refills/{id}/cancel */
  cancelRefill: (refillId) =>
    api.put(`/me/patient/refills/${refillId}/cancel`),

  // ── Billing ──────────────────────────────────────────────────
  /** GET /me/patient/billing/invoices → Page<BillingInvoiceResponseDTO> */
  getInvoices: (page = 0, size = 20) =>
    api.get('/me/patient/billing/invoices', { page, size }),

  // ── Visits / Encounters ──────────────────────────────────────
  /** GET /me/patient/encounters → EncounterResponseDTO[] */
  getEncounters: () => api.get('/me/patient/encounters'),

  /** GET /me/patient/after-visit-summaries → DischargeSummaryResponseDTO[] */
  getAfterVisitSummaries: () => api.get('/me/patient/after-visit-summaries'),

  // ── Care Team ────────────────────────────────────────────────
  /** GET /me/patient/care-team → CareTeamDTO */
  getCareTeam: () => api.get('/me/patient/care-team'),

  // ── Vitals ───────────────────────────────────────────────────
  /** GET /me/patient/vitals?limit=10 → PatientVitalSignResponseDTO[] */
  getVitals: (limit = 10) => api.get('/me/patient/vitals', { limit }),

  /** POST /me/patient/vitals → PatientVitalSignResponseDTO */
  recordHomeVital: (dto) => api.post('/me/patient/vitals', dto),

  // ── Immunizations ────────────────────────────────────────────
  getImmunizations: () => api.get('/me/patient/immunizations'),

  // ── Consultations ────────────────────────────────────────────
  getConsultations: () => api.get('/me/patient/consultations'),

  // ── Consents ─────────────────────────────────────────────────
  getConsents: (page = 0, size = 20) =>
    api.get('/me/patient/consents', { page, size }),
  grantConsent: (dto) => api.post('/me/patient/consents', dto),
  revokeConsent: (fromHospitalId, toHospitalId) =>
    api.delete('/me/patient/consents', undefined, {
      params: { fromHospitalId, toHospitalId },
    }),

  // ── Access Log ───────────────────────────────────────────────
  getAccessLog: (page = 0, size = 20) =>
    api.get('/me/patient/access-log', { page, size }),

  // ── Referrals ────────────────────────────────────────────────
  getReferrals: () => api.get('/me/patient/referrals'),

  // ── Treatment Plans ──────────────────────────────────────────
  getTreatmentPlans: (page = 0, size = 20) =>
    api.get('/me/patient/treatment-plans', { page, size }),
}

export default portalService

