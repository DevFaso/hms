/**
 * r54 (provisional, pending the clinical lead's approval): the signs of acute organ damage the server
 * knows (OrganDamageSymptom), ticked with a blood pressure. One ticked sign turns an adult's
 * severe-range warning URGENT on the server; the portal holds no threshold of its own.
 */
export const ORGAN_DAMAGE_SYMPTOMS = [
  'CHEST_PAIN',
  'NEUROLOGICAL_SIGNS',
  'DYSPNEA',
  'VISUAL_DISTURBANCE',
  'SEVERE_HEADACHE',
] as const;

export type OrganDamageSymptom = (typeof ORGAN_DAMAGE_SYMPTOMS)[number];
