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
  return genderedKey('PATIENTS.BORN', gender);
}

/**
 * QA D1327 (round 29; the D1311 class): any word that describes the patient
 * agrees with the patient's sex — "enregistrée dans 2 hôpitaux",
 * "registrada en 2 hospitales", "Déjà enregistrée dans cet hôpital". The
 * duplicate card fixed "Née" in round 28 and kept "enregistré" in the same
 * line. A key family `BASE`, `BASE_FEMALE`, `BASE_MALE` is picked here; an
 * unknown or other sex gets the neutral `BASE` ("enregistré(e)").
 * `scripts/lib/round29-front-office.test.mjs` holds every such family and
 * template to it.
 */
export function genderedKey(base: string, gender: string | null | undefined): string {
  switch ((gender ?? '').trim().toUpperCase()) {
    case 'FEMALE':
    case 'F':
      return `${base}_FEMALE`;
    case 'MALE':
    case 'M':
      return `${base}_MALE`;
    default:
      return base;
  }
}
