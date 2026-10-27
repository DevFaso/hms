/**
 * The client-side copy of the server's phone rule, so a form can refuse a
 * value inline before it sends anything. It mirrors
 * `hospital-core/.../utility/PhoneNumbers.toE164` exactly: keep the two in step,
 * a looser copy lets through what the server then refuses in a banner, a
 * stricter one refuses what the server would have stored.
 */

/** Calling code the server assumes when the hospital's country is unknown: Burkina Faso. */
export const DEFAULT_CALLING_CODE = '226';

/** National subscriber numbers are 8 digits in the deployment country. */
const SIGNIFICANT_DIGITS = 8;

/** Characters a desk types between digit groups (Java `[\s.\-()/]`; `\s` is ASCII there). */
const FORMATTING = /[ \t\n\v\f\r.\-()/]/g;

/** E.164: a plus, then 8 to 15 digits, the first not zero. */
const E164 = /^\+[1-9]\d{7,14}$/;

/**
 * One number as typed, in E.164 (`+22670202020`), or `null` when the server
 * would refuse it. A local number (`70202020`) takes `callingCode`, its trunk
 * zero dropped unless the code is Burkina Faso's; `00` becomes `+`. A Burkina
 * Faso number must have exactly eight national digits.
 */
export function toE164(
  raw: string | null | undefined,
  callingCode: string = DEFAULT_CALLING_CODE,
): string | null {
  if (!raw?.trim()) {
    return null;
  }
  const compact = raw.replace(FORMATTING, '');
  let candidate: string;
  if (compact.startsWith('+')) {
    candidate = compact;
  } else if (compact.startsWith('00')) {
    candidate = '+' + compact.substring(2);
  } else if (/^\d+$/.test(compact)) {
    const national =
      compact.startsWith('0') && callingCode !== DEFAULT_CALLING_CODE
        ? compact.substring(1)
        : compact;
    candidate = '+' + callingCode + national;
  } else {
    return null;
  }
  if (!E164.test(candidate)) {
    return null;
  }
  if (
    candidate.startsWith('+' + DEFAULT_CALLING_CODE) &&
    candidate.length !== 1 + DEFAULT_CALLING_CODE.length + SIGNIFICANT_DIGITS
  ) {
    return null;
  }
  return candidate;
}
