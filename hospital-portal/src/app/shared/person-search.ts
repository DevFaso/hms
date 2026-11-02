/**
 * QA D1019 / D1020 (D942 / D943 on the list): the one way a page filters
 * people it already holds by what the desk typed, folded like the server's
 * `PatientMatchKeys`:
 *
 * - a name is compared lower-cased and without accents, and the full name
 *   matches as well as each part ("Kiemde", "Pelagie", "Pélagie Kiemdé" and
 *   "kiemde pelagie" all find Pélagie Kiemdé);
 * - a phone number is compared on its digits, the last eight (the national
 *   subscriber number) when it has that many ("65 11 22 33", "65112233" and
 *   "+226 65 11 22 33" all find +22665112233).
 *
 * The pages used `x.toLowerCase().includes(term)`, which missed accents, full
 * names and every written phone format. `npm run guard:person-search` fails
 * on that shape anywhere in the portal.
 */

import { birthDateQuery } from './patient-query';

const COMBINING_MARKS = /[̀-ͯ]/g;
const SPACES = /\s+/g;
const NON_DIGITS = /\D/g;
const SUBSCRIBER_DIGITS = 8;

/** Lower-cased, accents removed, inner spaces collapsed, trimmed. */
export function foldSearchText(value: string | null | undefined): string {
  return (value ?? '')
    .normalize('NFD')
    .replace(COMBINING_MARKS, '')
    .toLowerCase()
    .replace(SPACES, ' ')
    .trim();
}

/** Whether `value` contains `term`, both folded. An empty term matches everything. */
export function textMatches(
  value: string | null | undefined,
  term: string | null | undefined,
): boolean {
  const needle = foldSearchText(term);
  return needle === '' || foldSearchText(value).includes(needle);
}

/**
 * Whether a person's name parts match `term`: any part alone, or the full
 * name in either order ("first middle last", "first last", "last first").
 */
export function personNameMatches(
  parts: { firstName?: string | null; middleName?: string | null; lastName?: string | null },
  term: string | null | undefined,
): boolean {
  const first = parts.firstName ?? '';
  const middle = parts.middleName ?? '';
  const last = parts.lastName ?? '';
  return [
    first,
    middle,
    last,
    `${first} ${last}`,
    `${last} ${first}`,
    `${first} ${middle} ${last}`,
  ].some((candidate) => candidate.trim() !== '' && textMatches(candidate, term));
}

/** The digits a number is compared on: an international 00 dropped, the last eight when longer. */
export function phoneSearchKey(value: string | null | undefined): string {
  let digits = (value ?? '').replace(NON_DIGITS, '');
  if (digits.startsWith('00')) digits = digits.slice(2);
  return digits.length > SUBSCRIBER_DIGITS ? digits.slice(-SUBSCRIBER_DIGITS) : digits;
}

/** A letter anywhere: a written phone number never has one (QA D1218). */
const LETTER = /\p{L}/u;

/**
 * Whether a stored number matches what was typed, however either is written.
 * A term with fewer than four digits is not a phone search (a year, a room).
 *
 * QA D1218 (round 23): a term with a LETTER is not a phone search either.
 * "CHW0410" (an MRN) was stripped to "0410" and found Aïcha Kinda, whose
 * phone is +22670004101, listed above the patient whose MRN it is.
 */
export function phoneMatches(
  stored: string | null | undefined,
  term: string | null | undefined,
): boolean {
  if (LETTER.test(term ?? '')) return false;
  const key = phoneSearchKey(term);
  if (key.length < 4) return false;
  return (stored ?? '').replace(NON_DIGITS, '').includes(key);
}

/**
 * QA D1218 (round 23): the row whose identifier (an MRN) IS what was typed
 * comes first; the order of the others is kept. Folded like the rest of the
 * search ("chw0410" finds CHW0410).
 */
export function exactIdentifierFirst<R>(
  rows: readonly R[],
  term: string | null | undefined,
  identifierOf: (row: R) => string | null | undefined,
): R[] {
  const needle = foldSearchText(term);
  if (needle === '') return [...rows];
  const exact = rows.filter((r) => foldSearchText(identifierOf(r)) === needle);
  return exact.length === 0 ? [...rows] : [...exact, ...rows.filter((r) => !exact.includes(r))];
}

/** How well a person's names answer `term`: lower is better (QA D1239). */
function nameRank(
  parts: { firstName?: string | null; middleName?: string | null; lastName?: string | null },
  identifier: string,
  needle: string,
): number {
  const names = [parts.firstName, parts.middleName, parts.lastName].map((n) => foldSearchText(n));
  const first = names[0];
  const last = names[2];
  const fulls = [`${first} ${last}`, `${last} ${first}`].map((f) => f.trim());
  if (identifier === needle || fulls.includes(needle)) return 0;
  if (names.includes(needle)) return 1;
  if (
    names.some((n) => n !== '' && n.startsWith(needle)) ||
    fulls.some((f) => f.startsWith(needle))
  )
    return 2;
  const words = names.join(' ').split(' ').filter(Boolean);
  if (words.some((w) => w.startsWith(needle))) return 3;
  return 4;
}

/**
 * QA D1239 (D1218 class, round 25): a patient typeahead shows the people whose
 * name STARTS with what was typed before the ones that merely contain it. "Awa"
 * filled the eight places with Sawadogos and never offered Awa Kaboré; the
 * list is now ranked, then cut. Exact identifier or full name first, then a
 * name equal to the term, then a name that starts with it, then a word inside
 * a name, then the rest;
 * the order of equals is kept (the server's).
 */
export function rankPeopleByName<
  R extends {
    firstName?: string | null;
    middleName?: string | null;
    lastName?: string | null;
    mrn?: string | null;
  },
>(rows: readonly R[], term: string | null | undefined): R[] {
  const needle = foldSearchText(term);
  if (needle === '') return [...rows];
  return rows
    .map((row, index) => ({ row, index, rank: nameRank(row, foldSearchText(row.mrn), needle) }))
    .sort((a, b) => a.rank - b.rank || a.index - b.index)
    .map((r) => r.row);
}

/**
 * QA D1274 (round 27): whether a stored birth date (yyyy-mm-dd, or an
 * ISO date-time) is the date typed, in any format the desk writes it
 * (patient-query.ts: 2020-03-15, 15/03/2020, 15-03-2020, 15032020).
 */
export function birthDateMatches(
  stored: string | null | undefined,
  term: string | null | undefined,
): boolean {
  const day = birthDateQuery(term);
  return day !== null && (stored ?? '').slice(0, 10) === day;
}

/**
 * QA D1274: the one filter of the patients a page already holds — the name
 * (folded, full or in part), the e-mail, the MRN, either phone number, and
 * the birth date, each read the way the server's search reads it. The patient
 * list filtered on everything but the birth date. `scripts/lib/patient-term-search.test.mjs`
 * fails the build on a page that composes its own filter from the parts.
 */
export function patientMatchesTerm(
  p: {
    firstName?: string | null;
    middleName?: string | null;
    lastName?: string | null;
    email?: string | null;
    mrn?: string | null;
    phoneNumberPrimary?: string | null;
    phoneNumberSecondary?: string | null;
    dateOfBirth?: string | null;
  },
  term: string | null | undefined,
): boolean {
  return (
    personNameMatches(p, term) ||
    textMatches(p.email, term) ||
    textMatches(p.mrn, term) ||
    phoneMatches(p.phoneNumberPrimary, term) ||
    phoneMatches(p.phoneNumberSecondary, term) ||
    birthDateMatches(p.dateOfBirth, term)
  );
}
