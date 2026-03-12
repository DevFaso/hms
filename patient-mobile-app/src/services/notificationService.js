/**
 * Notification service — wraps /notifications/* endpoints
 *
 * GET  /notifications          → Notification[]
 * POST /notifications/{id}/read → mark as read
 */
import api from './api'

const notificationService = {
  /** GET /notifications */
  getAll: () => api.get('/notifications'),

  /** POST /notifications/{id}/read */
  markRead: (id) => api.post(`/notifications/${id}/read`),
}

export default notificationService

