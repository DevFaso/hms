import { localDateString } from '../date-utils';

/**
 * QA D1215 (round 23): the reception waitlist saved a window from 20/11 to
 * 10/11, and a recall due on 01/01/2020. A date picker is bounded by the
 * template (`[min]` / `[max]`, see `scripts/lib/date-range-bounds.test.mjs`),
 * but a typed date slips past the native picker, so the submit checks too and
 * refuses on the field (`refuseFields`) with `DATE_RULES.*`.
 *
 * Both take `yyyy-MM-dd` strings (what `<input type="date">` gives); an empty
 * value is never refused here (whether it is required is the caller's rule).
 */

/** The range's end is before its start (both given). Equal dates are a one-day range. */
export function endsBeforeStart(
  start: string | null | undefined,
  end: string | null | undefined,
): boolean {
  return !!start && !!end && end < start;
}

/** The date is before today on the reader's calendar. */
export function isBeforeToday(date: string | null | undefined, today = localDateString()): boolean {
  return !!date && date < today;
}
