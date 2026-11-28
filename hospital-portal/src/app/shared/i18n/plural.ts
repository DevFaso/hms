import { Pipe, PipeTransform, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { currentLocale } from './app-locale';

/**
 * QA D4315 (r81): counted sentences read « 1 membres du personnel », « 0 jours » or
 * « 3 désactivé(s) ». A counted text has two keys, `KEY_ONE` and `KEY_OTHER`, and the
 * language's own rule picks between them (`Intl.PluralRules`): in French 0 and 1 are
 * singular (« 0 jour », « 1 jour », « 2 jours »), in English and Spanish only 1 is.
 * A language with more categories (few, many) falls back on `_OTHER`.
 */
export function pluralSuffix(count: number, locale: string = currentLocale()): '_ONE' | '_OTHER' {
  const n = Number(count);
  if (!Number.isFinite(n)) return '_OTHER';
  let category: string;
  try {
    category = new Intl.PluralRules(locale).select(Math.abs(n));
  } catch {
    category = Math.abs(n) === 1 ? 'one' : 'other';
  }
  return category === 'one' ? '_ONE' : '_OTHER';
}

/** The key of a counted text: `STAFF.COUNT` and 1 give `STAFF.COUNT_ONE`. */
export function pluralKey(
  baseKey: string,
  count: number,
  locale: string = currentLocale(),
): string {
  return `${baseKey}${pluralSuffix(count, locale)}`;
}

/**
 * `{{ 'STAFF.MEMBERS_COUNT' | plural: total }}` reads `STAFF.MEMBERS_COUNT_ONE` or `STAFF.MEMBERS_COUNT_OTHER`, each
 * given `{{count}}` (and any extra parameter). Impure, like the translate pipe: the text
 * follows a language switch.
 */
@Pipe({ name: 'plural', standalone: true, pure: false })
export class PluralPipe implements PipeTransform {
  private readonly translate = inject(TranslateService);

  transform(
    baseKey: string,
    count: number | null | undefined,
    params?: Record<string, unknown>,
  ): string {
    const n = Number(count ?? 0);
    // The language the texts are in decides the form, so a text and its plural never disagree.
    const locale = this.translate.currentLang || currentLocale();
    return this.translate.instant(pluralKey(baseKey, n, locale), { ...params, count: n });
  }
}
