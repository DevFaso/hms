/**
 * Notification service — wraps /notifications/* endpoints
 *
 * GET  /notifications          → Notification[]
 * POST /notifications/{id}/read → mark as read
 */
import api from './api'

const notificationService = {
  /** GET /notifications → Notification[] (unwraps Spring Page) */
  getAll: async () => {
    const res = await api.get('/notifications')
    // Backend returns a Spring Page object { content: [], pageable: ... }
    // Unwrap to a plain array so callers can use .filter(), .map(), etc.
    return Array.isArray(res) ? res : (res?.content ?? [])
  },

  /** POST /notifications/{id}/read */
  markRead: (id) => api.post(`/notifications/${id}/read`),
}

export default notificationService

