/**
 * Local-timezone date helpers for form defaults and filter bounds.
 * Never derive calendar dates from toISOString() — it renders the UTC date,
 * which is off by one for users east/west of UTC around midnight.
 */

const pad = (n: number): string => String(n).padStart(2, '0');

/** yyyy-MM-dd in the user's local timezone (for <input type="date">). */
export function localDateString(d: Date = new Date()): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** yyyy-MM-ddTHH:mm in the user's local timezone (for <input type="datetime-local">). */
export function nowLocalDatetime(d: Date = new Date()): string {
  return `${localDateString(d)}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * D49: a `datetime-local` value is the user's wall clock with no zone, and the
 * API stores result times on the server's UTC clock (the clock order times are
 * stamped with). Sending the wall clock as is recorded a result entered from a
 * workstation behind UTC hours BEFORE the order it answers.
 */
export function toServerDateTime(localValue: string): string {
  const instant = new Date(localValue);
  return Number.isNaN(instant.getTime()) ? localValue : instant.toISOString().slice(0, 19);
}

/** The reverse: a server (UTC, zone-less) time as a `datetime-local` value in the user's zone. */
export function toLocalInputValue(serverValue: string): string {
  const zoned = /(Z|[+-]\d{2}:\d{2})$/.test(serverValue) ? serverValue : serverValue + 'Z';
  const instant = new Date(zoned);
  if (Number.isNaN(instant.getTime())) {
    return serverValue.slice(0, 16);
  }
  return `${instant.getFullYear()}-${pad(instant.getMonth() + 1)}-${pad(instant.getDate())}T${pad(instant.getHours())}:${pad(instant.getMinutes())}`;
}

/**
 * D622: a server (UTC, zone-less) time as an instant, for the `date` pipe.
 * Passing the bare string to the pipe reads it as local time, so the shown
 * time is off by the UTC offset. Null for an empty or unparsable value.
 */
export function fromServerDateTime(serverValue: string | null | undefined): Date | null {
  if (!serverValue) {
    return null;
  }
  const zoned = /(Z|[+-]\d{2}:\d{2})$/.test(serverValue) ? serverValue : serverValue + 'Z';
  const instant = new Date(zoned);
  return Number.isNaN(instant.getTime()) ? null : instant;
}

/** A zone-less date AND time, as the API sends its UTC `LocalDateTime`s. */
const NAIVE_DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/;

/**
 * QA D777/D784: `new Date(serverValue)` for code, as `serverTime` is for
 * templates. `new Date('2026-10-24T15:33:17')` reads the zone-less UTC server
 * time as the browser's local time, so a label or an "expired?" check was off
 * by the reader's UTC offset. Only a zone-less date AND time is marked UTC
 * (the `serverTime` rule): a value with a zone or a bare date is read as is,
 * and an unreadable one gives an Invalid Date, as `new Date` would.
 */
export function serverInstant(serverValue: string): Date {
  return new Date(NAIVE_DATE_TIME.test(serverValue) ? `${serverValue}Z` : serverValue);
}

/**
 * #941 review: clinical-time forms (labor, delivery, postpartum, maternal
 * history) are checked against the server's UTC clock, so their
 * `datetime-local` values must be sent as UTC too. Sent as browser wall time,
 * "now" from a UTC+1 workstation was refused as the future, and a workstation
 * west of UTC could chart hours ahead. Returns a copy with the named fields
 * converted; empty fields are left out of the conversion.
 */
export function withServerDateTimes<F extends object>(form: F, fields: (keyof F)[]): F {
  const copy = { ...form };
  for (const field of fields) {
    const value = copy[field];
    if (typeof value === 'string' && value) {
      copy[field] = toServerDateTime(value) as F[keyof F];
    }
  }
  return copy;
}
