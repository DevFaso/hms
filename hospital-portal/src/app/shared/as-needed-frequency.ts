/**
 * QA round 30 (user decision 2026-11-02): an as-needed (PRN, "si besoin")
 * order carries the minimum interval between two doses, written by the
 * prescriber. The server decides with `MarDoseSchedule.read` (hospital-core);
 * this is the same reading of the words, so the form shows the interval
 * field exactly when the server will ask for it: "PRN", "as needed",
 * "si besoin", "si necesario", "si es necesario", any case, accents ignored.
 * A frequency with "STAT" is a single dose, never as-needed.
 */
export function isAsNeededFrequency(frequency: string | null | undefined): boolean {
  if (!frequency?.trim()) return false;
  const upper = frequency
    .trim()
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toUpperCase()
    .replace(/[_.,]/g, ' ');
  if (upper.includes('STAT')) return false;
  return (
    upper.includes('PRN') ||
    upper.includes('AS NEEDED') ||
    upper.includes('SI BESOIN') ||
    upper.includes('SI NECESARIO') ||
    upper.includes('SI ES NECESARIO')
  );
}

/** The longest minimum interval written, in hours (the server's `PrnMinInterval.MAX_HOURS`). */
export const PRN_MAX_INTERVAL_HOURS = 72;
