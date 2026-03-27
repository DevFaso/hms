/**
 * Scheduling service — wraps appointment-creation and availability endpoints
 *
 * POST /appointments                    → create appointment
 * POST /appointments/search             → search appointments
 * GET  /availability/check              → check staff availability
 * GET  /staff                           → list staff (for provider picker)
 */
import api from './api'

const schedulingService = {
  /** POST /appointments → AppointmentResponseDTO */
  createAppointment: (dto) => api.post('/appointments', dto),

  /** POST /appointments/search → Page<AppointmentResponseDTO> */
  searchAppointments: (dto) => api.post('/appointments/search', dto),

  /** GET /availability/check?staffId=&dateTime= */
  checkAvailability: (staffId, dateTime) =>
    api.get('/availability/check', { staffId, dateTime }),

  /** GET /staff → StaffDTO[] (for provider picker) */
  getStaff: () => api.get('/staff'),

  /** GET /departments → DepartmentDTO[] */
  getDepartments: () => api.get('/departments'),
}

export default schedulingService

