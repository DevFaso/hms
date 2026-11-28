import { Pipe, PipeTransform } from '@angular/core';
import { currentLocale } from './app-locale';

/**
 * QA D4194 (r81): a counted sentence read « 2 écart(s) à examiner », « 1 opération(s) », « 1 ordonnances ».
 * A sentence that counts has two keys, `KEY_ONE` (the singular) and `KEY` (the plural), and the reader's
 * language says which: `Intl.PluralRules`, so French takes the singular for 0 and 1 (« 0 ordonnance »,
 * « 1 ordonnance ») and English and Spanish for 1 only.
 *
 * ```html
 * {{ 'PHARMACY_MONEY.RECON.DIFFERENCES' | pluralKey: n | translate: { count: n } }}
 * ```
 * `scripts/lib/pharmacy-plurals.test.mjs` refuses a « (s) » in the pharmacy strings and a `_ONE` key
 * without its plural.
 */
export function pluralKey(key: string, count: number, locale: string = currentLocale()): string {
  return isSingular(count, locale) ? `${key}_ONE` : key;
}

/** Whether `count` things are written in the singular in `locale`. */
export function isSingular(count: number, locale: string = currentLocale()): boolean {
  const n = Math.abs(Number(count));
  if (!Number.isFinite(n)) return false;
  return new Intl.PluralRules(locale).select(n) === 'one';
}

@Pipe({ name: 'pluralKey', standalone: true })
export class PluralKeyPipe implements PipeTransform {
  transform(key: string, count: number | null | undefined): string {
    return pluralKey(key, Number(count ?? 0));
  }
}
