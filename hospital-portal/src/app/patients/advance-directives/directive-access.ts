/**
 * Role list mirroring the backend gate on /advance-directives
 * (AdvanceDirectiveController.CLINICAL_ROLES). One list, not a read/write
 * split, because the backend applies the same roles to every mapping.
 * Single source of truth for AdvanceDirectivesTabComponent and the
 * Directives tab gate in PatientDetailComponent — update here when the
 * backend gate changes.
 */
export const DIRECTIVE_ROLES: string[] = [
  'ROLE_DOCTOR',
  'ROLE_NURSE',
  'ROLE_MIDWIFE',
  'ROLE_HOSPITAL_ADMIN',
  'ROLE_SUPER_ADMIN',
];
