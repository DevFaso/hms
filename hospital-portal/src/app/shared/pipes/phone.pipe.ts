import { Pipe, PipeTransform } from '@angular/core';

import { DEFAULT_CALLING_CODE } from '../phone-number';

/** U+00A0, a no-break space: written as a code point so nothing invisible sits in the source. */
export const NO_BREAK_SPACE = String.fromCodePoint(0xa0);

/**
 * QA D2799 / D2794 (r65): a stored phone number was printed raw,
 * « +22670000109 », on the profile and the patient record. A reader checks a
 * number in pairs: « +226 70 00 01 09 ».
 *
 * `{{ user.phoneNumber | appPhone }}`: a Burkina Faso number in E.164 (the
 * form the server stores, shared/phone-number.ts) is grouped as the calling
 * code and four pairs. The groups are joined by no-break spaces so a number
 * never wraps in the middle. Any other value (another country, a number typed
 * before the E.164 rule, a blank) is returned as it is: the pipe never guesses
 * another country's grouping and never drops a character.
 */
@Pipe({ name: 'appPhone', standalone: true })
export class PhonePipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return formatPhone(value);
  }
}

const LOCAL_E164 = new RegExp(
  `^[+]${DEFAULT_CALLING_CODE}([0-9]{2})([0-9]{2})([0-9]{2})([0-9]{2})$`,
);

export function formatPhone(value: string | null | undefined): string {
  if (value === null || value === undefined) return '';
  const local = LOCAL_E164.exec(value.trim());
  if (!local) return value;
  return [`+${DEFAULT_CALLING_CODE}`, local[1], local[2], local[3], local[4]].join(NO_BREAK_SPACE);
}
