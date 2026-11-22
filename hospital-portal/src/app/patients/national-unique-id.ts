/** IU stays a string so a leading zero is never lost. Empty is an explicit clear. */
export function normalizedNationalUniqueId(input: string): string | null {
  const value = input.replace(/[\s\p{Pd}]/gu, '');
  return value === '' || /^[0-9]{12}$/.test(value) ? value : null;
}

export interface NationalUniqueIdChange {
  nationalUniqueId?: string;
  allowNationalUniqueIdDuplicate?: boolean;
  nationalUniqueIdDuplicateReason?: string;
}

export interface NationalUniqueIdWarning {
  accessiblePatientIds: string[];
  registeredElsewhere: boolean;
}

/**
 * QA D2521 (r65): who may find a patient by IU (POST /patients/national-unique-id/search, the
 * server's PatientNationalUniqueIdController.SEARCHERS). A page offers the IU search to these only:
 * a refused POST would take the user to the 403 page.
 */
export const NATIONAL_IU_SEARCH_ROLES: readonly string[] = [
  'ROLE_RECEPTIONIST',
  'ROLE_HOSPITAL_ADMIN',
  'ROLE_DOCTOR',
  'ROLE_NURSE',
  'ROLE_MIDWIFE',
  'ROLE_LAB_SCIENTIST',
  'ROLE_PHARMACIST',
  'ROLE_RADIOLOGIST',
  'ROLE_PHYSIOTHERAPIST',
  'ROLE_SUPER_ADMIN',
  'ROLE_BILLING_SPECIALIST',
  'ROLE_ACCOUNTANT',
];
