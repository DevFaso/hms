/**
 * The backend's SensitivityCategory (E8 #51): a specially-protected category
 * of clinical information. A row that carries one stays behind
 * break-the-glass between hospitals, and a family proxy never receives it
 * (QA D2730).
 */
export type SensitivityCategory =
  'SUBSTANCE_USE' | 'BEHAVIOURAL_HEALTH' | 'HIV' | 'REPRODUCTIVE_HEALTH';

export const SENSITIVITY_CATEGORIES: readonly SensitivityCategory[] = [
  'SUBSTANCE_USE',
  'BEHAVIOURAL_HEALTH',
  'HIV',
  'REPRODUCTIVE_HEALTH',
];

/** The backend's SensitivityTagResponseDTO: the tag on the row, and what governs. */
export interface SensitivityTag {
  id: string;
  explicitCategory: SensitivityCategory | null;
  effectiveCategory: SensitivityCategory | null;
  departmentDefault: SensitivityCategory | null;
  travelsCrossHospital: boolean;
}

/** The label key of a category. */
export function sensitivityLabelKey(category: SensitivityCategory): string {
  return `SENSITIVITY_TAG.CATEGORY_${category}`;
}
