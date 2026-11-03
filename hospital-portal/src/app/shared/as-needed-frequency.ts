/**
 * QA round 30 (user decision 2026-11-02): an as-needed (PRN, "si besoin")
 * order carries the minimum interval between two doses, written by the
 * prescriber. The server decides with `MarDoseSchedule.read` (hospital-core);
 * this is the same reading of the words, so the form shows the interval
 * field exactly when the server will ask for it. A frequency with "STAT" is a
 * single dose, never as-needed.
 *
 * QA D1360: the phrases are the server's `MarDoseSchedule.AS_NEEDED_PHRASES`
 * (scripts/lib/as-needed-frequency.test.mjs holds the two lists equal), and
 * both sides read the shared fixture `as-needed-frequency.cases.json`. Case,
 * accents and punctuation are ignored; a phrase matches where a word starts,
 * anywhere in the frequency ("Si douleurs", "q4h PRN"), never inside a word.
 */
export const AS_NEEDED_PHRASES: readonly string[] = [
  'PRN',
  'AS NEEDED',
  'AS REQUIRED',
  'WHEN NEEDED',
  'WHEN REQUIRED',
  'IF NEEDED',
  'IF REQUIRED',
  'IF NECESSARY',
  'ON DEMAND',
  'IF PAIN',
  'IF FEVER',
  'SI BESOIN',
  'AU BESOIN',
  'SELON BESOIN',
  'SELON LES BESOINS',
  'EN CAS DE BESOIN',
  'A LA DEMANDE',
  'SI NECESSAIRE',
  'SI DOULEUR',
  'SI FIEVRE',
  'EN CAS DE DOULEUR',
  'EN CAS DE FIEVRE',
  'SI NECESARIO',
  'SI ES NECESARIO',
  'EN CASO NECESARIO',
  'SEGUN NECESIDAD',
  'A DEMANDA',
  'SI DOLOR',
  'SI FIEBRE',
  'EN CASO DE DOLOR',
  'EN CASO DE FIEBRE',
];

/** Whether `phrase` occurs in `words` where a word starts (after a space, a digit or nothing). */
function startsAWord(words: string, phrase: string): boolean {
  let at = words.indexOf(phrase);
  while (at >= 0) {
    if (at === 0 || !/[A-Z]/.test(words.charAt(at - 1))) return true;
    at = words.indexOf(phrase, at + 1);
  }
  return false;
}

export function isAsNeededFrequency(frequency: string | null | undefined): boolean {
  if (!frequency?.trim()) return false;
  const upper = frequency.trim().normalize('NFD').replace(/\p{M}/gu, '').toUpperCase();
  if (upper.replace(/[_.,]/g, ' ').includes('STAT')) return false;
  const words = upper.replace(/[^A-Z0-9]+/g, ' ').trim();
  return AS_NEEDED_PHRASES.some((phrase) => startsAWord(words, phrase));
}

/** The longest minimum interval written, in hours (the server's `PrnMinInterval.MAX_HOURS`). */
export const PRN_MAX_INTERVAL_HOURS = 72;
