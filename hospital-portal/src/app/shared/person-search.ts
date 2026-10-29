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
export function textMatches(value: string | null | undefined, term: string | null | undefined): boolean {
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

/**
 * Whether a stored number matches what was typed, however either is written.
 * A term with fewer than four digits is not a phone search (a year, a room).
 */
export function phoneMatches(stored: string | null | undefined, term: string | null | undefined): boolean {
  const key = phoneSearchKey(term);
  if (key.length < 4) return false;
  return (stored ?? '').replace(NON_DIGITS, '').includes(key);
}
