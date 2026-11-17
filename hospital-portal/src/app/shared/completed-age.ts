/**
 * QA D1087: an age is always told in COMPLETED units, as a clinician says it:
 * a child of 6 years 7 months 15 days is "6 years 7 months", never rounded up
 * to 8 (and Math.round could even print "6 years 12 months").
 */
export const DAYS_PER_MONTH = 30.4375;
export const DAYS_PER_YEAR = 365.25;

/** Completed months in an age given in days. */
export function completedMonths(ageDays: number): number {
  return Math.max(0, Math.floor(ageDays / DAYS_PER_MONTH));
}

/** Completed years, and the completed months after them (0–11). */
export function completedYearsAndMonths(ageDays: number): { years: number; months: number } {
  const years = Math.max(0, Math.floor(ageDays / DAYS_PER_YEAR));
  const months = Math.min(
    11,
    Math.max(0, Math.floor((ageDays - years * DAYS_PER_YEAR) / DAYS_PER_MONTH)),
  );
  return { years, months };
}
