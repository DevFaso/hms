/**
 * QA D1274 (D1253 sibling, round 27): what a patient search box was given —
 * a birth date, an e-mail (or a piece of one), or anything else (a name, an
 * MRN, a phone number, which the server's own matching handles).
 *
 * Every staff patient picker sent a typed birth date as a NAME ("15/03/2020"
 * matched nobody, in any format), and an e-mail only to the exact-identifier
 * lookup, so a piece of one found nobody either. The one classification of
 * the portal lives here; `PatientService.list()` / `searchPatientsByTerm()` and the
 * page filters (`patientMatchesTerm` in person-search.ts) all go through it.
 *
 * Dates are read the way the desk writes them: ISO (2020-03-15, 2020/03/15),
 * day first (15/03/2020, 15-03-2020, 15.03.2020) and the eight digits of a
 * date typed without separators (15032020). Eight digits are also a phone
 * number in Burkina Faso, so a compact date is searched as both.
 */

const ISO = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$/;
const DAY_FIRST = /^(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})$/;
const COMPACT = /^(\d{2})(\d{2})(\d{4})$/;
const SPACE = /\s/;
const LETTER = /\p{L}/u;
/** Birth years read as dates; a later one finds nobody, so no clock is needed to bound it. */
const FIRST_YEAR = 1900;
const LAST_YEAR = 2999;

const DAYS_IN_MONTH = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

/** Days in month `m` (1–12) of year `y`, 29 February included (calendar arithmetic, no clock). */
function daysInMonth(y: number, m: number): number {
  const leap = (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0;
  return m === 2 && leap ? 29 : DAYS_IN_MONTH[m - 1];
}

function pad(n: string): string {
  return n.padStart(2, '0');
}

/** Year/month/day as yyyy-mm-dd when it is a real calendar day, else null. */
function isoDay(year: string, month: string, day: string): string | null {
  const y = Number(year);
  const m = Number(month);
  const d = Number(day);
  if (y < FIRST_YEAR || y > LAST_YEAR) return null;
  if (m < 1 || m > 12 || d < 1) return null;
  if (d > daysInMonth(y, m)) return null;
  return `${year}-${pad(month)}-${pad(day)}`;
}

/** The birth date a search term is, as yyyy-mm-dd, or null when it is not one. */
export function birthDateQuery(term: string | null | undefined): string | null {
  const t = (term ?? '').trim();
  let m = ISO.exec(t);
  if (m) return isoDay(m[1], m[2], m[3]);
  m = DAY_FIRST.exec(t);
  if (m) return isoDay(m[3], m[2], m[1]);
  m = COMPACT.exec(t);
  if (m) return isoDay(m[3], m[2], m[1]);
  return null;
}

/** Whether a birth-date term is also a phone number (the eight digits of 15032020). */
export function isCompactDate(term: string | null | undefined): boolean {
  return COMPACT.test((term ?? '').trim()) && birthDateQuery(term) !== null;
}

/**
 * The e-mail fragment a search term is (lower-cased), or null: anything with
 * an @, or a single word holding a letter and a dot or underscore
 * ("awa.kabore", "example.test"). A name ("Awa Kaboré", "Jean-Pierre") and a
 * phone number are not.
 */
export function emailQuery(term: string | null | undefined): string | null {
  const t = (term ?? '').trim();
  if (t.length < 3 || birthDateQuery(t) !== null) return null;
  if (SPACE.test(t)) return null;
  // A piece of an e-mail without its @: one word with a letter and a dot or underscore.
  const piece = LETTER.test(t) && (t.includes('.') || t.includes('_'));
  return t.includes('@') || piece ? t.toLowerCase() : null;
}
