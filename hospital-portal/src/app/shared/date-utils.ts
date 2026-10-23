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
