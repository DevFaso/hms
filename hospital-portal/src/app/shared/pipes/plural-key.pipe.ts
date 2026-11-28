import { Pipe, PipeTransform } from '@angular/core';
import { currentLocale } from '../i18n/app-locale';

/**
 * QA D4194 (r81): « 2 renouvellement(s) restant(s) », « 1 ordonnances sur cette page », « 0 ordonnances ».
 * A counted sentence has two translations, `KEY` (plural) and `KEY_ONE` (singular), and this picks one by the
 * rule of the reader's language: in French 0 and 1 are singular (« 0 ordonnance », « 1 ordonnance »,
 * « 2 ordonnances »); in English and Spanish only 1 is (« 0 prescriptions », « 1 prescription »).
 *
 *     {{ count | pluralKey: 'PHARMACY.REFILL_REMAINING' | translate: { count: count } }}
 *
 * A count that is not a number takes the plural. Never « (s) » in a translation.
 */
export function pluralKey(
  count: number | string | null | undefined,
  key: string,
  locale: string = currentLocale(),
): string {
  const n = typeof count === 'number' ? count : Number(count);
  if (count === null || count === undefined || count === '' || !Number.isFinite(n)) return key;
  const singular = locale.startsWith('fr') ? Math.abs(n) < 2 : Math.abs(n) === 1;
  return singular ? `${key}_ONE` : key;
}

@Pipe({ name: 'pluralKey', standalone: true })
export class PluralKeyPipe implements PipeTransform {
  transform(count: number | string | null | undefined, key: string): string {
    return pluralKey(count, key);
  }
}
