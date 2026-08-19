/**
 * Role lists mirroring the backend @PreAuthorize gates on the patient-chart
 * endpoints (allergies / diagnoses / chart-updates / doctor-timeline).
 * Single source of truth for PatientChartComponent and the Chart tab gate in
 * PatientDetailComponent — update here when the backend gates change.
 */
export const CHART_ROLES = {
  viewAllergies: [
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_PHARMACIST',
  ],
  editAllergies: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_PHARMACIST'],
  viewProblems: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_HOSPITAL_ADMIN'],
  editProblems: ['ROLE_DOCTOR'],
  viewUpdates: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_HOSPITAL_ADMIN'],
  createUpdates: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  viewTimeline: ['ROLE_DOCTOR'],
} as const;

/** Roles that can see at least one chart section (gates the Chart tab). */
export const CHART_VIEW_ROLES: string[] = [
  ...new Set([
    ...CHART_ROLES.viewAllergies,
    ...CHART_ROLES.viewProblems,
    ...CHART_ROLES.viewUpdates,
  ]),
];
