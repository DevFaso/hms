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

/**
 * Roles the VITALS read endpoints admit (PatientVitalSignController's GET
 * annotations). Deliberately a role list, not the 'Update Vital Signs'
 * permission the tab used to check: that is a WRITE permission, so gating the
 * read tab on it hid vitals from every read-only role — the same trap
 * canViewGrowth() documents. Consulting clinicians were added by audit
 * decision D7 (an anaesthetist cannot do a pre-operative assessment without
 * vitals); writing vitals stays with the bedside roles.
 */
export const VITALS_VIEW_ROLES: string[] = [
  'ROLE_NURSE',
  'ROLE_MIDWIFE',
  'ROLE_DOCTOR',
  'ROLE_HOSPITAL_ADMIN',
  'ROLE_RADIOLOGIST',
  'ROLE_ANESTHESIOLOGIST',
  'ROLE_PHYSIOTHERAPIST',
  'ROLE_SUPER_ADMIN',
];

/** Roles EncounterController's list read admits (was 'Create Encounters'). */
export const ENCOUNTER_VIEW_ROLES: string[] = [
  'ROLE_DOCTOR',
  'ROLE_NURSE',
  'ROLE_MIDWIFE',
  'ROLE_HOSPITAL_ADMIN',
  'ROLE_RADIOLOGIST',
  'ROLE_ANESTHESIOLOGIST',
  'ROLE_PHYSIOTHERAPIST',
  'ROLE_SUPER_ADMIN',
];

/**
 * Roles ChartReviewController admits — broader than the encounters list,
 * because the longitudinal record (encounters, notes, results, medications,
 * imaging, procedures) is a read surface the lab and pharmacy roles use too.
 *
 * This is also the answer to audit items D5/D6: consulting clinicians read
 * labs and imaging HERE, from the patient's record, rather than in /lab and
 * /imaging, which are order-entry workbenches owned by the teams that work
 * those queues.
 */
export const CHART_REVIEW_VIEW_ROLES: string[] = [
  'ROLE_DOCTOR',
  'ROLE_NURSE',
  'ROLE_MIDWIFE',
  'ROLE_HOSPITAL_ADMIN',
  'ROLE_RECEPTIONIST',
  'ROLE_PHARMACIST',
  'ROLE_LAB_SCIENTIST',
  'ROLE_LAB_TECHNICIAN',
  'ROLE_LAB_MANAGER',
  'ROLE_LAB_DIRECTOR',
  'ROLE_QUALITY_MANAGER',
  'ROLE_RADIOLOGIST',
  'ROLE_ANESTHESIOLOGIST',
  'ROLE_PHYSIOTHERAPIST',
  'ROLE_SUPER_ADMIN',
];
