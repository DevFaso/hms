/**
 * Chat / messaging service — wraps /chat/* endpoints
 *
 * GET  /chat/conversations/{userId}                → conversation list
 * GET  /chat/messages/{userId}                     → all messages
 * GET  /chat/history/{user1Id}/{user2Id}            → paginated thread
 * POST /chat/send                                   → send message
 * PUT  /chat/mark-read/{senderId}/{recipientId}     → mark read
 */
import api from './api'

const chatService = {
  getConversations: (userId) => api.get(`/chat/conversations/${userId}`),

  getMessages: (userId) => api.get(`/chat/messages/${userId}`),

  getHistory: (user1Id, user2Id, page = 0, size = 20) =>
    api.get(`/chat/history/${user1Id}/${user2Id}`, { page, size }),

  send: (dto) => api.post('/chat/send', dto),

  markRead: (senderId, recipientId) =>
    api.put(`/chat/mark-read/${senderId}/${recipientId}`),
}

export default chatService

