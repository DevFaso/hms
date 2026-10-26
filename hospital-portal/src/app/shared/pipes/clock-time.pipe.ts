import { Pipe, PipeTransform } from '@angular/core';

/** `09:00`, `9:00:00` or `09:00:00.000`: a time of day as the API sends it. */
const TIME_OF_DAY = /^(\d{1,2}):(\d{2})(?::\d{2}(?:\.\d+)?)?$/;

/**
 * QA D830: appointment and slot times arrive as `LocalTime` strings
 * (`09:00:00`) and were printed as sent, seconds included. A time of day is
 * shown as hours and minutes:
 *
 *     {{ appt.startTime | clockTime }}  →  09:00
 *
 * Anything that is not a bare time of day is returned unchanged; null and
 * undefined give an empty string.
 */
@Pipe({ name: 'clockTime', standalone: true })
export class ClockTimePipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    if (value === null || value === undefined) return '';
    const match = TIME_OF_DAY.exec(value.trim());
    if (!match) return value;
    return `${match[1].padStart(2, '0')}:${match[2]}`;
  }
}
