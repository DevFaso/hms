export const chatThreads = {
  'staff-002': [
    { id: 'm1', senderId: 'staff-002', senderName: 'Dr. Joshua Shapiro', content: 'Hello Tiego, your A1C results look great — down to 6.8%. Keep up the good work with your diet and exercise.', timestamp: '2026-03-03T14:30:00Z', read: true },
    { id: 'm2', senderId: 'me', senderName: 'Tiego Ouedraogo', content: 'Thank you Dr. Shapiro! I\'ve been walking 40 minutes every day. Should I continue the same Metformin dose?', timestamp: '2026-03-03T15:10:00Z', read: true },
    { id: 'm3', senderId: 'staff-002', senderName: 'Dr. Joshua Shapiro', content: 'Yes, continue Metformin 500mg twice daily. We\'ll recheck in 3 months. Also, please schedule a follow-up appointment.', timestamp: '2026-03-03T15:45:00Z', read: true },
  ],
  'staff-001': [
    { id: 'm4', senderId: 'staff-001', senderName: 'Dr. Sarah Johnson', content: 'Hi Tiego, just a reminder to schedule your annual diabetic eye exam. I\'ve sent a referral to ophthalmology.', timestamp: '2026-03-01T10:00:00Z', read: true },
    { id: 'm5', senderId: 'me', senderName: 'Tiego Ouedraogo', content: 'Thank you! I\'ll call to schedule it this week.', timestamp: '2026-03-01T11:20:00Z', read: true },
  ],
  'staff-005': [
    { id: 'm6', senderId: 'staff-005', senderName: 'Lisa Chen, RN', content: 'Good morning! This is a reminder that you have lab work scheduled for March 15. Please fast for 12 hours before your appointment.', timestamp: '2026-03-05T09:00:00Z', read: false },
  ],
}

export const conversations = [
  {
    recipientId: 'staff-002',
    recipientName: 'Dr. Joshua Shapiro',
    recipientRole: 'Endocrinology',
    lastMessage: 'Yes, continue Metformin 500mg twice daily...',
    lastMessageDate: '2026-03-03T15:45:00Z',
    unreadCount: 0,
  },
  {
    recipientId: 'staff-001',
    recipientName: 'Dr. Sarah Johnson',
    recipientRole: 'Primary Care',
    lastMessage: 'Thank you! I\'ll call to schedule it this week.',
    lastMessageDate: '2026-03-01T11:20:00Z',
    unreadCount: 0,
  },
  {
    recipientId: 'staff-005',
    recipientName: 'Lisa Chen, RN',
    recipientRole: 'Care Coordinator',
    lastMessage: 'Good morning! This is a reminder that you have lab work...',
    lastMessageDate: '2026-03-05T09:00:00Z',
    unreadCount: 1,
  },
]

export const staff = [
  { id: 'staff-001', name: 'Dr. Sarah Johnson', specialty: 'Internal Medicine', department: 'Primary Care' },
  { id: 'staff-002', name: 'Dr. Joshua Shapiro', specialty: 'Endocrinology', department: 'Endocrinology' },
  { id: 'staff-003', name: 'Maria Santos, NP', specialty: 'Family Medicine', department: 'Primary Care' },
  { id: 'staff-004', name: 'Dr. Amir Patel', specialty: 'Cardiology', department: 'Cardiology' },
  { id: 'staff-005', name: 'Lisa Chen, RN', specialty: 'Care Management', department: 'Care Coordination' },
]

