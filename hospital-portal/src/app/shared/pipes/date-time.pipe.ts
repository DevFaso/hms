import { LOCALE_ID, Pipe, PipeTransform, inject } from '@angular/core';

/**
 * QA D1340 (round 29): a date and time on screen never shows seconds — "2
 * nov. 2026, 15:00", not "2 nov. 2026, 15:00:00". Angular's `date: 'medium'`
 * prints the medium time, with seconds, and was used in 91 templates.
 *
 * `{{ value | dateTime }}` is the locale's medium date with its short time
 * (`Intl.DateTimeFormat` `dateStyle: 'medium'`, `timeStyle: 'short'`): the
 * reader's own order of day, month and year and their 24 h or 12 h clock —
 * never a hand-written pattern.
 * `scripts/lib/round29-front-office.test.mjs` fails a template under its
 * covered directories that writes `date: 'medium'`.
 */
@Pipe({ name: 'dateTime', standalone: true })
export class DateTimePipe implements PipeTransform {
  private readonly locale = inject(LOCALE_ID);

  transform(value: string | number | Date | null | undefined): string | null {
    return formatDateTime(value, this.locale);
  }
}

const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * `value` as the locale's medium date and short time, or `null` when it is
 * missing or not a date. A bare `yyyy-MM-dd` is that day at midnight, local
 * time (as Angular's date pipe reads it), not UTC midnight.
 */
export function formatDateTime(
  value: string | number | Date | null | undefined,
  locale: string,
): string | null {
  if (value === null || value === undefined || value === '') return null;
  let date: Date;
  const day = typeof value === 'string' ? DATE_ONLY.exec(value) : null;
  if (day) date = new Date(Number(day[1]), Number(day[2]) - 1, Number(day[3]));
  else date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}
