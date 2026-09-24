import { DOCTOR_EQUIVALENT_ROLES } from '../../core/role-equivalence';

/**
 * ROLE_DOCTOR and the authorities the BACKEND expands into it (role audit
 * decision C2, mirrored by JwtTokenProvider / SecurityConfig's authorities
 * mapper): a physician and a surgeon are doctors to every endpoint below.
 *
 * Spelled out in the lists rather than left to `roleSatisfies`, because these
 * lists are read through `RoleContextService.hasAnyActiveRole`, which compares
 * raw strings and does NOT expand — so a list naming only ROLE_DOCTOR is
 * narrower than the endpoint it claims to mirror, and hides the chart from a
 * surgeon the backend admits. B7's in-basket category routes exactly that
 * surgeon here.
 */
const DOCTOR_ROLES: string[] = ['ROLE_DOCTOR', ...DOCTOR_EQUIVALENT_ROLES];

/*
 * Every list in this file that names a doctor uses DOCTOR_ROLES. Leaving any
 * one of them on the bare name made the surgeon's patient page half-populated
 * — a Chart tab and no Vitals, Encounters, Appointments or Chart Review —
 * even though RoleExpansion grants them ROLE_DOCTOR and all four backends
 * admit it.
 */

/**
 * Role lists mirroring the backend @PreAuthorize gates on the patient-chart
 * endpoints (allergies / diagnoses / chart-updates / doctor-timeline).
 * E9 #67 (D5): HOSPITAL_ADMIN is administrative-only on the chart — it keeps
 * demographics, coverage and registration, none of the lists below.
 * Single source of truth for PatientChartComponent and the Chart tab gate in
 * PatientDetailComponent — update here when the backend gates change.
 */
export const CHART_ROLES = {
  // E9 #69: every clinical role reads allergies (contrast, induction, therapy).
  viewAllergies: [
    ...DOCTOR_ROLES,
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_PHARMACIST',
    'ROLE_RADIOLOGIST',
    'ROLE_ANESTHESIOLOGIST',
    'ROLE_PHYSIOTHERAPIST',
  ],
  editAllergies: [...DOCTOR_ROLES, 'ROLE_NURSE', 'ROLE_PHARMACIST'],
  // E9 #69: the pharmacist reads the problem list to verify a prescription.
  viewProblems: [...DOCTOR_ROLES, 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_PHARMACIST'],
  editProblems: [...DOCTOR_ROLES],
  viewUpdates: [...DOCTOR_ROLES, 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  createUpdates: [...DOCTOR_ROLES, 'ROLE_NURSE', 'ROLE_MIDWIFE'],
  viewTimeline: [...DOCTOR_ROLES],
  /**
   * B6 — the Labs section reads TWO backends, and they do not admit the same
   * roles, so each read carries its own list instead of one combined flag: a
   * combined flag would 403 half the roles on one of the two calls and render
   * that failure as "nothing to show".
   *
   * Both are READ lists lifted from the backend gates, never the lab
   * workbench's write permissions — the trap VITALS_VIEW_ROLES documents
   * below.
   *
   * viewLabResults mirrors PatientLabResultController#listLabResults
   * (`/patients/{id}/lab-results`); SecurityConfig's GET matcher for
   * API_PATIENT_CHART_PATTERNS admits every role the annotation names, so the
   * annotation is the effective gate.
   */
  viewLabResults: [
    ...DOCTOR_ROLES,
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_PHARMACIST',
    'ROLE_LAB_SCIENTIST',
    'ROLE_SUPER_ADMIN',
  ],
  /**
   * viewLabOrders mirrors LabOrderController#getAllLabOrders
   * (`GET /lab-orders?patientId=`), whose annotation adds ROLE_STAFF and the
   * lab bench to the clinical roles. Neither layer admits ROLE_PHARMACIST —
   * not the annotation, and not SecurityConfig's GET matcher for /lab-orders,
   * which is first-match-wins and terminal and would refuse a pharmacist
   * before the annotation ever ran. So this list is NOT viewLabResults: a
   * pharmacist reads a patient's results and never their orders, which is why
   * the two reads are gated apart instead of behind one flag.
   */
  viewLabOrders: [
    ...DOCTOR_ROLES,
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_STAFF',
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_TECHNICIAN',
    'ROLE_LAB_MANAGER',
    'ROLE_LAB_DIRECTOR',
    'ROLE_QUALITY_MANAGER',
    'ROLE_SUPER_ADMIN',
  ],
};

/**
 * Roles that can see at least one chart section (gates the Chart tab).
 *
 * B6 deliberately leaves the two lab lists OUT of this union. The union gates
 * the Chart TAB in PatientDetailComponent, and folding the lab lists in would
 * hand the whole tab to seven roles that reach no other section — the lab
 * bench, quality and ROLE_STAFF. Those roles read a patient's labs from the
 * /lab workbench and from Chart Review (CHART_REVIEW_VIEW_ROLES below), which
 * is where CHART_REVIEW_VIEW_ROLES' own note says audit decisions D5/D6 put
 * them. Every role that already reaches the chart and passes CHART_ROLES
 * .viewLabResults / .viewLabOrders gets the Labs section.
 */
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
  ...DOCTOR_ROLES,
  'ROLE_PHARMACIST',
  'ROLE_RADIOLOGIST',
  'ROLE_ANESTHESIOLOGIST',
  'ROLE_PHYSIOTHERAPIST',
  'ROLE_SUPER_ADMIN',
];

/** Roles EncounterController's list read admits (was 'Create Encounters'). */
/**
 * Roles AppointmentController's per-patient read admits
 * (APPOINTMENT_READ_ROLES). E9 #69: the Appointments tab was unconditional,
 * so every role that reaches the chart but not this endpoint (the
 * pharmacist now, the lab roles all along) clicked into a 403 card.
 */
export const APPOINTMENT_VIEW_ROLES: string[] = [
  'ROLE_SUPER_ADMIN',
  'ROLE_HOSPITAL_ADMIN',
  'ROLE_STAFF',
  'ROLE_RECEPTIONIST',
  ...DOCTOR_ROLES,
  'ROLE_NURSE',
  'ROLE_MIDWIFE',
  'ROLE_RADIOLOGIST',
  'ROLE_ANESTHESIOLOGIST',
  'ROLE_PHYSIOTHERAPIST',
];

export const ENCOUNTER_VIEW_ROLES: string[] = [
  ...DOCTOR_ROLES,
  'ROLE_NURSE',
  'ROLE_MIDWIFE',
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
  ...DOCTOR_ROLES,
  'ROLE_NURSE',
  'ROLE_MIDWIFE',
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
