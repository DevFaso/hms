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
