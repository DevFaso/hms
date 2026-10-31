import { Pipe, PipeTransform } from '@angular/core';

import { currentLocale } from '../i18n/app-locale';
import { serverDateOrInstant } from '../date-utils';

/**
 * QA D1140: a day and a month with no year, in the reader's order — "31 oct."
 * in French, "31 oct" in Spanish, "Oct 31" in English. The portal dashboard's
 * lab card wrote `date: 'MMM d'`, a pattern that fixes the ENGLISH order in
 * every language ("oct. 31"). Angular's date pipe has no locale-ordered
 * day-month format, so this asks `Intl` for one.
 *
 * Pure: the language is fixed for the page's life (a switch reloads it).
 */
@Pipe({ name: 'dayMonth', standalone: true })
export class DayMonthPipe implements PipeTransform {
  transform(value: Date | string | null | undefined, locale = currentLocale()): string {
    if (value === null || value === undefined || value === '') return '';
    // A bare calendar date is that local day; a server time is read as UTC
    // (serverDateOrInstant), like every other date on screen.
    const date = value instanceof Date ? value : serverDateOrInstant(value);
    if (Number.isNaN(date.getTime())) return '';
    return new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short' }).format(date);
  }
}
