import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

import { SlotInventoryService } from '../services/slot-inventory.service';

/** The request field a missing overbooking reason is refused on (QA D1429). */
export const OVERBOOK_REASON_FIELD = 'overbookReason';

/** A refusal that says the time is off the slots and asks why. */
export function refusesForOverbookReason(err: unknown): boolean {
  return (err as { error?: { field?: unknown } } | null)?.error?.field === OVERBOOK_REASON_FIELD;
}

/**
 * QA D1429: whether the chosen time is outside every open published slot of the clinician,
 * so the form asks why the visit is overbooked. Only the offer of the field: the server
 * decides, and its refusal opens the field too, so a failed read asks for nothing.
 */
export function isOffSlot(
  slots: SlotInventoryService,
  staffId: string | null | undefined,
  date: string | null | undefined,
  time: string | null | undefined,
): Observable<boolean> {
  if (!staffId || !date || !time) {
    return of(false);
  }
  const start = `${date}T${time.slice(0, 5)}`;
  return slots.searchAllOpen({ staffId, from: date, to: date }).pipe(
    map(({ rows }) => !rows.some((slot) => slot.startAt?.slice(0, 16) === start)),
    catchError(() => of(false)),
  );
}
