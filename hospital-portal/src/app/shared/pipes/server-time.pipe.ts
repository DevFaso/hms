import { Pipe, PipeTransform } from '@angular/core';

/** `2026-10-20T09:15:00` or `2026-10-20T09:15:00.123456`: a wall time with no zone. */
const NAIVE_ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/;

/**
 * QA D436: the server writes audit and notification times as `LocalDateTime`
 * in UTC and sends them without a zone. `DatePipe` reads a zone-less string
 * as the BROWSER's local time, so a reader outside UTC saw the UTC clock.
 * Marking the string as UTC lets `DatePipe` convert it to the reader's zone:
 *
 *     {{ row.eventTimestamp | serverTime | date: 'medium' }}
 *
 * Values that already carry a zone (`Z`, `+01:00`), dates without a time,
 * numbers and Date objects pass through untouched.
 */
@Pipe({ name: 'serverTime', standalone: true })
export class ServerTimePipe implements PipeTransform {
  transform<V>(value: V): V | string {
    return typeof value === 'string' && NAIVE_ISO.test(value) ? `${value}Z` : value;
  }
}
