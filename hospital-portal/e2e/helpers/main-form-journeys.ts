/** Each clinical entry journey must exercise its empty-form refusal as a working role. */
export const MAIN_FORM_JOURNEYS = [
  { route: '/prescriptions', role: 'ROLE_DOCTOR', endpoint: '/api/prescriptions' },
  { route: '/lab', role: 'ROLE_DOCTOR', endpoint: '/api/lab-orders' },
  { route: '/lab-results', role: 'ROLE_LAB_TECHNICIAN', endpoint: '/api/lab-results' },
  { route: '/imaging', role: 'ROLE_DOCTOR', endpoint: '/api/imaging/orders' },
  { route: '/encounters', role: 'ROLE_DOCTOR', endpoint: '/api/encounters' },
  { route: '/consultations', role: 'ROLE_DOCTOR', endpoint: '/api/consultations' },
  { route: '/treatment-plans', role: 'ROLE_DOCTOR', endpoint: '/api/treatment-plans' },
  { route: '/referrals', role: 'ROLE_DOCTOR', endpoint: '/api/referrals' },
] as const;
