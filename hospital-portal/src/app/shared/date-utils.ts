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
