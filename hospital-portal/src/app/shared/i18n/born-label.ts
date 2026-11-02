/**
 * QA D1311 (round 28): "Born in …" agrees with the patient's sex. The
 * registration duplicate card read "Rokia Bonkoungou Nacido en 1987" for a
 * woman: one key, worded for a man in Spanish. French and Spanish inflect the
 * participle (Né / Née, Nacido / Nacida); English does not.
 *
 * Every "born" string shown for a patient takes its key from here, so the
 * sex picks the word: `{{ bornLabelKey(m.gender) | translate }}`. An unknown
 * or other sex gets the neutral form (Né(e) en, Nacido/a en).
 * `scripts/lib/born-agreement.test.mjs` holds the keys and templates to it.
 */
export function bornLabelKey(gender: string | null | undefined): string {
  switch ((gender ?? '').trim().toUpperCase()) {
    case 'FEMALE':
    case 'F':
      return 'PATIENTS.BORN_FEMALE';
    case 'MALE':
    case 'M':
      return 'PATIENTS.BORN_MALE';
    default:
      return 'PATIENTS.BORN';
  }
}
