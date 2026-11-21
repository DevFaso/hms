/**
 * The backend's SensitivityCategory (E8 #51): a specially-protected category
 * of clinical information. A row that carries one stays behind
 * break-the-glass between hospitals, and a family proxy never receives it
 * (QA D2730), with the exceptions of the 2026-11-20 policy (provisional): a
 * tuberculosis row for a grant that names that scope, a child's record for a
 * verified parent, and a minor's result a clinician released.
 */
export type SensitivityCategory =
  | 'SUBSTANCE_USE'
  | 'BEHAVIOURAL_HEALTH'
  | 'HIV'
  | 'REPRODUCTIVE_HEALTH'
  | 'STI'
  | 'VIRAL_HEPATITIS'
  | 'PREGNANCY'
  | 'SEXUAL_DOMESTIC_VIOLENCE'
  | 'GENETIC'
  | 'SICKLE_CELL'
  | 'TUBERCULOSIS';

/** In the backend enum's order. */
export const SENSITIVITY_CATEGORIES: readonly SensitivityCategory[] = [
  'SUBSTANCE_USE',
  'BEHAVIOURAL_HEALTH',
  'HIV',
  'REPRODUCTIVE_HEALTH',
  'STI',
  'VIRAL_HEPATITIS',
  'PREGNANCY',
  'SEXUAL_DOMESTIC_VIOLENCE',
  'GENETIC',
  'SICKLE_CELL',
  'TUBERCULOSIS',
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
