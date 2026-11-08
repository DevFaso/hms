import { formatDate } from '@angular/common';
import { LOCALE_ID, Pipe, PipeTransform, inject } from '@angular/core';

/**
 * QA D1340 (round 29, user decision): a date and time on screen never shows
 * seconds — "2 nov. 2026, 15:00" in French, "2 nov 2026, 15:00" in Spanish,
 * "Nov 2, 2026, 3:00 PM" in English. Angular's `date: 'medium'` printed
 * "2 nov. 2026, 15:00:00" (a delivery time on the labour card, and some
 * ninety other templates), and a fixed pattern such as `'d MMM y, HH:mm'`
 * would fix the English field order in every language (QA D1140). This is the
 * locale's medium date and short time, joined as the locale's `medium` joins
 * them, without the seconds:
 *
 *     {{ row.recordedAt | serverTime | dateTime }}
 *
 * Null, undefined, '' and an unreadable value give ''. Pure: the language is
 * fixed for the page's life (a switch reloads it), as `LOCALE_ID` is.
 *
 * `scripts/lib/date-time-seconds.test.mjs` fails the build on a template or
 * component that shows a date-time with seconds.
 */
@Pipe({ name: 'dateTime', standalone: true })
export class DateTimePipe implements PipeTransform {
  private readonly locale = inject(LOCALE_ID);

  transform(value: Date | string | number | null | undefined): string {
    if (value === null || value === undefined || value === '') return '';
    // The date pipe's own parsing (a zone-less ISO string is local time), so a
    // value reads here exactly as it did under `date: 'medium'`.
    try {
      const day = formatDate(value, 'mediumDate', this.locale);
      return `${day}, ${formatDate(value, 'shortTime', this.locale)}`;
    } catch {
      return '';
    }
  }
}
